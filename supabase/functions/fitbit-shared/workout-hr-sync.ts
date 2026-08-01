// Orchestrates the post-workout HR import pipeline:
//   compute window -> fetch intraday HR (per-day, handling 401/429/empty) ->
//   persist raw samples -> match against exercise windows -> persist
//   aggregates -> flip fitbitSyncStatus PENDING -> SYNCED (or FAILED).
//
// Everything upstream (Fitbit HTTP, Postgres) is injected as small
// interfaces so this file has zero Deno/fetch/DB dependencies and can be
// exercised in plain Node with fixtures (see fitbit-tests/).

import { computeHrWindow, matchAndAggregate, splitWindowByDay } from "./hr-window.ts";
import type { ExerciseHrAggregate, ExerciseTimeRange, HrSample } from "./hr-window.ts";
import { classifyUpstreamResponse, decideEmptyHrWindow } from "./retry.ts";
import type { UpstreamResponseLike } from "./retry.ts";

export interface WorkoutInfo {
  id: string;
  userId: string;
  startedAtIso: string;
  endedAtIso: string;
}

export interface IntradayHrResponse extends UpstreamResponseLike {
  json(): Promise<{
    "activities-heart-intraday"?: {
      dataset: Array<{ time: string; value: number }>;
    };
  }>;
}

export interface FitbitHrClient {
  fetchIntradayHr(
    accessToken: string,
    dateYyyyMmDd: string,
    startHHmm: string,
    endHHmm: string,
  ): Promise<IntradayHrResponse>;
}

export interface TokenProvider {
  getAccessToken(): Promise<string>;
  /** Refreshes and persists a new access token, returning it. */
  refreshAccessToken(): Promise<string>;
}

export type SyncStatus = "PENDING" | "SYNCED" | "FAILED";

export interface SyncStatusRecord {
  workoutSessionId: string;
  userId: string;
  windowStart: string;
  windowEnd: string;
  status: SyncStatus;
  attemptCount: number;
  lastAttemptAt: string | null;
  nextRetryAt: string | null;
  failureReason: string | null;
}

export interface SyncStatusStore {
  get(workoutSessionId: string): Promise<SyncStatusRecord | null>;
  save(record: SyncStatusRecord): Promise<void>;
}

export interface SampleStore {
  save(workoutSessionId: string, userId: string, samples: HrSample[]): Promise<void>;
}

export interface AggregateStore {
  save(
    workoutSessionId: string,
    userId: string,
    aggregates: ExerciseHrAggregate[],
  ): Promise<void>;
}

export interface ExerciseWindowSource {
  getExerciseWindows(workoutSessionId: string): Promise<ExerciseTimeRange[]>;
}

export interface WorkoutHrSyncResult {
  status: SyncStatus;
  retryAfterSeconds: number | null;
  importedSampleCount: number;
}

export interface WorkoutHrSyncDeps {
  workout: WorkoutInfo;
  fitbitClient: FitbitHrClient;
  tokens: TokenProvider;
  statusStore: SyncStatusStore;
  sampleStore: SampleStore;
  aggregateStore: AggregateStore;
  exerciseWindows: ExerciseWindowSource;
  now?: () => string;
}

