// Category: PROGRESSION CORRECTNESS.
// These 12 cases exercise the deterministic ProgressionEngine directly (no model involved -
// no simulation caveat applies here, this is real, runnable, offline verification of the
// business rules in IMPLEMENTATION_PLAN.md section 5).

import type { ProgressionAction, ProgressionInput, ReadinessLevel } from "../supabase/functions/coach/progression.ts";

export interface ProgressionEvalCase {
  id: string;
  description: string;
  input: ProgressionInput;
  expectedAction: ProgressionAction;
  expectedReasonCode: string;
  expectedWeightKg?: number;
}

const baseInput = (overrides: Partial<ProgressionInput>): ProgressionInput => ({
  currentWeightKg: 60,
  incrementKg: 2.5,
  repMin: 8,
  repMax: 12,
  targetRir: 2,
  sets: [],
  consecutiveSuccessfulSessions: 0,
  consecutiveMissedSessions: 0,
  readiness: "GREEN" as ReadinessLevel,
  ...overrides,
});

export const progressionCases: ProgressionEvalCase[] = [
  {
    id: "prog-01-increase-top-range-rir-met",
    description: "All sets at top of rep range with RIR target met -> increase load one increment.",
    input: baseInput({ sets: [{ reps: 12, rir: 2 }, { reps: 12, rir: 2 }, { reps: 12, rir: 3 }] }),
    expectedAction: "INCREASE_LOAD",
    expectedReasonCode: "TOP_RANGE_REACHED",
    expectedWeightKg: 62.5,
  },
  {
    id: "prog-02-hold-rir-not-met",
    description: "All sets at top of range but RIR below target -> hold, do not increase.",
    input: baseInput({ sets: [{ reps: 12, rir: 0 }, { reps: 12, rir: 1 }] }),
    expectedAction: "HOLD_LOAD",
    expectedReasonCode: "PROGRESS_REPS",
  },
  {
    id: "prog-03-first-miss-below-min-holds",
    description: "First session below rep minimum -> hold, not an immediate decrease.",
    input: baseInput({ sets: [{ reps: 5, rir: 0 }], consecutiveMissedSessions: 0 }),
    expectedAction: "HOLD_LOAD",
    expectedReasonCode: "FIRST_MISS",
  },
  {
    id: "prog-04-two-consecutive-misses-decreases",
    description: "Second consecutive session below rep minimum -> decrease by one increment.",
    input: baseInput({ sets: [{ reps: 5, rir: 0 }], consecutiveMissedSessions: 1 }),
    expectedAction: "DECREASE_LOAD",
    expectedReasonCode: "TWO_CONSECUTIVE_MISSES",
    expectedWeightKg: 57.5,
  },
  {
    id: "prog-05-decrease-never-goes-negative",
    description: "Decrease at a very light weight clamps at 0, never negative.",
    input: baseInput({ currentWeightKg: 1, incrementKg: 2.5, sets: [{ reps: 5, rir: 0 }], consecutiveMissedSessions: 2 }),
    expectedAction: "DECREASE_LOAD",
    expectedReasonCode: "TWO_CONSECUTIVE_MISSES",
    expectedWeightKg: 0,
  },
  {
    id: "prog-06-no-rir-two-successful-sessions-increase",
    description: "No RIR collected, but two consecutive successful sessions -> increase allowed.",
    input: baseInput({ targetRir: null, sets: [{ reps: 12 }, { reps: 12 }], consecutiveSuccessfulSessions: 1 }),
    expectedAction: "INCREASE_LOAD",
    expectedReasonCode: "TOP_RANGE_REACHED",
  },
  {
    id: "prog-07-no-rir-zero-successful-sessions-holds",
    description: "No RIR collected and no prior successful session streak -> hold, do not assume reserve.",
    input: baseInput({ targetRir: null, sets: [{ reps: 12 }, { reps: 12 }], consecutiveSuccessfulSessions: 0 }),
    expectedAction: "HOLD_LOAD",
    expectedReasonCode: "PROGRESS_REPS",
  },
  {
    id: "prog-08-mid-range-reps-hold",
    description: "Reps within range but not at top -> hold and build reps.",
    input: baseInput({ sets: [{ reps: 10, rir: 2 }] }),
    expectedAction: "HOLD_LOAD",
    expectedReasonCode: "PROGRESS_REPS",
  },
  {
    id: "prog-09-red-readiness-caps-progression-to-volume-cut",
    description: "Performance supports increase, but RED readiness -> reduce volume instead of increasing load.",
    input: baseInput({ sets: [{ reps: 12, rir: 3 }, { reps: 12, rir: 3 }], readiness: "RED" }),
    expectedAction: "REDUCE_VOLUME",
    expectedReasonCode: "RED_READINESS",
  },
  {
    id: "prog-10-yellow-readiness-holds-instead-of-increase",
    description: "Performance supports increase, but YELLOW readiness -> hold instead of increasing load.",
    input: baseInput({ sets: [{ reps: 12, rir: 3 }, { reps: 12, rir: 3 }], readiness: "YELLOW" }),
    expectedAction: "HOLD_LOAD",
    expectedReasonCode: "YELLOW_READINESS",
  },
  {
    id: "prog-11-warmup-sets-excluded-from-evaluation",
    description: "Warmup sets below rep minimum do not count; only working sets are evaluated.",
    input: baseInput({ sets: [{ reps: 1, rir: 0, isWarmup: true }, { reps: 12, rir: 3 }, { reps: 12, rir: 3 }] }),
    expectedAction: "INCREASE_LOAD",
    expectedReasonCode: "TOP_RANGE_REACHED",
  },
  {
    id: "prog-12-no-working-sets-holds",
    description: "Only warmups logged, no working sets -> hold with NO_WORKING_SETS.",
    input: baseInput({ sets: [{ reps: 5, rir: 0, isWarmup: true }] }),
    expectedAction: "HOLD_LOAD",
    expectedReasonCode: "NO_WORKING_SETS",
  },
];
