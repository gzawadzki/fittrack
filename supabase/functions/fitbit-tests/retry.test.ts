import test from "node:test";
import assert from "node:assert/strict";
import { classifyUpstreamResponse, decideEmptyHrWindow } from "../fitbit-shared/retry.ts";

function fakeHeaders(map: Record<string, string>) {
  return { get: (name: string) => map[name.toLowerCase()] ?? null };
}

test("classifyUpstreamResponse: 401 -> refresh_and_retry", () => {
  const d = classifyUpstreamResponse({ status: 401, headers: fakeHeaders({}) });
  assert.equal(d.kind, "refresh_and_retry");
});

test("classifyUpstreamResponse: 429 respects Retry-After header", () => {
  const d = classifyUpstreamResponse({
    status: 429,
    headers: fakeHeaders({ "retry-after": "120" }),
  });
  assert.deepEqual(d, { kind: "wait_and_retry", retryAfterSeconds: 120 });
});

test("classifyUpstreamResponse: 429 without header falls back to default", () => {
  const d = classifyUpstreamResponse({ status: 429, headers: fakeHeaders({}) });
  assert.equal(d.kind, "wait_and_retry");
  assert.ok((d as any).retryAfterSeconds > 0);
});

test("classifyUpstreamResponse: 200 -> ok", () => {
  const d = classifyUpstreamResponse({ status: 200, headers: fakeHeaders({}) });
  assert.deepEqual(d, { kind: "ok" });
});

test("classifyUpstreamResponse: 500 -> fail", () => {
  const d = classifyUpstreamResponse({ status: 503, headers: fakeHeaders({}) });
  assert.equal(d.kind, "fail");
});

test("decideEmptyHrWindow: defers within the not-yet-synced grace window", () => {
  const d = decideEmptyHrWindow({
    windowEndIso: "2026-08-01T10:50:00.000Z",
    nowIso: "2026-08-01T10:52:00.000Z", // 2 min after window end
    attemptCount: 0,
  });
  assert.equal(d.kind, "defer_pending");
});

test("decideEmptyHrWindow: accepts as final once outside the grace window", () => {
  const d = decideEmptyHrWindow({
    windowEndIso: "2026-08-01T10:50:00.000Z",
    nowIso: "2026-08-01T11:20:00.000Z", // 30 min after window end
    attemptCount: 3,
  });
  assert.equal(d.kind, "ok");
});