export async function syncWorkoutHr(
  deps: WorkoutHrSyncDeps,
): Promise<WorkoutHrSyncResult> {
  const now = deps.now ?? (() => new Date().toISOString());
  const { workout } = deps;
  const window = computeHrWindow(workout.startedAtIso, workout.endedAtIso);

  const existing = await deps.statusStore.get(workout.id);
  const attemptCount = (existing?.attemptCount ?? 0) + 1;

  const baseRecord: SyncStatusRecord = {
    workoutSessionId: workout.id,
    userId: workout.userId,
    windowStart: window.startIso,
    windowEnd: window.endIso,
    status: "PENDING",
    attemptCount,
    lastAttemptAt: now(),
    nextRetryAt: null,
    failureReason: null,
  };

  const segments = splitWindowByDay(window.startIso, window.endIso);
  let accessToken = await deps.tokens.getAccessToken();
  const collected: HrSample[] = [];

  for (const segment of segments) {
    let res = await deps.fitbitClient.fetchIntradayHr(
      accessToken,
      segment.dateYyyyMmDd,
      segment.startHHmm,
      segment.endHHmm,
    );

    let decision = classifyUpstreamResponse(res);

    if (decision.kind === "refresh_and_retry") {
      accessToken = await deps.tokens.refreshAccessToken();
      res = await deps.fitbitClient.fetchIntradayHr(
        accessToken,
        segment.dateYyyyMmDd,
        segment.startHHmm,
        segment.endHHmm,
      );
      decision = classifyUpstreamResponse(res);
    }

    if (decision.kind === "refresh_and_retry") {
      // Refresh didn't fix it (revoked/invalid grant): fail, don't loop.
      const failed: SyncStatusRecord = {
        ...baseRecord,
        status: "FAILED",
        failureReason: "fitbit_reauth_required",
      };
      await deps.statusStore.save(failed);
      return { status: "FAILED", retryAfterSeconds: null, importedSampleCount: 0 };
    }

    if (decision.kind === "wait_and_retry") {
      const pending: SyncStatusRecord = {
        ...baseRecord,
        status: "PENDING",
        nextRetryAt: new Date(
          Date.parse(now()) + decision.retryAfterSeconds * 1000,
        ).toISOString(),
      };
      await deps.statusStore.save(pending);
      return {
        status: "PENDING",
        retryAfterSeconds: decision.retryAfterSeconds,
        importedSampleCount: 0,
      };
    }

    if (decision.kind === "fail") {
      const failed: SyncStatusRecord = {
        ...baseRecord,
        status: "FAILED",
        failureReason: decision.reason,
      };
      await deps.statusStore.save(failed);
      return { status: "FAILED", retryAfterSeconds: null, importedSampleCount: 0 };
    }

    const body = await res.json();
    const dataset = body["activities-heart-intraday"]?.dataset ?? [];
    for (const point of dataset) {
      collected.push({
        ts: `${segment.dateYyyyMmDd}T${point.time}.000Z`,
        bpm: point.value,
      });
    }
  }

  if (collected.length === 0) {
    const emptyDecision = decideEmptyHrWindow({
      windowEndIso: window.endIso,
      nowIso: now(),
      attemptCount: attemptCount - 1,
    });

    if (emptyDecision.kind === "defer_pending") {
      const pending: SyncStatusRecord = {
        ...baseRecord,
        status: "PENDING",
        nextRetryAt: emptyDecision.nextRetryAt,
      };
      await deps.statusStore.save(pending);
      const retryAfterSeconds = Math.max(
        1,
        Math.round(
          (Date.parse(emptyDecision.nextRetryAt) - Date.parse(now())) / 1000,
        ),
      );
      return { status: "PENDING", retryAfterSeconds, importedSampleCount: 0 };
    }
    // else "ok": genuinely no data (e.g. device not worn) -- fall through
    // and persist empty/null aggregates, marked SYNCED so we don't retry
    // forever.
  } else {
    await deps.sampleStore.save(workout.id, workout.userId, collected);
  }

  const exerciseWindows = await deps.exerciseWindows.getExerciseWindows(
    workout.id,
  );
  const aggregates = matchAndAggregate(collected, exerciseWindows);
  await deps.aggregateStore.save(workout.id, workout.userId, aggregates);

  const synced: SyncStatusRecord = {
    ...baseRecord,
    status: "SYNCED",
    nextRetryAt: null,
  };
  await deps.statusStore.save(synced);

  return {
    status: "SYNCED",
    retryAfterSeconds: null,
    importedSampleCount: collected.length,
  };
}
