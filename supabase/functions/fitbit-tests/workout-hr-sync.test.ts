// Definition-of-done test for M6: a fixture simulating delayed Fitbit data
// must transition a workout's fitbitSyncStatus from PENDING to SYNCED across
// two sync attempts, without ever representing missing samples as false
// zero values.

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { syncWorkoutHr } from "../fitbit-shared/workout-hr-sync.ts";
import type { SyncStatusRecord } from "../fitbit-shared/workout-hr-sync.ts";
import type { HrSample } from "../fitbit-shared/hr-window.ts";

const fixturePath = fileURLToPath(
  new URL("./fixtures/workout-delayed-sync.json", import.meta.url),
);
const fixture = JSON.parse(readFileSync(fixturePath, "utf-8"));

function buildSecondAttemptDataset() {
  // Dense 1s samples covering ex-squat (10:02-10:12) and ex-bench
  // (10:15-10:25) windows plus a recovery sample just after ex-squat ends.
  // ex-not-worn (10:30-10:32) intentionally gets zero samples.
  const dataset: Array<{ time: string; value: number }> = [];
  const push = (hh: number, mm: number, ss: number, bpm: number) => {
    dataset.push({
      time: `${String(hh).padStart(2, "0")}:${String(mm).padStart(2, "0")}:${
        String(ss).padStart(2, "0")
      }`,
      value: bpm,
    });
  };
  push(10, 2, 0, 100);
  push(10, 5, 0, 150);
  push(10, 8, 0, 130);
  push(10, 13, 0, 95); // ~60s after ex-squat ends -> recovery sample
  push(10, 15, 0, 110);
  push(10, 20, 0, 145);
  push(10, 24, 0, 125);
  return dataset;
}

function makeInMemoryHarness() {
  const statusRows = new Map<string, SyncStatusRecord>();
  const savedSamples: HrSample[] = [];
  const savedAggregates: any[] = [];

  return {
    statusStore: {
      async get(id: string) {
        return statusRows.get(id) ?? null;
      },
      async save(record: SyncStatusRecord) {
        statusRows.set(record.workoutSessionId, record);
      },
    },
    sampleStore: {
      async save(_workoutId: string, _userId: string, samples: HrSample[]) {
        savedSamples.push(...samples);
      },
    },
    aggregateStore: {
      async save(_workoutId: string, _userId: string, aggregates: any[]) {
        savedAggregates.length = 0;
        savedAggregates.push(...aggregates);
      },
    },
    exerciseWindows: {
      async getExerciseWindows() {
        return fixture.exercises;
      },
    },
    statusRows,
    savedSamples,
    savedAggregates,
  };
}

