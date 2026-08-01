// POST /fitbit-sync
// Input:  { "mode": "DAILY_METRICS" | "WORKOUT_HR" | "RETENTION_CLEANUP", "workoutSessionId"?: "uuid" }
// Output: { "status": "SYNCED"|"PENDING"|"PARTIAL"|"FAILED", "importedCounts": {...}, "retryAfter": number|null }
//
// Requires a Supabase JWT (Authorization: Bearer <token>).
//
// mode=DAILY_METRICS  -> imports sleep, resting HR, steps (last 7 days) and
//                        recent walk/run activities.
// mode=WORKOUT_HR      -> requires workoutSessionId; fetches 1s HR for
//                        [startedAt-5min, endedAt+5min] (<=1 calendar day),
//                        matches samples to exercises, computes aggregates,
//                        flips fitbit_workout_hr_sync.status PENDING->SYNCED.
// mode=RETENTION_CLEANUP -> purges raw HR samples older than 90 days
//                        (aggregates are never purged). Intended to be
//                        invoked by a scheduled trigger (pg_cron / external
//                        scheduler hitting this function), not by the app.

import { ERR, errorResponse, FitbitFunctionError } from "../fitbit-shared/errors.ts";
import {
  getAuthenticatedUserId,
  getServiceRoleClient,
} from "../fitbit-shared/supabase-client.ts";
import { createSupabaseTokenProvider } from "../fitbit-shared/token-provider.ts";
import { syncWorkoutHr } from "../fitbit-shared/workout-hr-sync.ts";
import { syncDailyMetrics } from "../fitbit-shared/daily-sync.ts";
import {
  fetchActivitiesList,
  fetchDailySteps,
  fetchIntradayHeartRate,
  fetchRestingHeartRate,
  fetchSleepLog,
} from "../fitbit-shared/fitbit-api.ts";

