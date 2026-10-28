package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.mofanyi.config.ScreenshotProperties;
import com.mofanyi.job.GenerationExecutionCondition;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.springframework.stereotype.Service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 交付前渲染验证（plan-agent-pipeline.md P0-4）。
 *
 * <p>生成完成后用无头浏览器按 <b>3 档视口</b>（桌面 1440 / 平板 768 / 手机 375）真实渲染
 * 生成的 {@code index.html}，采集结构化问题：
 * <ul>
 *   <li>图裂：可见且声明了 src 的图片满足 {@code img.complete && naturalWidth === 0}</li>
 *   <li>横向溢出：{@code scrollingElement.scrollWidth > clientWidth}</li>
 *   <li>空白页：body 可见文本过少</li>
 *   <li>控制台严重错误（驱动支持时采集）</li>
 * </ul>
 * 报告落盘到 {@code .agent/verify.json} 供追溯，同时转成文本喂给修复 agent。
 *
 * <p>范围：html / multi_file / vue dist 构建产物。
 * 构建产物通过进程内临时 HTTP 静态源加载，避免 Vite ES module 和 CSS 在 {@code file://}
 * 下被浏览器按跨源资源拒绝。
 */
@Slf4j
@Service
@Conditional(GenerationExecutionCondition.class)
@ConditionalOnProperty(name = "codegen.browser.engine",
        havingValue = "selenium", matchIfMissing = true)
public class RenderVerifier implements BrowserVerifierPort {

    /**
     * 3 档视口
     */
    private static final List<Viewport> VIEWPORTS = List.of(
            new Viewport("桌面", "desktop", 1440, 900),
            new Viewport("平板", "tablet", 768, 900),
            new Viewport("手机", "mobile", 375, 800));

    /**
     * body 可见文本低于此长度视为"疑似空白页"
     */
    private static final int MIN_TEXT_LENGTH = 20;

    /**
     * 控制台错误最多采集条数
     */
    private static final int MAX_CONSOLE_ERRORS = 10;

    /**
     * 页面加载后等待渲染/图片就绪的毫秒数
     */
    private static final long SETTLE_MILLIS = 1200;

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("gif", "image/gif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("map", "application/json; charset=utf-8"));

    /**
     * 页面内取证脚本：返回 JSON
     */
    private static final String COLLECT_JS =
            "return (function () {"
                    + "  var broken = [];"
                    + "  var imgs = document.images || [];"
                    + "  for (var i = 0; i < imgs.length; i++) {"
                    + "    var im = imgs[i];"
                    + "    var src = im.getAttribute('src') || '';"
                    + "    if (!src.trim()) { continue; }"
                    + "    var cs = window.getComputedStyle(im);"
                    + "    if (cs.display === 'none' || cs.visibility === 'hidden') { continue; }"
                    + "    if (im.complete && im.naturalWidth === 0) {"
                    + "      broken.push((im.currentSrc || src || '(空 src)').slice(0, 200));"
                    + "    }"
                    + "  }"
                    + "  var se = document.scrollingElement || document.documentElement;"
                    + "  var body = document.body;"
                    + "  var text = body ? (body.innerText || '').trim() : '';"
                    + "  return JSON.stringify({brokenImages: broken,"
                    + "    overflow: (se.scrollWidth - se.clientWidth) > 1,"
                    + "    scrollWidth: se.scrollWidth, clientWidth: se.clientWidth,"
                    + "    textLength: text.length});"
                    + "})();";

    private final ScreenshotProperties screenshotProperties;
    private final CodeFileWriteService codeFileWriteService;

    public RenderVerifier(ScreenshotProperties screenshotProperties, CodeFileWriteService codeFileWriteService) {
        this.screenshotProperties = screenshotProperties;
        this.codeFileWriteService = codeFileWriteService;
    }

    private record Viewport(String label, String name, int width, int height) {
    }

    /**
     * 单个视口的渲染结果
     */
    public record ViewportResult(String name, String label, int width, List<String> brokenImages,
                                 boolean overflow, int scrollWidth, int clientWidth,
                                 int textLength, List<String> consoleErrors,
                                 String screenshotPath, String screenshotSha256) {

        public boolean hasProblem() {
            return !brokenImages.isEmpty() || overflow || textLength < MIN_TEXT_LENGTH
                    || !consoleErrors.isEmpty();
        }
    }

    /**
     * 渲染验证报告
     */
    public record RenderReport(List<ViewportResult> viewports, List<String> errors) {

        /**
         * 是否真的跑出了结果（浏览器起不来时为 false，调用方应跳过修复回路）
         */
        public boolean isUsable() {
            return viewports != null && !viewports.isEmpty();
        }

        public boolean hasProblems() {
            return viewports != null && viewports.stream().anyMatch(ViewportResult::hasProblem);
        }

        /**
         * 转成喂给修复 agent 的文本（只列有问题的视口）
         */
        public String toPromptText() {
            if (viewports == null || viewports.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (ViewportResult v : viewports) {
                if (!v.hasProblem()) {
                    continue;
                }
                count++;
                sb.append("- ").append(v.label()).append("（").append(v.width()).append("px 宽）：");
                List<String> parts = new ArrayList<>();
                if (!v.brokenImages().isEmpty()) {
                    parts.add("图裂/空图 " + v.brokenImages().size() + " 处 → " + String.join(" | ", v.brokenImages()));
                }
                if (v.overflow()) {
                    parts.add("横向溢出（内容宽 " + v.scrollWidth() + "px > 视口 " + v.clientWidth() + "px）");
                }
                if (v.textLength() < MIN_TEXT_LENGTH) {
                    parts.add("页面几乎空白（可见文本仅 " + v.textLength() + " 字符）");
                }
                if (!v.consoleErrors().isEmpty()) {
                    parts.add("控制台报错 " + v.consoleErrors().size() + " 条 → " + String.join(" | ", v.consoleErrors()));
                }
                sb.append(String.join("；", parts)).append("\n");
            }
            if (count == 0) {
                return "";
            }
            return "渲染验证发现 " + count + " 个视口存在问题：\n" + sb;
        }

        /**
         * 一行摘要（日志用）
         */
        public String summary() {
            if (viewports == null || viewports.isEmpty()) {
                return "未产出结果";
            }
            long bad = viewports.stream().filter(ViewportResult::hasProblem).count();
            return bad == 0
                    ? "3 档视口全部通过"
                    : bad + "/" + viewports.size() + " 档视口存在问题";
        }
    }

    /**
     * 渲染验证：逐视口渲染并采集问题。浏览器不可用时返回 report.isUsable()==false。
     */
    @Override
    public RenderReport verify(CodeGenTypeEnum type, Long appId) {
        return verify(type, appId, "latest");
    }

    @Override
    public RenderReport verify(CodeGenTypeEnum type, Long appId, String stage) {
        List<ViewportResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (type == null || appId == null || appId <= 0) {
            return new RenderReport(results, List.of("参数非法"));
        }
        String projectDir = codeFileWriteService.getProjectDir(type, appId);
        Path sourceDir = type == CodeGenTypeEnum.VUE
                ? Paths.get(projectDir, "dist").toAbsolutePath().normalize()
                : Paths.get(projectDir).toAbsolutePath().normalize();
        File index = sourceDir.resolve("index.html").toFile();
        if (!FileUtil.exist(index)) {
            return new RenderReport(results, List.of("未找到 index.html，跳过渲染验证"));
        }
        WebDriver driver = null;
        try (StaticSiteServer server = StaticSiteServer.start(sourceDir)) {
            // Vite 构建产物包含 ES module；file:// 会被 Chromium 按跨源资源拒绝。
            // 临时 HTTP 源只在本次验证期间监听回环地址，不暴露到外部网络。
            String url = server.baseUrl() + "/";
            driver = createDriver(VIEWPORTS.get(0));
            driver.get(url);
            waitReady(driver);
            for (Viewport vp : VIEWPORTS) {
                try {
                    driver.manage().window().setSize(new Dimension(vp.width(), vp.height()));
                    Thread.sleep(SETTLE_MILLIS);
                    results.add(inspect(driver, vp, type, appId, stage));
                } catch (Exception e) {
                    log.warn("[verify] 视口 {} 渲染失败，appId={}", vp.name(), appId, e);
                    errors.add("视口 " + vp.label() + " 渲染失败：" + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[verify] 浏览器不可用，跳过渲染验证，appId={}", appId, e);
            errors.add("浏览器不可用：" + e.getMessage());
        } finally {
            quit(driver);
        }
        RenderReport report = new RenderReport(results, errors);
        log.info("[verify] appId={} 渲染验证完成：{}", appId, report.summary());
        return report;
    }

    private static final class StaticSiteServer implements AutoCloseable {

        private final Path root;
        private final HttpServer server;

        private StaticSiteServer(Path root, HttpServer server) {
            this.root = root;
            this.server = server;
        }

        static StaticSiteServer start(Path root) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StaticSiteServer site = new StaticSiteServer(root, server);
            server.createContext("/", site::handle);
            server.start();
            return site;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                        && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respond(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                    return;
                }
                String pathname = exchange.getRequestURI().getPath();
                if ("/favicon.ico".equals(pathname)) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                String relative = pathname == null || pathname.isBlank() || "/".equals(pathname)
                        ? "index.html" : pathname.replaceFirst("^/+", "");
                Path file = root.resolve(relative).normalize();
                if (!file.startsWith(root)) {
                    respond(exchange, 403, "Forbidden", "text/plain; charset=utf-8");
                    return;
                }
                if (!Files.isRegularFile(file)
                        && relative.indexOf('.') < 0
                        && Files.isRegularFile(root.resolve("index.html"))) {
                    file = root.resolve("index.html");
                }
                if (!Files.isRegularFile(file)) {
                    respond(exchange, 404, "Not Found", "text/plain; charset=utf-8");
                    return;
                }
                byte[] body = Files.readAllBytes(file);
                String filename = file.getFileName().toString();
                int dot = filename.lastIndexOf('.');
                String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
                exchange.getResponseHeaders().set("Content-Type",
                        CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"));
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.sendResponseHeaders(200,
                        "HEAD".equalsIgnoreCase(exchange.getRequestMethod()) ? -1 : body.length);
                if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseBody().write(body);
                }
            } finally {
                exchange.close();
            }
        }

        private static void respond(HttpExchange exchange, int status,
                                    String body, String contentType) throws IOException {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * 报告落盘到 {@code .agent/verify-{stage}.json}（阶段产物，可追溯）。
     * 分 stage 落盘（before / after）避免"修复后的结果覆盖掉修复前的问题清单"。
     *
     * @param stage 阶段标识，如 before / after
     */
    @Override
    public void persistReport(CodeGenTypeEnum type, Long appId, RenderReport report, String stage) {
        try {
            BrowserReportPersistence.persist(codeFileWriteService, type, appId, report, stage);
        } catch (RuntimeException e) {
            log.warn("[verify] 报告落盘失败，appId={}", appId, e);
        }
    }

    private WebDriver createDriver(Viewport vp) {
        String browser = screenshotProperties.getBrowser();
        String driverPath = screenshotProperties.getDriverPath();
        try {
            return BrowserFactory.create(browser, driverPath, true, vp.width(), vp.height(), true);
        } catch (RuntimeException e) {
            // 开启控制台日志的会话创建失败时降级重试（部分驱动不支持 loggingPrefs）
            log.warn("[verify] 带日志能力的会话创建失败，降级重试：{}", e.getMessage());
            return BrowserFactory.create(browser, driverPath, true, vp.width(), vp.height(), false);
        }
    }

    private void waitReady(WebDriver driver) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(d -> "complete".equals(((JavascriptExecutor) d)
                            .executeScript("return document.readyState")));
        } catch (Exception ignore) {
            // 加载超时不阻塞取证
        }
    }

    private ViewportResult inspect(WebDriver driver, Viewport vp, CodeGenTypeEnum type,
                                   Long appId, String stage) {
        Object raw = ((JavascriptExecutor) driver).executeScript(COLLECT_JS);
        JSONObject obj = toJsonObject(raw);
        List<String> broken = new ArrayList<>();
        JSONArray arr = obj.getJSONArray("brokenImages");
        if (arr != null) {
            for (Object o : arr) {
                broken.add(String.valueOf(o));
            }
        }
        String screenshotPath = captureScreenshot(driver, type, appId, stage, vp);
        return new ViewportResult(
                vp.name(), vp.label(), vp.width(), broken,
                obj.getBool("overflow", false),
                obj.getInt("scrollWidth", 0),
                obj.getInt("clientWidth", 0),
                obj.getInt("textLength", 0),
                collectConsoleErrors(driver), screenshotPath,
                screenshotPath == null ? null : sha256(Path.of(screenshotPath)));
    }

    private String captureScreenshot(WebDriver driver, CodeGenTypeEnum type, Long appId,
                                     String stage, Viewport viewport) {
        try {
            byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            String safeStage = stage == null ? "latest" : stage.replaceAll("[^a-zA-Z0-9_-]", "_");
            String projectDir = codeFileWriteService.getProjectDir(type, appId);
            File out = Paths.get(projectDir, ".agent", "screenshots",
                    safeStage + "-" + viewport.name() + ".png").toFile();
            FileUtil.mkParentDirs(out);
            FileUtil.writeBytes(png, out);
            return out.getAbsolutePath();
        } catch (Exception e) {
            log.debug("[verify] 截图失败，viewport={}", viewport.name(), e);
            return null;
        }
    }

    private String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(java.nio.file.Files.readAllBytes(file)));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 归一化取证脚本的返回值：Chromium 可能返回 JSON 字符串，也可能返回 Map（取决于驱动实现）。
     */
    private JSONObject toJsonObject(Object raw) {
        if (raw == null) {
            throw new IllegalStateException("取证脚本返回 null");
        }
        if (raw instanceof java.util.Map<?, ?> map) {
            return new JSONObject(map);
        }
        String text = String.valueOf(raw);
        if (!text.trim().startsWith("{")) {
            throw new IllegalStateException("取证脚本返回非 JSON：" + StrUtil.maxLength(text, 200));
        }
        return JSONUtil.parseObj(text);
    }

    /**
     * 采集控制台严重错误；驱动/会话不支持时返回空列表（不阻塞验证）
     */
    private List<String> collectConsoleErrors(WebDriver driver) {
        List<String> errors = new ArrayList<>();
        try {
            LogEntries entries = driver.manage().logs().get(LogType.BROWSER);
            for (LogEntry entry : entries) {
                if (entry.getLevel() == java.util.logging.Level.SEVERE) {
                    errors.add(StrUtil.maxLength(entry.getMessage(), 200));
                    if (errors.size() >= MAX_CONSOLE_ERRORS) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // 该驱动不支持浏览器日志，忽略
        }
        return errors;
    }

    private void quit(WebDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            driver.quit();
        } catch (Exception ignore) {
            // 忽略关闭异常
        }
    }
}
