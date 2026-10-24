import http from "node:http";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { chromium } from "playwright-core";
import { createClient } from "redis";

const PORT = Number(process.env.BROWSER_WORKER_PORT || 8090);
const WORKSPACE_ROOT = path.resolve(
  process.env.BROWSER_WORKER_WORKSPACE_ROOT || path.join(process.cwd(), "..", "tmp")
);
const BROWSER_CHANNEL = process.env.BROWSER_WORKER_CHANNEL || "msedge";
const BROWSER_EXECUTABLE = process.env.BROWSER_WORKER_EXECUTABLE || "";
const MAX_BODY_BYTES = 1024 * 1024;
const SETTLE_MILLIS = Number(process.env.BROWSER_WORKER_SETTLE_MILLIS || 1200);
const MAX_EVENTS = 50;
const REDIS_URL = process.env.BROWSER_WORKER_REDIS_URL || "redis://127.0.0.1:6379";
const REDIS_STREAM = process.env.BROWSER_WORKER_REDIS_STREAM
  || "codegen:browser-verify:requests";
const REDIS_RESULT_PREFIX = process.env.BROWSER_WORKER_REDIS_RESULT_PREFIX
  || "codegen:browser-verify:result:";
const REDIS_GROUP = process.env.BROWSER_WORKER_REDIS_GROUP
  || "codegen-browser-verifiers";
const REDIS_CONSUMER = process.env.BROWSER_WORKER_REDIS_CONSUMER
  || `browser-worker-${process.pid}`;
const REDIS_RESULT_TTL_SECONDS = Number(
  process.env.BROWSER_WORKER_REDIS_RESULT_TTL_SECONDS || 300
);
const MIME_TYPES = new Map([
  [".html", "text/html; charset=utf-8"],
  [".css", "text/css; charset=utf-8"],
  [".js", "text/javascript; charset=utf-8"],
  [".mjs", "text/javascript; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".svg", "image/svg+xml"],
  [".png", "image/png"],
  [".jpg", "image/jpeg"],
  [".jpeg", "image/jpeg"],
  [".webp", "image/webp"],
  [".avif", "image/avif"],
  [".woff", "font/woff"],
  [".woff2", "font/woff2"]
]);

function json(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(payload),
    "cache-control": "no-store"
  });
  res.end(payload);
}

function scopedPath(value, label) {
  if (!value || typeof value !== "string") {
    throw new Error(`${label} 不能为空`);
  }
  const resolved = path.resolve(value);
  const relative = path.relative(WORKSPACE_ROOT, resolved);
  if (relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new Error(`${label} 超出允许的工作区根目录`);
  }
  return resolved;
}

async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) {
      throw new Error("请求体超过 1MB");
    }
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

async function sha256(file) {
  return crypto.createHash("sha256").update(await fs.readFile(file)).digest("hex");
}

