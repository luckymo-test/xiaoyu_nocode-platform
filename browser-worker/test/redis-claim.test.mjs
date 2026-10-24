import assert from "node:assert/strict";
import test from "node:test";
import {
  autoClaimUnsupported,
  claimablePendingIds
} from "../redis-claim.mjs";

test("detects only the Redis < 6.2 XAUTOCLAIM error", () => {
  assert.equal(autoClaimUnsupported(
    new Error("ERR unknown command `XAUTOCLAIM`, with args beginning with:")
  ), true);
  assert.equal(autoClaimUnsupported(new Error("NOGROUP no such group")), false);
});

test("translates XPENDING rows into bounded XCLAIM ids", () => {
  const rows = [
    ["1-0", "worker-a", "60001", "2"],
    ["2-0", "worker-b", "1000", "1"],
    ["3-0", "worker-a", "120000", "4"],
    ["bad"]
  ];

  assert.deepEqual(claimablePendingIds(rows, 60_000, 10), ["1-0", "3-0"]);
  assert.deepEqual(claimablePendingIds(rows, 60_000, 1), ["1-0"]);
});
