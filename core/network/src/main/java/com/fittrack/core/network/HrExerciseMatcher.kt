package com.fittrack.core.network

import java.time.Duration
import java.time.Instant

/**
 * Kotlin port of `supabase/functions/fitbit-shared/hr-window.ts`'s
 * `computeHrWindow` and `matchAndAggregate`, adapted for on-device use with
 * Android Health Connect instead of a server-side call to Fitbit's intraday
 * API. See `docs/adr/0001-fitbit-health-connect-pivot.md` for why this moved
 * on-device and what changed.
 *
 * Deliberately NOT ported: the old `splitWindowByDay` step. That existed only
 * because Fitbit's intraday endpoint is scoped to a single calendar day per
 * request; Health Connect's `TimeRangeFilter` accepts an arbitrary interval,
 * so there is nothing to split here.
 *
 * Pure functions, no Android/Health Connect types — unit-testable on the
 * plain JVM (see HrExerciseMatcherTest.kt).
 */
object HrExerciseMatcher {

    private val FIVE_MINUTES: Duration = Duration.ofMinutes(5)
    private val ONE_DAY: Duration = Duration.ofDays(1)
    private val RECOVERY_LOOKAHEAD: Duration = Duration.ofSeconds(60)
    private val RECOVERY_TOLERANCE: Duration = Duration.ofSeconds(15)

    /**
     * Computes `[startedAt - 5min, endedAt + 5min]`, clamped so the window
     * never spans more than 24h. If padding would push the window past 24h,
     * the END is clamped inward first (favoring full pre-workout context),
     * matching the original TS behavior exactly.
     */
    fun computeHrWindow(startedAt: Instant, endedAt: Instant): HrWindow {
        require(!endedAt.isBefore(startedAt)) { "endedAt must not be before startedAt" }

        val windowStart = startedAt.minus(FIVE_MINUTES)
        var windowEnd = endedAt.plus(FIVE_MINUTES)
        if (Duration.between(windowStart, windowEnd) > ONE_DAY) {
            windowEnd = windowStart.plus(ONE_DAY)
        }
        return HrWindow(windowStart, windowEnd)
    }

    /**
     * Matches heart-rate samples into each exercise's `[startAt, endAt]`
     * window (inclusive) and computes average/max/recovery-delta. Exercises
     * with zero overlapping samples get `null` fields (never `0`) and
     * `sampleCount = 0` — callers must render this as "no data", not as a
     * real zero. Mirrors `matchAndAggregate` in hr-window.ts, including the
     * never-false-zero guarantee and the +/-15s recovery-sample tolerance.
     *
     * Samples are not assumed to be evenly spaced or 1Hz — Health Connect
     * gives no such guarantee (see the ADR). The algorithm only relies on
     * timestamp ordering/overlap, never on a fixed sampling interval.
     */
    fun matchAndAggregate(
        samples: List<HrSample>,
        exercises: List<ExerciseTimeRange>,
    ): List<ExerciseHrAggregate> {
        val sorted = samples.sortedBy { it.sampledAt }

        return exercises.map { exercise ->
            val inRange = sorted.filter { sample ->
                !sample.sampledAt.isBefore(exercise.startAt) && !sample.sampledAt.isAfter(exercise.endAt)
            }

            if (inRange.isEmpty()) {
                return@map ExerciseHrAggregate(
                    exerciseId = exercise.exerciseId,
                    avgBpm = null,
                    maxBpm = null,
                    recoveryDeltaBpm = null,
                    sampleCount = 0,
                )
            }

            val avgBpm = inRange.sumOf { it.bpm } / inRange.size.toDouble()
            val maxBpm = inRange.maxOf { it.bpm }
            val recoveryDeltaBpm = computeRecoveryDelta(sorted, exercise.endAt, maxBpm)

            ExerciseHrAggregate(
                exerciseId = exercise.exerciseId,
                avgBpm = avgBpm,
                maxBpm = maxBpm,
                recoveryDeltaBpm = recoveryDeltaBpm,
                sampleCount = inRange.size,
            )
        }
    }

    private fun computeRecoveryDelta(
        sortedSamples: List<HrSample>,
        exerciseEndAt: Instant,
        maxBpmDuringExercise: Int,
    ): Int? {
        val target = exerciseEndAt.plus(RECOVERY_LOOKAHEAD)

        var best: HrSample? = null
        var bestDiff = Long.MAX_VALUE
        for (sample in sortedSamples) {
            if (sample.sampledAt.isBefore(exerciseEndAt)) continue
            val diff = Duration.between(target, sample.sampledAt).abs().toMillis()
            if (diff <= RECOVERY_TOLERANCE.toMillis() && diff < bestDiff) {
                best = sample
                bestDiff = diff
            }
        }

        return best?.let { maxBpmDuringExercise - it.bpm }
    }
}

data class HrWindow(val start: Instant, val end: Instant)

data class HrSample(val sampledAt: Instant, val bpm: Int)

data class ExerciseTimeRange(
    val exerciseId: String,
    /** inclusive */
    val startAt: Instant,
    /** inclusive */
    val endAt: Instant,
)

data class ExerciseHrAggregate(
    val exerciseId: String,
    val avgBpm: Double?,
    val maxBpm: Int?,
    /** recoveryDeltaBpm = (max bpm during exercise) - (bpm ~60s after exercise ends), null if unavailable. */
    val recoveryDeltaBpm: Int?,
    val sampleCount: Int,
)
