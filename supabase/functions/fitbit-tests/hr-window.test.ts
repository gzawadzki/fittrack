import test from "node:test";
import assert from "node:assert/strict";
import {
  computeHrWindow,
  matchAndAggregate,
  splitWindowByDay,
} from "../fitbit-shared/hr-window.ts";

test("computeHrWindow pads 5 minutes on each side", () => {
  const w = computeHrWindow(
    "2026-08-01T10:00:00.000Z",
    "2026-08-01T10:45:00.000Z",
  );
  assert.equal(w.startIso, "2026-08-01T09:55:00.000Z");
  assert.equal(w.endIso, "2026-08-01T10:50:00.000Z");
});

test("computeHrWindow clamps to at most one calendar day", () => {
  const w = computeHrWindow(
    "2026-08-01T00:00:00.000Z",
    "2026-08-02T23:00:00.000Z",
  );
  const spanMs = Date.parse(w.endIso) - Date.parse(w.startIso);
  assert.equal(spanMs, 24 * 60 * 60 * 1000);
});

test("computeHrWindow rejects endedAt before startedAt", () => {
  assert.throws(() =>
    computeHrWindow("2026-08-01T10:00:00.000Z", "2026-08-01T09:00:00.000Z")
  );
});

test("splitWindowByDay returns a single segment for a same-day window", () => {
  const segments = splitWindowByDay(
    "2026-08-01T09:55:00.000Z",
    "2026-08-01T10:50:00.000Z",
  );
  assert.equal(segments.length, 1);
  assert.equal(segments[0].dateYyyyMmDd, "2026-08-01");
  assert.equal(segments[0].startHHmm, "09:55");
  assert.equal(segments[0].endHHmm, "10:50");
});

test("splitWindowByDay splits a window that crosses midnight", () => {
  const segments = splitWindowByDay(
    "2026-08-01T23:50:00.000Z",
    "2026-08-02T00:10:00.000Z",
  );
  assert.equal(segments.length, 2);
  assert.equal(segments[0].dateYyyyMmDd, "2026-08-01");
  assert.equal(segments[0].endHHmm, "23:59");
  assert.equal(segments[1].dateYyyyMmDd, "2026-08-02");
  assert.equal(segments[1].startHHmm, "00:00");
  assert.equal(segments[1].endHHmm, "00:10");
});

test("matchAndAggregate computes avg/max and leaves no-data exercises as null, never 0", () => {
  const samples = [
    { ts: "2026-08-01T10:02:00.000Z", bpm: 100 },
    { ts: "2026-08-01T10:05:00.000Z", bpm: 140 },
    { ts: "2026-08-01T10:08:00.000Z", bpm: 120 },
    // sample 61s after ex-squat ends at 10:12:00 -> used for recovery delta
    { ts: "2026-08-01T10:13:00.000Z", bpm: 90 },
  ];
  const exercises = [
    { exerciseId: "ex-squat", startIso: "2026-08-01T10:02:00.000Z", endIso: "2026-08-01T10:12:00.000Z" },
    { exerciseId: "ex-empty", startIso: "2026-08-01T10:20:00.000Z", endIso: "2026-08-01T10:22:00.000Z" },
  ];

  const result = matchAndAggregate(samples, exercises);
  const squat = result.find((r) => r.exerciseId === "ex-squat")!;
  assert.equal(squat.sampleCount, 3);
  assert.equal(squat.avgBpm, (100 + 140 + 120) / 3);
  assert.equal(squat.maxBpm, 140);
  assert.equal(squat.recoveryDeltaBpm, 140 - 90);

  const empty = result.find((r) => r.exerciseId === "ex-empty")!;
  assert.equal(empty.sampleCount, 0);
  assert.equal(empty.avgBpm, null, "missing data must be null, not a false 0");
  assert.equal(empty.maxBpm, null);
  assert.equal(empty.recoveryDeltaBpm, null);
});
