import test from "node:test";
import assert from "node:assert/strict";
import { syncDailyMetrics } from "../fitbit-shared/daily-sync.ts";

function okResponse(body: unknown) {
  return { status: 200, headers: { get: () => null }, async json() { return body; } };
}

test("missing sleep/RHR for a day is stored as null, not 0", async () => {
  const upserts: any[] = [];
  const result = await syncDailyMetrics({
    userId: "user-1",
    dates: ["2026-08-01"],
    activitiesAfterDate: "2026-08-01",
    tokens: {
      async getAccessToken() { return "tok"; },
      async refreshAccessToken() { return "tok"; },
    },
    fitbitClient: {
      async fetchSleepLog() { return okResponse({ summary: {} }); }, // no totalMinutesAsleep
      async fetchRestingHeartRate() { return okResponse({ "activities-heart": [{ value: {} }] }); },
      async fetchDailySteps() { return okResponse({ summary: { steps: 8342 } }); },
      async fetchActivitiesList() { return okResponse({ activities: [] }); },
    },
    dailyMetricsStore: { async upsert(row) { upserts.push(row); } },
    activityStore: { async upsertMany() {} },
  });

  assert.equal(upserts.length, 1);
  assert.equal(upserts[0].sleepMinutes, null);
  assert.equal(upserts[0].restingHeartRate, null);
  assert.equal(upserts[0].steps, 8342);
  assert.equal(result.importedCounts.sleep, 0);
  assert.equal(result.importedCounts.steps, 1);
  assert.equal(result.status, "SYNCED");
  assert.deepEqual(result.dailyMetrics, [{
    date: "2026-08-01",
    sleepMinutes: null,
    restingHeartRate: null,
    steps: 8342,
    source: "FITBIT",
  }]);
  assert.equal("raw" in result.dailyMetrics[0], false);
});

test("walk/run activities are imported, other activity types are filtered out", async () => {
  const saved: any[] = [];
  const result = await syncDailyMetrics({
    userId: "user-1",
    dates: [],
    activitiesAfterDate: "2026-08-01",
    tokens: {
      async getAccessToken() { return "tok"; },
      async refreshAccessToken() { return "tok"; },
    },
    fitbitClient: {
      async fetchSleepLog() { return okResponse({}); },
      async fetchRestingHeartRate() { return okResponse({}); },
      async fetchDailySteps() { return okResponse({}); },
      async fetchActivitiesList() {
        return okResponse({
          activities: [
            { logId: 1, activityName: "Run", startTime: "2026-08-01T06:00:00.000Z", duration: 1800000, distance: 5, calories: 400, steps: 6000 },
            { logId: 2, activityName: "Weights", startTime: "2026-08-01T18:00:00.000Z", duration: 3600000 },
            { logId: 3, activityName: "Walk", startTime: "2026-08-01T12:00:00.000Z", duration: 900000 },
          ],
        });
      },
    },
    dailyMetricsStore: { async upsert() {} },
    activityStore: { async upsertMany(rows) { saved.push(...rows); } },
  });

  assert.equal(saved.length, 2);
  assert.deepEqual(saved.map((r) => r.fitbitLogId).sort(), ["1", "3"]);
  assert.equal(result.importedCounts.activities, 2);
  assert.equal(result.cardioActivities.length, 2);
  assert.deepEqual(result.cardioActivities[0], {
    sourceId: "1",
    kind: "Run",
    startedAt: "2026-08-01T06:00:00.000Z",
    endedAt: "2026-08-01T06:30:00.000Z",
    durationSeconds: 1800,
    distanceMeters: 5000,
    steps: 6000,
    averageHeartRate: null,
  });
  assert.equal("raw" in result.cardioActivities[0], false);
});

test("429 during daily sync surfaces PARTIAL status with retryAfter", async () => {
  const result = await syncDailyMetrics({
    userId: "user-1",
    dates: ["2026-08-01"],
    activitiesAfterDate: "2026-08-01",
    tokens: {
      async getAccessToken() { return "tok"; },
      async refreshAccessToken() { return "tok"; },
    },
    fitbitClient: {
      async fetchSleepLog() {
        return { status: 429, headers: { get: (n: string) => n.toLowerCase() === "retry-after" ? "30" : null }, async json() { return {}; } };
      },
      async fetchRestingHeartRate() { return okResponse({}); },
      async fetchDailySteps() { return okResponse({}); },
      async fetchActivitiesList() { return okResponse({ activities: [] }); },
    },
    dailyMetricsStore: { async upsert() {} },
    activityStore: { async upsertMany() {} },
  });

  assert.equal(result.status, "PARTIAL");
  assert.equal(result.retryAfterSeconds, 30);
});