test("delayed Fitbit sync: first attempt defers to PENDING, second attempt reaches SYNCED", async () => {
  const harness = makeInMemoryHarness();
  let call = 0;

  const tokens = {
    async getAccessToken() {
      return "fake-access-token";
    },
    async refreshAccessToken() {
      return "fake-access-token-refreshed";
    },
  };

  const fitbitClient = {
    async fetchIntradayHr(_token: string, _date: string, _s: string, _e: string) {
      call += 1;
      if (call === 1) {
        // First attempt: Fitbit hasn't ingested the device data yet.
        return {
          status: 200,
          headers: { get: () => null },
          async json() {
            return fixture.firstAttemptResponse;
          },
        };
      }
      return {
        status: 200,
        headers: { get: () => null },
        async json() {
          return {
            "activities-heart-intraday": { dataset: buildSecondAttemptDataset() },
          };
        },
      };
    },
  };

  // --- Attempt 1: simulate "now" just after the workout ends, inside the
  // not-yet-synced grace window -> must defer to PENDING, must NOT write
  // aggregates yet.
  const attempt1 = await syncWorkoutHr({
    workout: fixture.workout,
    fitbitClient,
    tokens,
    statusStore: harness.statusStore,
    sampleStore: harness.sampleStore,
    aggregateStore: harness.aggregateStore,
    exerciseWindows: harness.exerciseWindows,
    now: () => "2026-08-01T10:52:00.000Z",
  });

  assert.equal(attempt1.status, "PENDING");
  assert.equal(attempt1.importedSampleCount, 0);
  assert.ok(attempt1.retryAfterSeconds && attempt1.retryAfterSeconds > 0);
  assert.equal(harness.statusRows.get(fixture.workout.id)?.status, "PENDING");
  assert.equal(harness.savedAggregates.length, 0, "must not write aggregates while pending");

  // --- Attempt 2: simulate a later poll where Fitbit now has the data.
  const attempt2 = await syncWorkoutHr({
    workout: fixture.workout,
    fitbitClient,
    tokens,
    statusStore: harness.statusStore,
    sampleStore: harness.sampleStore,
    aggregateStore: harness.aggregateStore,
    exerciseWindows: harness.exerciseWindows,
    now: () => "2026-08-01T11:05:00.000Z",
  });

  assert.equal(attempt2.status, "SYNCED");
  assert.equal(attempt2.retryAfterSeconds, null);
  assert.equal(attempt2.importedSampleCount, 7);
  assert.equal(harness.statusRows.get(fixture.workout.id)?.status, "SYNCED");

  const squat = harness.savedAggregates.find((a) => a.exerciseId === "ex-squat");
  const bench = harness.savedAggregates.find((a) => a.exerciseId === "ex-bench");
  const notWorn = harness.savedAggregates.find((a) => a.exerciseId === "ex-not-worn");

  assert.equal(squat.sampleCount, 3);
  assert.equal(squat.maxBpm, 150);
  assert.ok(squat.recoveryDeltaBpm !== null);

  assert.equal(bench.sampleCount, 3);
  assert.equal(bench.maxBpm, 145);

  // The exercise with zero overlapping samples must be represented with
  // null fields, never a false 0 average/max.
  assert.equal(notWorn.sampleCount, 0);
  assert.equal(notWorn.avgBpm, null);
  assert.equal(notWorn.maxBpm, null);
  assert.equal(notWorn.recoveryDeltaBpm, null);
});

test("401 from Fitbit triggers a token refresh and the retried call succeeds", async () => {
  const harness = makeInMemoryHarness();
  let refreshed = false;
  let calls = 0;

  const tokens = {
    async getAccessToken() {
      return "stale-token";
    },
    async refreshAccessToken() {
      refreshed = true;
      return "fresh-token";
    },
  };

  const fitbitClient = {
    async fetchIntradayHr(token: string) {
      calls += 1;
      if (token === "stale-token") {
        return { status: 401, headers: { get: () => null }, async json() { return {}; } };
      }
      return {
        status: 200,
        headers: { get: () => null },
        async json() {
          return { "activities-heart-intraday": { dataset: buildSecondAttemptDataset() } };
        },
      };
    },
  };

  const result = await syncWorkoutHr({
    workout: fixture.workout,
    fitbitClient,
    tokens,
    statusStore: harness.statusStore,
    sampleStore: harness.sampleStore,
    aggregateStore: harness.aggregateStore,
    exerciseWindows: harness.exerciseWindows,
    now: () => "2026-08-01T11:05:00.000Z",
  });

  assert.equal(refreshed, true);
  assert.equal(result.status, "SYNCED");
  assert.equal(calls, 2);
});

test("429 from Fitbit yields PENDING with retryAfter honoring Retry-After header", async () => {
  const harness = makeInMemoryHarness();

  const tokens = {
    async getAccessToken() {
      return "token";
    },
    async refreshAccessToken() {
      return "token";
    },
  };

  const fitbitClient = {
    async fetchIntradayHr() {
      return {
        status: 429,
        headers: { get: (n: string) => (n.toLowerCase() === "retry-after" ? "45" : null) },
        async json() {
          return {};
        },
      };
    },
  };

  const result = await syncWorkoutHr({
    workout: fixture.workout,
    fitbitClient,
    tokens,
    statusStore: harness.statusStore,
    sampleStore: harness.sampleStore,
    aggregateStore: harness.aggregateStore,
    exerciseWindows: harness.exerciseWindows,
    now: () => "2026-08-01T11:05:00.000Z",
  });

  assert.equal(result.status, "PENDING");
  assert.equal(result.retryAfterSeconds, 45);
  assert.equal(harness.statusRows.get(fixture.workout.id)?.status, "PENDING");
});
