// Builds the minimal grounding context passed to the model: profile summary, Fitbit
// AGGREGATES (never raw HR samples), a short recent-workout summary, and the deterministic
// pre-computed recommendation. The model receives the recommendation as a fact to explain,
// not as something to compute itself (section 5 / M7 brief: model is never the source of
// the numeric recommendation).
//
// NOTE on table names: supabase/migrations (owned by M5) had not been published at the time
// this function was written. Table/column names below are best-effort guesses consistent
// with the domain types in IMPLEMENTATION_PLAN.md section 4 (profiles, workout_sessions,
// workout_sets, fitbit_daily_aggregates). All reads are defensive: a missing table/column
// degrades to a neutral default rather than throwing, so the coach endpoint degrades
// gracefully instead of 500ing while M5's schema stabilizes. Once the real schema lands,
// update the query bodies in this file only - the shape returned by buildCoachContext()
// (CoachContext) is what the rest of this module depends on and should not need to change.

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

async function safeSingle<T>(promise: Promise<{ data: T | null; error: unknown }>): Promise<T | null> {
  try {
    const { data, error } = await promise;
    if (error) return null;
    return data ?? null;
  } catch {
    return null;
  }
}

async function safeList<T>(promise: Promise<{ data: T[] | null; error: unknown }>): Promise<T[]> {
  try {
    const { data, error } = await promise;
    if (error || !data) return [];
    return data;
  } catch {
    return [];
  }
}

export async function buildCoachContext(
  supabase: SupabaseClient,
  userId: string,
  req: CoachRequest,
): Promise<CoachContext> {
  // deno-lint-ignore no-explicit-any
  const profileRow = await safeSingle<any>(
    supabase.from("profiles").select("goal, experience, equipment, schedule, constraints, preferences").eq("id", userId).maybeSingle(),
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

  // deno-lint-ignore no-explicit-any
  const fitbitRow = await safeSingle<any>(
    supabase
      .from("fitbit_daily_aggregates")
      .select("resting_heart_rate, sleep_minutes, steps, avg_hr_workout, max_hr_workout, hr_recovery_delta_bpm")
      .eq("user_id", userId)
      .order("day", { ascending: false })
      .limit(1)
      .maybeSingle(),
  );
  const fitbit: FitbitAggregateSummary | null = fitbitRow
    ? {
      restingHeartRate: fitbitRow.resting_heart_rate ?? null,
      sleepMinutesLastNight: fitbitRow.sleep_minutes ?? null,
      stepsLastNight: fitbitRow.steps ?? null,
      avgHrDuringLastWorkout: fitbitRow.avg_hr_workout ?? null,
      maxHrDuringLastWorkout: fitbitRow.max_hr_workout ?? null,
      hrRecoveryDeltaBpm: fitbitRow.hr_recovery_delta_bpm ?? null,
    }
    : null;

  // deno-lint-ignore no-explicit-any
  const recoveryHistory = await safeList<any>(
    supabase
      .from("fitbit_daily_aggregates")
      .select("sleep_minutes, resting_heart_rate")
      .eq("user_id", userId)
      .order("day", { ascending: false })
      .limit(15),
  );
  const history: RecoveryDay[] = recoveryHistory.slice(1).map((r) => ({
    sleepMinutes: r.sleep_minutes ?? null,
    restingHeartRate: r.resting_heart_rate ?? null,
  }));
  const today: RecoveryDay = {
    sleepMinutes: fitbit?.sleepMinutesLastNight ?? null,
    restingHeartRate: fitbit?.restingHeartRate ?? null,
  };
  const readiness = ReadinessCalculator.calculate(today, history);

  // deno-lint-ignore no-explicit-any
  let sessionRow: any = null;
  if (req.workoutSessionId) {
    sessionRow = await safeSingle(
      supabase.from("workout_sessions").select("id, status, started_at, note").eq("id", req.workoutSessionId).eq("user_id", userId).maybeSingle(),
    );
  } else {
    sessionRow = await safeSingle(
      supabase.from("workout_sessions").select("id, status, started_at, note").eq("user_id", userId).order("started_at", { ascending: false }).limit(1).maybeSingle(),
    );
  }

  // deno-lint-ignore no-explicit-any
  const recentSessions = await safeList<any>(
    supabase.from("workout_sessions").select("id, status, started_at, note").eq("user_id", userId).order("started_at", { ascending: false }).limit(5),
  );

  // deno-lint-ignore no-explicit-any
  const sets = sessionRow
    ? await safeList<any>(
      supabase
        .from("workout_sets")
        .select("target_id, exercise_name, reps, rir, is_warmup, pain, technique_issue, concerning_symptom, weight_kg, rep_min, rep_max, target_rir, increment_kg")
        .eq("session_id", sessionRow.id),
    )
    : [];

  let deterministic: ProgressionRecommendation | null = null;
  let targetId: string | null = null;

  if (sets.length > 0) {
    const primary = sets[0];
    targetId = primary.target_id ?? null;
    const setPerformances: SetPerformance[] = sets
      .filter((s) => s.target_id === targetId)
      .map((s) => ({
        reps: s.reps ?? 0,
        rir: s.rir ?? null,
        isWarmup: !!s.is_warmup,
        pain: !!s.pain,
        techniqueIssue: !!s.technique_issue,
        concerningSymptom: !!s.concerning_symptom,
      }));

    const input: ProgressionInput = {
      currentWeightKg: primary.weight_kg ?? 0,
      incrementKg: primary.increment_kg ?? 2.5,
      repMin: primary.rep_min ?? 8,
      repMax: primary.rep_max ?? 12,
      targetRir: primary.target_rir ?? null,
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
    exerciseName: sets[0]?.exercise_name ?? null,
  }));

  const untrustedNotes = recentWorkouts.map((w) => w.note).filter((n) => n && n.trim() !== "");
  if (req.message) untrustedNotes.push(req.message);

  return { profile, fitbit, recentWorkouts, readiness, deterministic, targetId, untrustedNotes };
}
