// Pure functions for computing the HR fetch window and matching/aggregating
// 1s-resolution HR samples against exercise/set timestamps. No I/O here —
// keeps this unit-testable without Deno or a DB.

const FIVE_MIN_MS = 5 * 60 * 1000;
const ONE_DAY_MS = 24 * 60 * 60 * 1000;

export interface HrWindow {
  startIso: string;
  endIso: string;
}

export interface DaySegment {
  dateYyyyMmDd: string;
  startHHmm: string;
  endHHmm: string;
}

/**
 * Splits an (already <=24h) window into per-calendar-day segments, since
 * Fitbit's intraday endpoint is scoped to a single date. Dates/times are
 * computed in UTC for determinism; see fitbit-HANDOFF.md for the
 * user-timezone caveat.
 */
export function splitWindowByDay(startIso: string, endIso: string): DaySegment[] {
  const startMs = Date.parse(startIso);
  const endMs = Date.parse(endIso);
  if (Number.isNaN(startMs) || Number.isNaN(endMs) || endMs < startMs) {
    throw new Error("splitWindowByDay: invalid window");
  }

  const segments: DaySegment[] = [];
  let cursor = startMs;
  while (cursor <= endMs) {
    const cursorDate = new Date(cursor);
    const dayEndMs = Date.UTC(
      cursorDate.getUTCFullYear(),
      cursorDate.getUTCMonth(),
      cursorDate.getUTCDate(),
      23,
      59,
      59,
      999,
    );
    const segmentEndMs = Math.min(endMs, dayEndMs);
    segments.push({
      dateYyyyMmDd: cursorDate.toISOString().slice(0, 10),
      startHHmm: toHHmm(cursor),
      endHHmm: toHHmm(segmentEndMs),
    });
    cursor = segmentEndMs + 1;
  }
  return segments;
}

function toHHmm(ms: number): string {
  const d = new Date(ms);
  const hh = String(d.getUTCHours()).padStart(2, "0");
  const mm = String(d.getUTCMinutes()).padStart(2, "0");
  return `${hh}:${mm}`;
}

/**
 * Computes [workout.startedAt - 5min, workout.endedAt + 5min], clamped so the
 * window never spans more than one calendar day, per Fitbit's intraday API
 * constraint (a single request must stay within one day). If padding would
 * push the window past 24h, we clamp the END inward first (favoring full
 * pre-workout context) down to exactly one day from the (padded) start.
 */
export function computeHrWindow(
  startedAtIso: string,
  endedAtIso: string,
): HrWindow {
  const startedAt = Date.parse(startedAtIso);
  const endedAt = Date.parse(endedAtIso);
  if (Number.isNaN(startedAt) || Number.isNaN(endedAt)) {
    throw new Error("computeHrWindow: invalid startedAt/endedAt");
  }
  if (endedAt < startedAt) {
    throw new Error("computeHrWindow: endedAt before startedAt");
  }

  let windowStart = startedAt - FIVE_MIN_MS;
  let windowEnd = endedAt + FIVE_MIN_MS;

  if (windowEnd - windowStart > ONE_DAY_MS) {
    windowEnd = windowStart + ONE_DAY_MS;
  }

  return {
    startIso: new Date(windowStart).toISOString(),
    endIso: new Date(windowEnd).toISOString(),
  };
}

export interface HrSample {
  ts: string; // ISO instant
  bpm: number;
}

export interface ExerciseTimeRange {
  exerciseId: string;
  /** inclusive */
  startIso: string;
  /** inclusive */
  endIso: string;
  /** end of workout/window used for recovery-delta lookahead */
  recoveryWindowEndIso?: string;
}

export interface ExerciseHrAggregate {
  exerciseId: string;
  avgBpm: number | null;
  maxBpm: number | null;
  /** recoveryDeltaBpm = (max bpm during exercise) - (bpm ~60s after exercise ends), null if unavailable */
  recoveryDeltaBpm: number | null;
  sampleCount: number;
}

const RECOVERY_LOOKAHEAD_MS = 60 * 1000;
const RECOVERY_TOLERANCE_MS = 15 * 1000; // accept nearest sample within +/-15s of the 60s mark

/**
 * Matches HR samples into each exercise's [startIso, endIso] window and
 * computes avg/max/recovery-delta. Exercises with zero overlapping samples
 * get `null` fields (never 0) and sampleCount 0 — callers must render this
 * as "no data", not as a real zero.
 */
export function matchAndAggregate(
  samples: HrSample[],
  exercises: ExerciseTimeRange[],
): ExerciseHrAggregate[] {
  const sorted = [...samples].sort(
    (a, b) => Date.parse(a.ts) - Date.parse(b.ts),
  );

  return exercises.map((ex) => {
    const startMs = Date.parse(ex.startIso);
    const endMs = Date.parse(ex.endIso);

    const inRange = sorted.filter((s) => {
      const t = Date.parse(s.ts);
      return t >= startMs && t <= endMs;
    });

    if (inRange.length === 0) {
      return {
        exerciseId: ex.exerciseId,
        avgBpm: null,
        maxBpm: null,
        recoveryDeltaBpm: null,
        sampleCount: 0,
      };
    }

    const sum = inRange.reduce((acc, s) => acc + s.bpm, 0);
    const avgBpm = sum / inRange.length;
    const maxBpm = Math.max(...inRange.map((s) => s.bpm));

    const recoveryDeltaBpm = computeRecoveryDelta(sorted, endMs, maxBpm);

    return {
      exerciseId: ex.exerciseId,
      avgBpm,
      maxBpm,
      recoveryDeltaBpm,
      sampleCount: inRange.length,
    };
  });
}

function computeRecoveryDelta(
  sortedSamples: HrSample[],
  exerciseEndMs: number,
  maxBpmDuringExercise: number,
): number | null {
  const targetMs = exerciseEndMs + RECOVERY_LOOKAHEAD_MS;

  let best: HrSample | null = null;
  let bestDiff = Infinity;
  for (const s of sortedSamples) {
    const t = Date.parse(s.ts);
    if (t < exerciseEndMs) continue;
    const diff = Math.abs(t - targetMs);
    if (diff <= RECOVERY_TOLERANCE_MS && diff < bestDiff) {
      best = s;
      bestDiff = diff;
    }
  }

  if (!best) return null;
  return maxBpmDuringExercise - best.bpm;
}