async function startStaticServer(root) {
  const server = http.createServer(async (req, res) => {
    try {
      const pathname = decodeURIComponent(new URL(req.url, "http://127.0.0.1").pathname);
      const relative = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "");
      const file = path.resolve(root, relative);
      const pathFromRoot = path.relative(root, file);
      if (pathFromRoot.startsWith("..") || path.isAbsolute(pathFromRoot)) {
        res.writeHead(403);
        res.end("Forbidden");
        return;
      }
      const data = await fs.readFile(file);
      res.writeHead(200, {
        "content-type": MIME_TYPES.get(path.extname(file).toLowerCase())
          || "application/octet-stream",
        "cache-control": "no-store"
      });
      res.end(data);
    } catch {
      res.writeHead(404);
      res.end("Not Found");
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  return {
    origin: `http://127.0.0.1:${address.port}`,
    close: () => new Promise((resolve) => server.close(resolve))
  };
}

async function verify(request) {
  const sourceDir = scopedPath(request.sourceDir, "sourceDir");
  const outputDir = scopedPath(request.outputDir, "outputDir");
  const indexFile = path.join(sourceDir, "index.html");
  await fs.access(indexFile);
  await fs.mkdir(path.join(outputDir, "screenshots"), { recursive: true });

  const stage = String(request.stage || "latest").replace(/[^a-zA-Z0-9_-]/g, "_");
  const viewports = Array.isArray(request.viewports) && request.viewports.length
    ? request.viewports.slice(0, 5)
    : [
        { name: "desktop", label: "桌面", width: 1440, height: 900 },
        { name: "tablet", label: "平板", width: 768, height: 900 },
        { name: "mobile", label: "手机", width: 375, height: 800 }
      ];
  const launchOptions = { headless: true };
  if (BROWSER_EXECUTABLE) {
    launchOptions.executablePath = BROWSER_EXECUTABLE;
  } else {
    launchOptions.channel = BROWSER_CHANNEL;
  }

  const staticServer = await startStaticServer(sourceDir);
  let browser = null;
  let context = null;
  try {
    browser = await chromium.launch(launchOptions);
    context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  } catch (error) {
    if (browser) {
      await browser.close().catch(() => {});
    }
    await staticServer.close().catch(() => {});
    throw error;
  }
  let page = null;
  try {
    page = await context.newPage();
  } catch (error) {
    await context.close().catch(() => {});
    await browser.close().catch(() => {});
    await staticServer.close().catch(() => {});
    throw error;
  }
  const errors = [];
  const networkFailures = [];
  const consoleErrors = [];
  const byViewport = new Map();
  let currentViewport = viewports[0].name;

  const observation = (name) => {
    if (!byViewport.has(name)) {
      byViewport.set(name, { network: [], console: [] });
    }
    return byViewport.get(name);
  };

  page.on("console", (message) => {
    if (message.type() !== "error") {
      return;
    }
    const text = message.text().slice(0, 300);
    const row = `${currentViewport}: ${text}`;
    consoleErrors.push(row);
    observation(currentViewport).console.push(text);
  });
  page.on("pageerror", (error) => {
    const text = `pageerror: ${error.message}`.slice(0, 300);
    consoleErrors.push(`${currentViewport}: ${text}`);
    observation(currentViewport).console.push(text);
  });
  page.on("requestfailed", (requestEvent) => {
    const failure = requestEvent.failure();
    const text = `${requestEvent.method()} ${requestEvent.url()} ${failure?.errorText || "failed"}`;
    networkFailures.push({ viewport: currentViewport, url: requestEvent.url(), error: text });
    observation(currentViewport).network.push(text.slice(0, 300));
  });
  page.on("response", (response) => {
    if (response.status() < 400) {
      return;
    }
    const text = `${response.request().method()} ${response.url()} HTTP ${response.status()}`;
    networkFailures.push({
      viewport: currentViewport,
      url: response.url(),
      status: response.status(),
      error: text
    });
    observation(currentViewport).network.push(text.slice(0, 300));
  });

  const traceFile = path.join(outputDir, `trace-${stage}.zip`);
  const results = [];
  try {
    await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
    await page.goto(`${staticServer.origin}/index.html`, {
      waitUntil: "load",
      timeout: 30000
    });
    for (const viewport of viewports) {
      currentViewport = String(viewport.name || "viewport");
      await page.setViewportSize({
        width: Number(viewport.width),
        height: Number(viewport.height)
      });
      await page.waitForTimeout(SETTLE_MILLIS);
      const dom = await page.evaluate(() => {
        const brokenImages = [];
        for (const image of document.images || []) {
          const src = image.getAttribute("src") || "";
          const style = getComputedStyle(image);
          if (!src.trim() || style.display === "none" || style.visibility === "hidden") {
            continue;
          }
          if (image.complete && image.naturalWidth === 0) {
            brokenImages.push(String(image.currentSrc || src).slice(0, 200));
          }
        }
        const scrolling = document.scrollingElement || document.documentElement;
        const text = document.body ? (document.body.innerText || "").trim() : "";
        return {
          brokenImages,
          overflow: scrolling.scrollWidth - scrolling.clientWidth > 1,
          scrollWidth: scrolling.scrollWidth,
          clientWidth: scrolling.clientWidth,
          textLength: text.length
        };
      });
      const screenshot = path.join(
        outputDir, "screenshots", `${stage}-${currentViewport}.png`
      );
      await page.screenshot({ path: screenshot, fullPage: false });
      const observed = observation(currentViewport);
      results.push({
        viewport: currentViewport,
        label: viewport.label || currentViewport,
        width: Number(viewport.width),
        ...dom,
        consoleErrors: observed.console.slice(0, MAX_EVENTS),
        networkErrors: observed.network.slice(0, MAX_EVENTS),
        screenshotPath: screenshot,
        screenshotSha256: await sha256(screenshot)
      });
    }
  } catch (error) {
    errors.push(`Playwright 执行失败：${String(error?.message || error).slice(0, 500)}`);
  } finally {
    try {
      await context.tracing.stop({ path: traceFile });
    } catch (error) {
      errors.push(`trace 保存失败：${String(error?.message || error).slice(0, 300)}`);
    }
    await context.close().catch(() => {});
    await browser.close().catch(() => {});
    await staticServer.close().catch(() => {});
  }

  const report = {
    generatedAt: new Date().toISOString(),
    verificationId: request.verificationId || null,
    jobId: request.jobId || null,
    revisionId: request.revisionId || null,
    stage,
    engine: "playwright",
    hasProblems: results.some((item) =>
      item.brokenImages.length > 0
      || item.overflow
      || item.textLength < 20
      || item.consoleErrors.length > 0
      || item.networkErrors.some((text) => /HTTP [45]\d\d|CORS|access-control/i.test(text))
    ),
    viewports: results,
    networkFailures: networkFailures.slice(0, MAX_EVENTS * viewports.length),
    consoleErrors: consoleErrors.slice(0, MAX_EVENTS * viewports.length),
    tracePath: traceFile,
    errors
  };
  await fs.writeFile(
    path.join(outputDir, `verify-${stage}.json`),
    JSON.stringify(report, null, 2),
    "utf8"
  );
  const networkJson = JSON.stringify(report.networkFailures, null, 2);
  const consoleJson = JSON.stringify(report.consoleErrors, null, 2);
  await fs.writeFile(path.join(outputDir, `network-${stage}.json`), networkJson, "utf8");
  await fs.writeFile(path.join(outputDir, `console-${stage}.json`), consoleJson, "utf8");
  await fs.writeFile(path.join(outputDir, "network-failures.json"), networkJson, "utf8");
  await fs.writeFile(path.join(outputDir, "console-errors.json"), consoleJson, "utf8");
  return report;
}

const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    json(res, 200, {
      status: "UP",
      engine: "playwright",
      channel: BROWSER_EXECUTABLE || BROWSER_CHANNEL,
      workspaceRoot: WORKSPACE_ROOT
    });
    return;
  }
  if (req.method !== "POST" || req.url !== "/verify") {
    json(res, 404, { status: "NOT_FOUND" });
    return;
  }
  try {
    const payload = await readJson(req);
    const report = await verify(payload);
    json(res, 200, report);
  } catch (error) {
    json(res, 500, {
      status: "ERROR",
      errors: [String(error?.message || error).slice(0, 500)]
    });
  }
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(
    `[playwright-worker] listening=http://127.0.0.1:${PORT} channel=${BROWSER_EXECUTABLE || BROWSER_CHANNEL} root=${WORKSPACE_ROOT}`
  );
});