// @ts-ignore
Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return errorResponse(
      new FitbitFunctionError({
        code: ERR.BAD_REQUEST,
        message: "Method not allowed",
        httpStatus: 405,
      }),
    );
  }

  try {
    const userId = await getAuthenticatedUserId(req);
    if (!userId) {
      return errorResponse(
        new FitbitFunctionError({
          code: ERR.UNAUTHORIZED,
          message: "Sign in required",
          httpStatus: 401,
        }),
      );
    }

    const body = await req.json().catch(() => ({}));
    const mode = body?.mode;
    const workoutSessionId = body?.workoutSessionId ?? null;

    // @ts-ignore
    const clientId = Deno.env.get("FITBIT_CLIENT_ID");
    // @ts-ignore
    const clientSecret = Deno.env.get("FITBIT_CLIENT_SECRET");
    // @ts-ignore
    const encKey = Deno.env.get("FITBIT_TOKEN_ENC_KEY");
    if (!clientId || !clientSecret || !encKey) {
      throw new Error(
        "Missing FITBIT_CLIENT_ID/FITBIT_CLIENT_SECRET/FITBIT_TOKEN_ENC_KEY secrets",
      );
    }

    const supabase = getServiceRoleClient();

    const tokensTable = {
      async getByUser(uid: string) {
        const { data, error } = await supabase
          .from("fitbit_tokens")
          .select("*")
          .eq("user_id", uid)
          .is("deleted_at", null)
          .maybeSingle();
        if (error) throw new Error(error.message);
        if (!data) return null;
        return {
          accessTokenCiphertext: data.access_token_ciphertext,
          accessTokenIv: data.access_token_iv,
          refreshTokenCiphertext: data.refresh_token_ciphertext,
          refreshTokenIv: data.refresh_token_iv,
          accessTokenExpiresAt: data.access_token_expires_at,
        };
      },
      async save(row: {
        userId: string;
        accessTokenCiphertext: string;
        accessTokenIv: string;
        refreshTokenCiphertext: string;
        refreshTokenIv: string;
        accessTokenExpiresAt: string;
      }) {
        const { error } = await supabase.from("fitbit_tokens").update({
          access_token_ciphertext: row.accessTokenCiphertext,
          access_token_iv: row.accessTokenIv,
          refresh_token_ciphertext: row.refreshTokenCiphertext,
          refresh_token_iv: row.refreshTokenIv,
          access_token_expires_at: row.accessTokenExpiresAt,
          updated_at: new Date().toISOString(),
        }).eq("user_id", row.userId);
        if (error) throw new Error(error.message);
      },
    };

    const tokens = createSupabaseTokenProvider({
      userId,
      table: tokensTable,
      oauthConfig: { clientId, clientSecret },
      encryptionKey: encKey,
    });

    if (mode === "DAILY_METRICS") {
      const dates = lastNDates(7);
      const result = await syncDailyMetrics({
        userId,
        dates,
        activitiesAfterDate: dates[0],
        tokens,
        fitbitClient: {
          fetchSleepLog: (t, d) => fetchSleepLog(t, d),
          fetchRestingHeartRate: (t, d) => fetchRestingHeartRate(t, d),
          fetchDailySteps: (t, d) => fetchDailySteps(t, d),
          fetchActivitiesList: (t, after) => fetchActivitiesList(t, after),
        },
        dailyMetricsStore: {
          async upsert(row) {
            const { error } = await supabase.from("fitbit_daily_metrics").upsert(
              {
                user_id: row.userId,
                metric_date: row.date,
                sleep_minutes: row.sleepMinutes,
                resting_heart_rate: row.restingHeartRate,
                steps: row.steps,
                raw: row.raw,
                updated_at: new Date().toISOString(),
              },
              { onConflict: "user_id,metric_date" },
            );
            if (error) throw new Error(error.message);
          },
        },
        activityStore: {
          async upsertMany(rows) {
            if (rows.length === 0) return;
            const { error } = await supabase.from("fitbit_activities").upsert(
              rows.map((r) => ({
                user_id: r.userId,
                fitbit_log_id: r.fitbitLogId,
                activity_type: r.activityType,
                started_at: r.startedAtIso,
                ended_at: r.endedAtIso,
                duration_ms: r.durationMs,
                distance_km: r.distanceKm,
                calories: r.calories,
                steps: r.steps,
                raw: r.raw,
                updated_at: new Date().toISOString(),
              })),
              { onConflict: "user_id,fitbit_log_id" },
            );
            if (error) throw new Error(error.message);
          },
        },
      });

      return jsonResponse({
        status: result.status,
        importedCounts: result.importedCounts,
        retryAfter: result.retryAfterSeconds,
        data: {
          dailyMetrics: result.dailyMetrics,
          cardioActivities: result.cardioActivities,
          heartRateSamples: [],
          exerciseHeartRateSummaries: [],
        },
      });
    }

    if (mode === "WORKOUT_HR") {
      if (!workoutSessionId) {
        return errorResponse(
          new FitbitFunctionError({
            code: ERR.BAD_REQUEST,
            message: "workoutSessionId is required for WORKOUT_HR mode",
            httpStatus: 400,
          }),
        );
      }

      // NOTE: workout_sessions is owned by M5's schema; this is a read-only
      // reference by id, no FK constraint from this module's migration. See
      // fitbit-HANDOFF.md "Integration point to confirm with M5".
      const { data: workoutRow, error: workoutError } = await supabase
        .from("workout_sessions")
        .select("id, user_id, started_at, ended_at")
        .eq("id", workoutSessionId)
        .eq("user_id", userId)
        .maybeSingle();
      if (workoutError) throw new Error(workoutError.message);
      if (!workoutRow || !workoutRow.ended_at) {
        return errorResponse(
          new FitbitFunctionError({
            code: ERR.BAD_REQUEST,
            message: "Workout not found or not yet finished",
            httpStatus: 400,
          }),
        );
      }

      const result = await syncWorkoutHr({
        workout: {
          id: workoutRow.id,
          userId: workoutRow.user_id,
          startedAtIso: workoutRow.started_at,
          endedAtIso: workoutRow.ended_at,
        },
        tokens,
        fitbitClient: {
          fetchIntradayHr: (t, date, start, end) =>
            fetchIntradayHeartRate(t, date, start, end),
        },
        statusStore: {
          async get(id) {
            const { data, error } = await supabase
              .from("fitbit_workout_hr_sync")
              .select("*")
              .eq("workout_session_id", id)
              .maybeSingle();
            if (error) throw new Error(error.message);
            if (!data) return null;
            return {
              workoutSessionId: data.workout_session_id,
              userId: data.user_id,
              windowStart: data.window_start,
              windowEnd: data.window_end,
              status: data.status,
              attemptCount: data.attempt_count,
              lastAttemptAt: data.last_attempt_at,
              nextRetryAt: data.next_retry_at,
              failureReason: data.failure_reason,
            };
          },
          async save(record) {
            const { error } = await supabase.from("fitbit_workout_hr_sync").upsert(
              {
                workout_session_id: record.workoutSessionId,
                user_id: record.userId,
                window_start: record.windowStart,
                window_end: record.windowEnd,
                status: record.status,
                attempt_count: record.attemptCount,
                last_attempt_at: record.lastAttemptAt,
                next_retry_at: record.nextRetryAt,
                failure_reason: record.failureReason,
                updated_at: new Date().toISOString(),
              },
              { onConflict: "workout_session_id" },
            );
            if (error) throw new Error(error.message);
          },
        },
        sampleStore: {
          async save(workoutId, uid, samples) {
            if (samples.length === 0) return;
            const { error } = await supabase.from("fitbit_hr_samples").upsert(
              samples.map((s) => ({
                workout_session_id: workoutId,
                user_id: uid,
                sample_ts: s.ts,
                bpm: s.bpm,
              })),
              { onConflict: "workout_session_id,sample_ts" },
            );
            if (error) throw new Error(error.message);
          },
        },
        aggregateStore: {
          async save(workoutId, uid, aggregates) {
            if (aggregates.length === 0) return;
            const { error } = await supabase.from("fitbit_hr_exercise_aggregates")
              .upsert(
                aggregates.map((a) => ({
                  workout_session_id: workoutId,
                  exercise_id: a.exerciseId,
                  user_id: uid,
                  avg_bpm: a.avgBpm,
                  max_bpm: a.maxBpm,
                  recovery_delta_bpm: a.recoveryDeltaBpm,
                  sample_count: a.sampleCount,
                  computed_at: new Date().toISOString(),
                  updated_at: new Date().toISOString(),
                })),
                { onConflict: "workout_session_id,exercise_id" },
              );
            if (error) throw new Error(error.message);
          },
        },
        exerciseWindows: {
          async getExerciseWindows(workoutId) {
            const { data, error } = await supabase
              .from("exercise_sessions")
              .select("id, started_at, ended_at, position")
              .eq("workout_session_id", workoutId)
              .is("deleted_at", null)
              .not("started_at", "is", null)
              .not("ended_at", "is", null)
              .order("position", { ascending: true });
            if (error) throw new Error(error.message);
            return (data ?? []).map((row) => ({
              // The aggregate key is the exercise-session UUID, not the
              // reusable exercise/catalog UUID.
              exerciseId: row.id,
              startIso: row.started_at,
              endIso: row.ended_at,
            }));
          },
        },
      });

      return jsonResponse({
        status: result.status,
        importedCounts: { heartRateSamples: result.importedSampleCount },
        retryAfter: result.retryAfterSeconds,
        data: {
          dailyMetrics: [],
          cardioActivities: [],
          heartRateSamples: result.samples.map((sample) => ({
            workoutSessionId,
            sampledAt: sample.ts,
            bpm: sample.bpm,
          })),
          exerciseHeartRateSummaries: result.aggregates.map((aggregate) => ({
            exerciseSessionId: aggregate.exerciseId,
            averageBpm: aggregate.avgBpm,
            maximumBpm: aggregate.maxBpm,
            recoveryDeltaBpm: aggregate.recoveryDeltaBpm,
            sampleCount: aggregate.sampleCount,
          })),
        },
      });
    }

    if (mode === "RETENTION_CLEANUP") {
      const { data, error } = await supabase.rpc(
        "fitbit_purge_expired_hr_samples",
      );
      if (error) throw new Error(error.message);
      return jsonResponse({
        status: "SYNCED",
        importedCounts: { deletedHrSamples: data ?? 0 },
        retryAfter: null,
      });
    }

    return errorResponse(
      new FitbitFunctionError({
        code: ERR.BAD_REQUEST,
        message: "Unknown mode",
        httpStatus: 400,
      }),
    );
  } catch (err) {
    if (err instanceof FitbitFunctionError) {
      return errorResponse(err);
    }
    console.error("fitbit-sync failed", (err as Error).message);
    return errorResponse(
      new FitbitFunctionError({
        code: ERR.UPSTREAM_ERROR,
        message: "Fitbit sync failed",
        httpStatus: 500,
        retryable: true,
        retryAfterSeconds: 60,
      }),
    );
  }
});

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function lastNDates(n: number): string[] {
  const dates: string[] = [];
  const today = new Date();
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(today);
    d.setUTCDate(d.getUTCDate() - i);
    dates.push(d.toISOString().slice(0, 10));
  }
  return dates;
}
