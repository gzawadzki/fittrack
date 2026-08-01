// Pure decision logic for how fitbit-sync should react to a Fitbit API
// response. Kept dependency-free (no Deno/fetch calls here) so it can be
// unit tested with plain fixtures in Node.

export type RetryDecision =
  | { kind: "refresh_and_retry" }
  | { kind: "wait_and_retry"; retryAfterSeconds: number }
  | { kind: "defer_pending"; nextRetryAt: string }
  | { kind: "fail"; reason: string }
  | { kind: "ok" };

export interface UpstreamResponseLike {
  status: number;
  headers: { get(name: string): string | null };
}

const DEFAULT_RETRY_AFTER_SECONDS = 60;
// Fitbit intraday HR for "today" (or very recent windows) can lag behind
// real time by a few minutes while their pipeline ingests device data. We
// treat an empty/zero-sample response for a window that ended recently as
// "not synced yet" rather than a real 0-sample result, and back off
// exponentially capped at 30 minutes.
const MIN_DEFER_BACKOFF_SECONDS = 30;
const MAX_DEFER_BACKOFF_SECONDS = 30 * 60;
const NOT_YET_SYNCED_GRACE_MS = 20 * 60 * 1000; // 20 min after window end

export function classifyUpstreamResponse(
  res: UpstreamResponseLike,
): RetryDecision {
  if (res.status === 401) {
    return { kind: "refresh_and_retry" };
  }
  if (res.status === 429) {
    const header = res.headers.get("retry-after");
    const seconds = header ? parseInt(header, 10) : DEFAULT_RETRY_AFTER_SECONDS;
    return {
      kind: "wait_and_retry",
      retryAfterSeconds: Number.isFinite(seconds) && seconds > 0
        ? seconds
        : DEFAULT_RETRY_AFTER_SECONDS,
    };
  }
  if (res.status >= 500) {
    return { kind: "fail", reason: `fitbit_upstream_${res.status}` };
  }
  if (res.status >= 400) {
    return { kind: "fail", reason: `fitbit_upstream_${res.status}` };
  }
  return { kind: "ok" };
}

/**
 * Decides whether an empty-looking intraday HR result should be treated as
 * "genuinely no data" vs "Fitbit hasn't finished syncing this window yet".
 * Only applies the grace/defer behavior while the window end is recent;
 * older windows with zero samples are accepted as final (device wasn't worn,
 * etc.) rather than retried forever.
 */
export function decideEmptyHrWindow(opts: {
  windowEndIso: string;
  nowIso: string;
  attemptCount: number;
}): RetryDecision {
  const windowEndMs = Date.parse(opts.windowEndIso);
  const nowMs = Date.parse(opts.nowIso);
  const withinGrace = nowMs - windowEndMs < NOT_YET_SYNCED_GRACE_MS;

  if (!withinGrace) {
    return { kind: "ok" };
  }

  const backoffSeconds = Math.min(
    MAX_DEFER_BACKOFF_SECONDS,
    MIN_DEFER_BACKOFF_SECONDS * Math.pow(2, opts.attemptCount),
  );
  const nextRetryAt = new Date(nowMs + backoffSeconds * 1000).toISOString();
  return { kind: "defer_pending", nextRetryAt };
}
