import fs from "node:fs";

const LOOPBACK_ADDRESSES = new Set(["127.0.0.1", "::1"]);
const CONTAINER_MARKERS = ["/.dockerenv", "/run/.containerenv"];
const CONTAINER_CGROUP_PATTERN =
  /(?:docker|kubepods|libpod|containerd|garden|podman)/i;

/**
 * Detect the usual Docker, Podman, Kubernetes and containerd markers.
 * This is a startup policy check, not a cryptographic proof of isolation.
 */
export function detectContainer({
  env = process.env,
  pathExists = fs.existsSync,
  readText = (file) => fs.readFileSync(file, "utf8")
} = {}) {
  if (String(env.container || "").toLowerCase() === "docker"
      || String(env.DOCKER_CONTAINER || "").toLowerCase() === "true") {
    return true;
  }
  if (CONTAINER_MARKERS.some((marker) => pathExists(marker))) {
    return true;
  }
  try {
    return CONTAINER_CGROUP_PATTERN.test(readText("/proc/1/cgroup"));
  } catch {
    return false;
  }
}

export function assertContainerPolicy({ required, inContainer }) {
  if (required && !inContainer) {
    throw new Error(
      "生产隔离要求容器运行，但当前进程未检测到 Docker/Podman/Kubernetes 容器标记"
    );
  }
}

export function resolveBindAddress(raw = "127.0.0.1") {
  const value = String(raw || "").trim();
  if (value === "0.0.0.0" || LOOPBACK_ADDRESSES.has(value)) {
    return value;
  }
  throw new Error(`不允许的 Browser Worker 监听地址：${value}`);
}

export function assertBindPolicy({ bindAddress, inContainer }) {
  if (!LOOPBACK_ADDRESSES.has(bindAddress) && !inContainer) {
    throw new Error("非容器进程只能监听回环地址，拒绝暴露 Browser Worker");
  }
}

export function parsePositiveInteger(raw, fallback, { min = 1, max = 128 } = {}) {
  if (raw === undefined || raw === null || String(raw).trim() === "") {
    return fallback;
  }
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < min || value > max) {
    throw new Error(`配置值必须是 ${min}～${max} 的整数：${raw}`);
  }
  return value;
}

export function resolveProxyServer(raw = "") {
  const value = String(raw || "").trim();
  if (!value) {
    return null;
  }
  let proxy;
  try {
    proxy = new URL(value);
  } catch {
    throw new Error("BROWSER_WORKER_PROXY_SERVER 不是合法 URL");
  }
  if (!["http:", "https:"].includes(proxy.protocol)) {
    throw new Error("BROWSER_WORKER_PROXY_SERVER 只允许 http/https");
  }
  if (proxy.username || proxy.password || proxy.search || proxy.hash
      || (proxy.pathname && proxy.pathname !== "/")) {
    throw new Error("BROWSER_WORKER_PROXY_SERVER 不允许凭据、路径、查询或片段");
  }
  return proxy.origin;
}

export function assertProxyPolicy({ required, proxyServer }) {
  if (required && !proxyServer) {
    throw new Error("生产隔离要求配置 BROWSER_WORKER_PROXY_SERVER");
  }
}

/**
 * Fail-fast admission gate. HTTP and Redis tasks share the same cap so that
 * multiple Chromium contexts cannot exhaust the worker container.
 */
export function createConcurrencyGate(limit) {
  let active = 0;
  return {
    tryAcquire() {
      if (active >= limit) {
        return null;
      }
      active++;
      let released = false;
      return () => {
        if (!released) {
          released = true;
          active--;
        }
      };
    },
    snapshot() {
      return { active, limit };
    }
  };
}