let redisClient = null;

async function processRedisMessages(messages) {
  for (const [recordId, fields] of messages) {
    const values = new Map();
    for (let index = 0; index < fields.length; index += 2) {
      values.set(fields[index], fields[index + 1]);
    }
    const payloadText = values.get("payload");
    let task = null;
    try {
      task = JSON.parse(payloadText);
      const report = await verify(task);
      const resultKey = REDIS_RESULT_PREFIX + task.verificationId;
      await redisClient.set(resultKey, JSON.stringify(report), {
        EX: REDIS_RESULT_TTL_SECONDS
      });
    } catch (error) {
      const verificationId = task?.verificationId
        || values.get("verificationId")
        || recordId;
      await redisClient.set(
        REDIS_RESULT_PREFIX + verificationId,
        JSON.stringify({
          viewports: [],
          errors: [String(error?.message || error).slice(0, 500)]
        }),
        { EX: REDIS_RESULT_TTL_SECONDS }
      );
    } finally {
      await redisClient.xAck(REDIS_STREAM, REDIS_GROUP, recordId);
    }
  }
}

async function startRedisConsumer() {
  // 兼容项目现有 Redis 5.0：不发送 RESP3 HELLO。
  redisClient = createClient({ url: REDIS_URL, RESP: 2 });
  redisClient.on("error", (error) => {
    console.error(`[playwright-worker] redis error: ${error.message}`);
  });
  await redisClient.connect();
  try {
    await redisClient.sendCommand([
      "XGROUP", "CREATE", REDIS_STREAM, REDIS_GROUP, "0", "MKSTREAM"
    ]);
  } catch (error) {
    if (!String(error?.message || error).includes("BUSYGROUP")) {
      throw error;
    }
  }
  console.log(
    `[playwright-worker] redis stream=${REDIS_STREAM} group=${REDIS_GROUP} consumer=${REDIS_CONSUMER}`
  );
  let nextPendingClaimAt = 0;
  while (redisClient?.isOpen) {
    try {
      if (Date.now() >= nextPendingClaimAt) {
        nextPendingClaimAt = Date.now() + 30000;
        const claimed = await redisClient.sendCommand([
          "XAUTOCLAIM", REDIS_STREAM, REDIS_GROUP, REDIS_CONSUMER,
          "60000", "0-0", "COUNT", "10"
        ]);
        await processRedisMessages(claimed?.[1] || []);
      }
      const response = await redisClient.sendCommand([
        "XREADGROUP", "GROUP", REDIS_GROUP, REDIS_CONSUMER,
        "COUNT", "1", "BLOCK", "5000",
        "STREAMS", REDIS_STREAM, ">"
      ]);
      const messages = response?.[0]?.[1] || [];
      await processRedisMessages(messages);
    } catch (error) {
      if (redisClient?.isOpen) {
        console.error(`[playwright-worker] redis consume failed: ${error.message}`);
        await new Promise((resolve) => setTimeout(resolve, 1000));
      }
    }
  }
}

startRedisConsumer().catch((error) => {
  console.error(`[playwright-worker] redis disabled: ${error.message}`);
});

function shutdown(signal) {
  console.log(`[playwright-worker] shutting down: ${signal}`);
  server.close(() => process.exit(0));
  redisClient?.quit().catch(() => {});
  setTimeout(() => process.exit(1), 5000).unref();
}

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));
