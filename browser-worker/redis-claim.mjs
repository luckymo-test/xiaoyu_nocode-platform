export function autoClaimUnsupported(error) {
  const text = String(error?.message || error || "").toLowerCase();
  return text.includes("unknown command") && text.includes("xautoclaim");
}

/**
 * Redis 5 does not implement XAUTOCLAIM. Its XPENDING extended form returns
 * [id, consumer, idleMillis, deliveryCount], which can be translated to XCLAIM.
 */
export function claimablePendingIds(rows, minIdleMillis, limit = 10) {
  if (!Array.isArray(rows)) {
    return [];
  }
  return rows
    .filter((row) => Array.isArray(row) && row.length >= 3)
    .filter((row) => Number(row[2]) >= minIdleMillis)
    .slice(0, Math.max(1, limit))
    .map((row) => String(row[0]));
}
