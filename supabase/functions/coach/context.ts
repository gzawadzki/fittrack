// Builds the minimal grounding context passed to the model: profile summary, Fitbit
// AGGREGATES (never raw HR samples), a short recent-workout summary, and the deterministic
// pre-computed recommendation. The model receives the recommendation as a fact to explain,
// not as something to compute itself (section 5 / M7 brief: model is never the source of
// the numeric recommendation).
//
// Schema: see supabase/migrations (user_profiles, fitbit_daily_metrics,
// fitbit_hr_exercise_aggregates, workout_sessions, exercise_sessions, set_logs, plan_exercises).
// Query errors are logged and degrade to neutral defaults so the coach still answers,
// but they are never silently swallowed.

import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import {
  ProgressionEngine,
  ReadinessCalculator,
  type ProgressionInput,
  type ProgressionRecommendation,
  type ReadinessLevel,
  type RecoveryDay,
  type SetPerformance,
} from "./progression.ts";
import type { CoachRequest } from "./types.ts";

export interface ProfileSummary {
  goal: string;
  experience: string;
  equipment: string[];
  schedule: string;
  constraints: string;
  preferences: string;
}

export interface FitbitAggregateSummary {
  restingHeartRate: number | null;
  sleepMinutesLastNight: number | null;
  stepsLastNight: number | null;
  avgHrDuringLastWorkout: number | null;
  maxHrDuringLastWorkout: number | null;
  hrRecoveryDeltaBpm: number | null;
}

export interface RecentWorkoutSummary {
  sessionId: string;
  status: string;
  startedAt: string | null;
  note: string;
  exerciseTargetId: string | null;
  exerciseName: string | null;
}

export interface CoachContext {
  profile: ProfileSummary | null;
  fitbit: FitbitAggregateSummary | null;
  recentWorkouts: RecentWorkoutSummary[];
  readiness: ReadinessLevel;
  deterministic: ProgressionRecommendation | null;
  targetId: string | null;
  /** Raw, untrusted free-text from the athlete/session (e.g. workout note) - never treated as instructions. */
  untrustedNotes: string[];
}

// deno-lint-ignore no-explicit-any
type Row = Record<string, any>;
type QueryResult<T> = { data: T | null; error: { message?: string } | null };

async function query<T>(label: string, q: PromiseLike<QueryResult<T>>): Promise<T | null> {
  try {
    const { data, error } = await q;
    if (error) {
      console.error(`coach context: ${label} failed`, error.message ?? String(error));
      return null;
    }
    return data ?? null;
  } catch (e) {
    console.error(`coach context: ${label} threw`, String(e));
    return null;
  }
}

