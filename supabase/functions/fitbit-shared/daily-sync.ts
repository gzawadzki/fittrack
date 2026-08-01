// Imports sleep, resting heart rate, daily steps, and walk/run activities.
// Same injectable-deps shape as workout-hr-sync.ts so it's testable without
// Deno or a live Fitbit account.

import { classifyUpstreamResponse } from "./retry.ts";
import type { UpstreamResponseLike } from "./retry.ts";

export interface DailyHttpResponse extends UpstreamResponseLike {
  json(): Promise<any>;
}

export interface FitbitDailyClient {
  fetchSleepLog(accessToken: string, date: string): Promise<DailyHttpResponse>;
  fetchRestingHeartRate(
    accessToken: string,
    date: string,
  ): Promise<DailyHttpResponse>;
  fetchDailySteps(accessToken: string, date: string): Promise<DailyHttpResponse>;
  fetchActivitiesList(
    accessToken: string,
    afterDate: string,
  ): Promise<DailyHttpResponse>;
}

export interface TokenProvider {
  getAccessToken(): Promise<string>;
  refreshAccessToken(): Promise<string>;
}

export interface DailyMetricUpsert {
  userId: string;
  date: string;
  sleepMinutes: number | null;
  restingHeartRate: number | null;
  steps: number | null;
  raw: unknown;
}

export interface ActivityUpsert {
  userId: string;
  fitbitLogId: string;
  activityType: string;
  startedAtIso: string;
  endedAtIso: string | null;
  durationMs: number | null;
  distanceKm: number | null;
  calories: number | null;
  steps: number | null;
  raw: unknown;
}

export interface DailyMetricsStore {
  upsert(row: DailyMetricUpsert): Promise<void>;
}

export interface ActivityStore {
  upsertMany(rows: ActivityUpsert[]): Promise<void>;
}

export interface ImportedCounts {
  sleep: number;
  restingHeartRate: number;
  steps: number;
  activities: number;
}

export interface DailySyncResult {
  status: "SYNCED" | "PARTIAL" | "FAILED";
  importedCounts: ImportedCounts;
  retryAfterSeconds: number | null;
}

const WALK_RUN_ACTIVITY_NAMES = new Set([
  "walk",
  "run",
  "running",
  "walking",
  "outdoor walk",
  "outdoor run",
  "treadmill",
]);

