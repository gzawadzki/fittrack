// Deterministic progression + readiness engine for the coach function.
//
// This is a TypeScript port of app/src/main/java/com/fittrack/app/domain/ProgressionEngine.kt
// (owned by M1 / core/training). It is duplicated here deliberately, NOT imported, because:
//   1. core/training is Kotlin/JVM and cannot be imported into a Deno Edge Function.
//   2. Section 5 business rules require the NUMERIC recommendation to come from deterministic
//      logic, never from the LLM. The coach function must be able to compute this recommendation
//      independently of the Android client.
// If the business rules in IMPLEMENTATION_PLAN.md section 5 change, this file and the Kotlin
// source must be updated together (see docs/adr/ per section 6).

export type ProgressionAction =
  | "INCREASE_LOAD"
  | "HOLD_LOAD"
  | "DECREASE_LOAD"
  | "REDUCE_VOLUME"
  | "STOP_AND_REVIEW";

export type ReadinessLevel = "UNKNOWN" | "GREEN" | "YELLOW" | "RED";

export interface SetPerformance {
  reps: number;
  rir?: number | null;
  isWarmup?: boolean;
  pain?: boolean;
  techniqueIssue?: boolean;
  concerningSymptom?: boolean;
}

export interface ProgressionInput {
  currentWeightKg: number;
  incrementKg: number;
  repMin: number;
  repMax: number;
  targetRir?: number | null;
  sets: SetPerformance[];
  consecutiveSuccessfulSessions?: number;
  consecutiveMissedSessions?: number;
  readiness?: ReadinessLevel;
}

export interface ProgressionRecommendation {
  action: ProgressionAction;
  proposedWeightKg: number;
  proposedSetDelta: number;
  reasonCode: string;
  explanation: string;
}

export interface RecoveryDay {
  sleepMinutes: number | null;
  restingHeartRate: number | null;
}

function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid];
}

export const ReadinessCalculator = {
  /** Requires >= 7 valid days of sleep AND RHR history (last 14 days) plus today's values. */
  calculate(today: RecoveryDay, history: RecoveryDay[]): ReadinessLevel {
    const validSleep = history.map((d) => d.sleepMinutes).filter((v): v is number => v != null).slice(-14);
    const validRhr = history.map((d) => d.restingHeartRate).filter((v): v is number => v != null).slice(-14);
    if (
      validSleep.length < 7 ||
      validRhr.length < 7 ||
      today.sleepMinutes == null ||
      today.restingHeartRate == null
    ) {
      return "UNKNOWN";
    }

    const sleepBaseline = median(validSleep);
    const rhrBaseline = median(validRhr);
    const lowSleep = today.sleepMinutes < sleepBaseline * 0.8;
    const elevatedRhr = today.restingHeartRate >= rhrBaseline + 5;

    if (lowSleep && elevatedRhr) return "RED";
    if (lowSleep || elevatedRhr) return "YELLOW";
    return "GREEN";
  },
};

function hold(input: ProgressionInput, code: string, explanation: string): ProgressionRecommendation {
  return {
    action: "HOLD_LOAD",
    proposedWeightKg: input.currentWeightKg,
    proposedSetDelta: 0,
    reasonCode: code,
    explanation,
  };
}

export const ProgressionEngine = {
  recommend(input: ProgressionInput): ProgressionRecommendation {
    const workingSets = input.sets.filter((s) => !s.isWarmup);
    if (workingSets.length === 0) {
      return hold(input, "NO_WORKING_SETS", "No completed working sets.");
    }

    // Rule: pain, a concerning symptom, or a technique issue blocks progression. The app never diagnoses.
    if (workingSets.some((s) => s.concerningSymptom || s.pain)) {
      return {
        action: "STOP_AND_REVIEW",
        proposedWeightKg: input.currentWeightKg,
        proposedSetDelta: 0,
        reasonCode: "PAIN_OR_SYMPTOM",
        explanation:
          "Pain or a concerning symptom blocks progression. Stop and assess; the app does not diagnose.",
      };
    }

    if (workingSets.some((s) => s.techniqueIssue)) {
      return hold(input, "TECHNIQUE_ISSUE", "Stabilize technique at the current weight first.");
    }

    const belowMinimum = workingSets.some((s) => s.reps < input.repMin);
    if (belowMinimum) {
      const consecutiveMissed = input.consecutiveMissedSessions ?? 0;
      if (consecutiveMissed >= 1) {
        return {
          action: "DECREASE_LOAD",
          proposedWeightKg: Math.max(0, input.currentWeightKg - input.incrementKg),
          proposedSetDelta: 0,
          reasonCode: "TWO_CONSECUTIVE_MISSES",
          explanation: "Target missed in two consecutive sessions; one step down is proposed.",
        };
      }
      return hold(input, "FIRST_MISS", "First result below range - hold weight and observe the next session.");
    }

    const allAtTop = workingSets.every((s) => s.reps >= input.repMax);
    const rirSupportsIncrease = input.targetRir != null
      ? workingSets.every((s) => s.rir != null && s.rir >= (input.targetRir as number))
      : (input.consecutiveSuccessfulSessions ?? 0) >= 1;

    if (allAtTop && rirSupportsIncrease) {
      const readiness = input.readiness ?? "UNKNOWN";
      if (readiness === "RED") {
        return {
          action: "REDUCE_VOLUME",
          proposedWeightKg: input.currentWeightKg,
          proposedSetDelta: -1,
          reasonCode: "RED_READINESS",
          explanation: "Sleep and resting heart rate are both off-baseline; one fewer set is proposed instead of load progression.",
        };
      }
      if (readiness === "YELLOW") {
        return hold(input, "YELLOW_READINESS", "One recovery signal is off-baseline; hold load progression.");
      }
      return {
        action: "INCREASE_LOAD",
        proposedWeightKg: input.currentWeightKg + input.incrementKg,
        proposedSetDelta: 0,
        reasonCode: "TOP_RANGE_REACHED",
        explanation: "All sets reached the top of the rep range with the required reserve; one increment up is proposed.",
      };
    }

    return hold(input, "PROGRESS_REPS", "Hold weight and keep building reps within the target range.");
  },
};
