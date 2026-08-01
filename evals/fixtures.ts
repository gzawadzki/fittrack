// Shared CoachContext fixtures for pipeline eval cases.

import type { CoachContext, FitbitAggregateSummary, ProfileSummary, RecentWorkoutSummary } from "../supabase/functions/coach/context.ts";
import { ProgressionEngine, type ProgressionInput, type ReadinessLevel } from "../supabase/functions/coach/progression.ts";

export const REAL_SOURCE_URL = "https://www.nsca.com/education/articles/ksa/progressive-overload/";
export const REAL_SOURCE_TITLE = "Progressive overload and strength adaptation";
export const FABRICATED_SOURCE_URL = "https://example.com/totally-not-a-real-study";

const defaultProfile: ProfileSummary = {
  goal: "General strength",
  experience: "Intermediate",
  equipment: ["barbell", "dumbbells"],
  schedule: "3x/week",
  constraints: "None reported",
  preferences: "Prefers compound lifts",
};

const defaultFitbit: FitbitAggregateSummary = {
  restingHeartRate: 58,
  sleepMinutesLastNight: 440,
  stepsLastNight: 8000,
  avgHrDuringLastWorkout: 128,
  maxHrDuringLastWorkout: 162,
  hrRecoveryDeltaBpm: 22,
};

function workout(note: string, overrides: Partial<RecentWorkoutSummary> = {}): RecentWorkoutSummary {
  return {
    sessionId: "session-1",
    status: "COMPLETED",
    startedAt: "2026-07-30T17:00:00Z",
    note,
    exerciseTargetId: "target-bench-press",
    exerciseName: "Barbell Bench Press",
    ...overrides,
  };
}

export function makeContext(overrides: Partial<CoachContext> & { progressionInput?: ProgressionInput } = {}): CoachContext {
  const { progressionInput, ...rest } = overrides;
  const deterministic = progressionInput ? ProgressionEngine.recommend(progressionInput) : (rest.deterministic ?? null);

  return {
    profile: defaultProfile,
    fitbit: defaultFitbit,
    recentWorkouts: [workout("Felt strong today, no issues.")],
    readiness: "GREEN" as ReadinessLevel,
    deterministic,
    targetId: "target-bench-press",
    untrustedNotes: [],
    ...rest,
    deterministic,
  };
}
