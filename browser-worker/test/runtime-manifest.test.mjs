import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..", "..");

function read(relative) {
  return fs.readFileSync(path.join(root, relative), "utf8");
}

test("browser container manifest keeps production isolation defaults", () => {
  const compose = read("sandbox-runtime/compose.yaml");
  const dockerfile = read("sandbox-runtime/Dockerfile.browser-worker");

  for (const required of [
    "read_only: true",
    "cap_drop:",
    "- ALL",
    "no-new-privileges:true",
    "pids_limit: 256",
    "mem_limit: 2g",
    "cpus: 1.5",
    "SANDBOX_WORKSPACE_ROOT:?",
    "sandbox-internal",
    "internal: true"
  ]) {
    assert.ok(compose.includes(required), `compose 缺少 ${required}`);
  }
  assert.ok(!compose.includes("docker.sock"), "不得挂载 Docker socket");
  assert.ok(dockerfile.includes("USER pwuser"));
  assert.ok(dockerfile.includes("npm ci --omit=dev --ignore-scripts"));
  assert.ok(dockerfile.includes("BROWSER_WORKER_REQUIRE_CONTAINER=true"));
});

test("squid policy denies everything outside the reviewed hosts", () => {
  const config = read("sandbox-runtime/squid.conf");

  assert.ok(config.includes("http_access allow local_net allowed_domains safe_ports"));
  assert.ok(config.includes("http_access deny all"));
  assert.ok(config.includes(".images.pexels.com"));
  assert.ok(config.includes(".cdn.pixabay.com"));
  assert.ok(config.includes("cache deny all"));
});

test("destructive isolation probe covers all required failure classes", () => {
  const probe = read("sandbox-runtime/probe-isolation.sh");

  for (const marker of [
    "read-only root filesystem",
    "direct internet bypass",
    "memory quota",
    "fork bomb",
    "infinite loop",
    "trap 'compose down"
  ]) {
    assert.ok(probe.includes(marker), `probe 缺少 ${marker}`);
  }
});