export async function syncDailyMetrics(opts: {
  userId: string;
  /** dates to sync, YYYY-MM-DD, e.g. the last N days since the cursor */
  dates: string[];
  activitiesAfterDate: string;
  fitbitClient: FitbitDailyClient;
  tokens: TokenProvider;
  dailyMetricsStore: DailyMetricsStore;
  activityStore: ActivityStore;
}): Promise<DailySyncResult> {
  const counts: ImportedCounts = {
    sleep: 0,
    restingHeartRate: 0,
    steps: 0,
    activities: 0,
  };

  let accessToken = await opts.tokens.getAccessToken();
  let sawFailure = false;

  const callWithRefresh = async <T extends UpstreamResponseLike>(
    fn: (token: string) => Promise<T>,
  ): Promise<{ res: T; retryAfterSeconds: number | null } | null> => {
    let res = await fn(accessToken);
    let decision = classifyUpstreamResponse(res);
    if (decision.kind === "refresh_and_retry") {
      accessToken = await opts.tokens.refreshAccessToken();
      res = await fn(accessToken);
      decision = classifyUpstreamResponse(res);
    }
    if (decision.kind === "wait_and_retry") {
      return { res, retryAfterSeconds: decision.retryAfterSeconds };
    }
    if (decision.kind === "fail" || decision.kind === "refresh_and_retry") {
      sawFailure = true;
      return null;
    }
    return { res, retryAfterSeconds: null };
  };

  let retryAfterSeconds: number | null = null;

  for (const date of opts.dates) {
    const sleepResult = await callWithRefresh((t) =>
      opts.fitbitClient.fetchSleepLog(t, date)
    );
    if (sleepResult?.retryAfterSeconds) {
      retryAfterSeconds = maxRetry(retryAfterSeconds, sleepResult.retryAfterSeconds);
    }
    const sleepBody = sleepResult && !sleepResult.retryAfterSeconds
      ? await sleepResult.res.json()
      : null;

    const rhrResult = await callWithRefresh((t) =>
      opts.fitbitClient.fetchRestingHeartRate(t, date)
    );
    if (rhrResult?.retryAfterSeconds) {
      retryAfterSeconds = maxRetry(retryAfterSeconds, rhrResult.retryAfterSeconds);
    }
    const rhrBody = rhrResult && !rhrResult.retryAfterSeconds
      ? await rhrResult.res.json()
      : null;

    const stepsResult = await callWithRefresh((t) =>
      opts.fitbitClient.fetchDailySteps(t, date)
    );
    if (stepsResult?.retryAfterSeconds) {
      retryAfterSeconds = maxRetry(retryAfterSeconds, stepsResult.retryAfterSeconds);
    }
    const stepsBody = stepsResult && !stepsResult.retryAfterSeconds
      ? await stepsResult.res.json()
      : null;

    // Fitbit returns 0-length arrays / missing keys when there's genuinely
    // no data for the day -- we must not coerce that into a false 0. Only a
    // present numeric value is stored; otherwise the column stays NULL.
    const sleepMinutes = extractSleepMinutes(sleepBody);
    const restingHeartRate = extractRestingHeartRate(rhrBody);
    const steps = extractSteps(stepsBody);

    if (sleepBody || rhrBody || stepsBody) {
      await opts.dailyMetricsStore.upsert({
        userId: opts.userId,
        date,
        sleepMinutes,
        restingHeartRate,
        steps,
        raw: { sleep: sleepBody, rhr: rhrBody, steps: stepsBody },
      });
      if (sleepMinutes !== null) counts.sleep += 1;
      if (restingHeartRate !== null) counts.restingHeartRate += 1;
      if (steps !== null) counts.steps += 1;
    }
  }

  const activitiesResult = await callWithRefresh((t) =>
    opts.fitbitClient.fetchActivitiesList(t, opts.activitiesAfterDate)
  );
  if (activitiesResult?.retryAfterSeconds) {
    retryAfterSeconds = maxRetry(retryAfterSeconds, activitiesResult.retryAfterSeconds);
  } else if (activitiesResult) {
    const body = await activitiesResult.res.json();
    const list = (body.activities ?? []) as any[];
    const walkRun = list.filter((a) =>
      WALK_RUN_ACTIVITY_NAMES.has(String(a.activityName ?? "").toLowerCase())
    );
    if (walkRun.length > 0) {
      await opts.activityStore.upsertMany(
        walkRun.map((a) => ({
          userId: opts.userId,
          fitbitLogId: String(a.logId),
          activityType: String(a.activityName ?? "unknown"),
          startedAtIso: a.startTime,
          endedAtIso: a.startTime && a.duration
            ? new Date(Date.parse(a.startTime) + a.duration).toISOString()
            : null,
          durationMs: a.duration ?? null,
          distanceKm: a.distance ?? null,
          calories: a.calories ?? null,
          steps: a.steps ?? null,
          raw: a,
        })),
      );
    }
    counts.activities = walkRun.length;
  }

  const status: DailySyncResult["status"] = sawFailure
    ? "FAILED"
    : retryAfterSeconds
    ? "PARTIAL"
    : "SYNCED";

  return { status, importedCounts: counts, retryAfterSeconds };
}

function maxRetry(a: number | null, b: number | null): number | null {
  if (a === null) return b;
  if (b === null) return a;
  return Math.max(a, b);
}

function extractSleepMinutes(body: any): number | null {
  const summary = body?.summary;
  if (summary && typeof summary.totalMinutesAsleep === "number") {
    return summary.totalMinutesAsleep;
  }
  return null;
}

function extractRestingHeartRate(body: any): number | null {
  const val = body?.["activities-heart"]?.[0]?.value?.restingHeartRate;
  return typeof val === "number" ? val : null;
}

function extractSteps(body: any): number | null {
  const val = body?.summary?.steps;
  return typeof val === "number" ? val : null;
}