export async function buildCoachContext(
  supabase: SupabaseClient,
  userId: string,
  req: CoachRequest,
): Promise<CoachContext> {
  const profileRow = await query<Row>(
    "user_profiles",
    supabase
      .from("user_profiles")
      .select("goal, experience, equipment, schedule, constraints, preferences")
      .eq("user_id", userId)
      .is("deleted_at", null)
      .maybeSingle(),
  );
  const profile: ProfileSummary | null = profileRow
    ? {
      goal: profileRow.goal ?? "",
      experience: profileRow.experience ?? "",
      equipment: Array.isArray(profileRow.equipment) ? profileRow.equipment : [],
      schedule: profileRow.schedule ?? "",
      constraints: profileRow.constraints ?? "",
      preferences: profileRow.preferences ?? "",
    }
    : null;

  // Newest first: [0] is today's/last night's metrics, the rest is the readiness baseline.
  const dailyMetrics = (await query<Row[]>(
    "fitbit_daily_metrics",
    supabase
      .from("fitbit_daily_metrics")
      .select("metric_date, sleep_minutes, resting_heart_rate, steps")
      .eq("user_id", userId)
      .is("deleted_at", null)
      .order("metric_date", { ascending: false })
      .limit(15),
  )) ?? [];
  const latestMetrics = dailyMetrics[0] ?? null;

  const sessionColumns = "id, status, started_at, note";
  const sessionRow = await query<Row>(
    "workout_sessions (current)",
    req.workoutSessionId
      ? supabase.from("workout_sessions").select(sessionColumns).eq("id", req.workoutSessionId).eq("user_id", userId).maybeSingle()
      : supabase.from("workout_sessions").select(sessionColumns).eq("user_id", userId).is("deleted_at", null)
        .order("started_at", { ascending: false }).limit(1).maybeSingle(),
  );

  const recentSessions = (await query<Row[]>(
    "workout_sessions (recent)",
    supabase.from("workout_sessions").select(sessionColumns).eq("user_id", userId).is("deleted_at", null)
      .order("started_at", { ascending: false }).limit(5),
  )) ?? [];

  // Primary exercise of the session = first exercise_session by position. Its plan_exercise_id
  // is the suggestion target (suggestion-decision resolves LOAD/VOLUME/SWAP against plan_exercises).
  const exerciseSessions = sessionRow
    ? (await query<Row[]>(
      "exercise_sessions",
      supabase
        .from("exercise_sessions")
        .select("id, plan_exercise_id, exercise_id, exercise_name_snapshot, position")
        .eq("user_id", userId)
        .eq("workout_session_id", sessionRow.id)
        .is("deleted_at", null)
        .order("position", { ascending: true }),
    )) ?? []
    : [];
  const primaryExercise = exerciseSessions[0] ?? null;

  const sets = primaryExercise
    ? (await query<Row[]>(
      "set_logs",
      supabase
        .from("set_logs")
        .select("set_type, target_rep_min, target_rep_max, target_rir, target_weight_kg, actual_reps, actual_weight_kg, actual_rir, pain, technique_issue, concerning_symptom, completed_at")
        .eq("user_id", userId)
        .eq("exercise_session_id", primaryExercise.id)
        .is("deleted_at", null)
        .not("completed_at", "is", null)
        .order("position", { ascending: true }),
    )) ?? []
    : [];

  const planExercise = primaryExercise
    ? await query<Row>(
      "plan_exercises",
      supabase.from("plan_exercises").select("weight_increment_kg").eq("id", primaryExercise.plan_exercise_id).eq("user_id", userId).maybeSingle(),
    )
    : null;

  const hrAggregates = sessionRow
    ? (await query<Row[]>(
      "fitbit_hr_exercise_aggregates",
      supabase
        .from("fitbit_hr_exercise_aggregates")
        .select("exercise_id, avg_bpm, max_bpm, recovery_delta_bpm")
        .eq("user_id", userId)
        .eq("workout_session_id", sessionRow.id)
        .is("deleted_at", null),
    )) ?? []
    : [];
  const avgs = hrAggregates.map((r) => r.avg_bpm).filter((v): v is number => typeof v === "number");
  const maxes = hrAggregates.map((r) => r.max_bpm).filter((v): v is number => typeof v === "number");
  const primaryHr = hrAggregates.find((r) => r.exercise_id === primaryExercise?.exercise_id) ?? null;

  const fitbit: FitbitAggregateSummary | null = latestMetrics || hrAggregates.length > 0
    ? {
      restingHeartRate: latestMetrics?.resting_heart_rate ?? null,
      sleepMinutesLastNight: latestMetrics?.sleep_minutes ?? null,
      stepsLastNight: latestMetrics?.steps ?? null,
      avgHrDuringLastWorkout: avgs.length ? Math.round(avgs.reduce((a, b) => a + b, 0) / avgs.length) : null,
      maxHrDuringLastWorkout: maxes.length ? Math.max(...maxes) : null,
      hrRecoveryDeltaBpm: primaryHr?.recovery_delta_bpm ?? null,
    }
    : null;

  const history: RecoveryDay[] = dailyMetrics.slice(1).map((r) => ({
    sleepMinutes: r.sleep_minutes ?? null,
    restingHeartRate: r.resting_heart_rate ?? null,
  }));
  const today: RecoveryDay = {
    sleepMinutes: fitbit?.sleepMinutesLastNight ?? null,
    restingHeartRate: fitbit?.restingHeartRate ?? null,
  };
  const readiness = ReadinessCalculator.calculate(today, history);

  let deterministic: ProgressionRecommendation | null = null;
  let targetId: string | null = null;

  if (primaryExercise && sets.length > 0) {
    targetId = primaryExercise.plan_exercise_id ?? null;
    const working = sets.find((s) => s.set_type === "WORKING") ?? sets[0];
    const setPerformances: SetPerformance[] = sets.map((s) => ({
      reps: s.actual_reps ?? 0,
      rir: s.actual_rir ?? null,
      isWarmup: s.set_type === "WARMUP",
      pain: !!s.pain,
      techniqueIssue: !!s.technique_issue,
      concerningSymptom: !!s.concerning_symptom,
    }));

    const input: ProgressionInput = {
      currentWeightKg: Number(working.actual_weight_kg ?? working.target_weight_kg ?? 0),
      incrementKg: Number(planExercise?.weight_increment_kg ?? 2.5),
      repMin: working.target_rep_min ?? 8,
      repMax: working.target_rep_max ?? 12,
      targetRir: working.target_rir ?? null,
      sets: setPerformances,
      consecutiveSuccessfulSessions: 0,
      consecutiveMissedSessions: 0,
      readiness,
    };
    deterministic = ProgressionEngine.recommend(input);
  }

  const recentWorkouts: RecentWorkoutSummary[] = recentSessions.map((s) => ({
    sessionId: s.id,
    status: s.status ?? "UNKNOWN",
    startedAt: s.started_at ?? null,
    note: typeof s.note === "string" ? s.note : "",
    exerciseTargetId: targetId,
    exerciseName: primaryExercise?.exercise_name_snapshot ?? null,
  }));

  const untrustedNotes = recentWorkouts.map((w) => w.note).filter((n) => n && n.trim() !== "");
  if (req.message) untrustedNotes.push(req.message);

  return { profile, fitbit, recentWorkouts, readiness, deterministic, targetId, untrustedNotes };
}
