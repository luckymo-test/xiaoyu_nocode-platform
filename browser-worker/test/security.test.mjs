import assert from "node:assert/strict";
import test from "node:test";
import {
  assertBindPolicy,
  assertContainerPolicy,
  assertProxyPolicy,
  createConcurrencyGate,
  detectContainer,
  parsePositiveInteger,
  resolveBindAddress,
  resolveProxyServer
} from "../security.mjs";

test("detectContainer recognizes environment and marker files", () => {
  assert.equal(detectContainer({
    env: { container: "docker" },
    pathExists: () => false,
    readText: () => ""
  }), true);
  assert.equal(detectContainer({
    env: {},
    pathExists: (path) => path === "/.dockerenv",
    readText: () => ""
  }), true);
  assert.equal(detectContainer({
    env: {},
    pathExists: () => false,
    readText: () => "0::/system.slice/generation-worker.service"
  }), false);
});

test("container policy fails closed", () => {
  assert.throws(
    () => assertContainerPolicy({ required: true, inContainer: false }),
    /要求容器运行/
  );
  assert.doesNotThrow(
    () => assertContainerPolicy({ required: true, inContainer: true })
  );
});

test("bind policy only permits public bind inside a container", () => {
  assert.equal(resolveBindAddress("127.0.0.1"), "127.0.0.1");
  assert.equal(resolveBindAddress("0.0.0.0"), "0.0.0.0");
  assert.throws(() => resolveBindAddress("192.168.1.10"), /不允许/);
  assert.throws(
    () => assertBindPolicy({ bindAddress: "0.0.0.0", inContainer: false }),
    /只能监听回环地址/
  );
  assert.doesNotThrow(
    () => assertBindPolicy({ bindAddress: "0.0.0.0", inContainer: true })
  );
});

test("proxy validation rejects credentials and paths", () => {
  assert.equal(resolveProxyServer("http://egress-proxy:3128"), "http://egress-proxy:3128");
  assert.equal(resolveProxyServer(""), null);
  assert.throws(() => resolveProxyServer("http://u:p@proxy:3128"), /不允许/);
  assert.throws(() => resolveProxyServer("http://proxy:3128/path"), /不允许/);
  assert.throws(
    () => assertProxyPolicy({ required: true, proxyServer: null }),
    /PROXY_SERVER/
  );
});

test("positive integer parsing has explicit bounds", () => {
  assert.equal(parsePositiveInteger(undefined, 1), 1);
  assert.equal(parsePositiveInteger("4", 1, { min: 1, max: 8 }), 4);
  assert.throws(() => parsePositiveInteger("0", 1), /1～128/);
  assert.throws(() => parsePositiveInteger("9", 1, { min: 1, max: 8 }), /1～8/);
});

test("concurrency gate rejects excess work and releases exactly once", () => {
  const gate = createConcurrencyGate(1);
  const release = gate.tryAcquire();
  assert.equal(typeof release, "function");
  assert.equal(gate.tryAcquire(), null);
  assert.deepEqual(gate.snapshot(), { active: 1, limit: 1 });
  release();
  release();
  assert.deepEqual(gate.snapshot(), { active: 0, limit: 1 });
  assert.equal(typeof gate.tryAcquire(), "function");
});
