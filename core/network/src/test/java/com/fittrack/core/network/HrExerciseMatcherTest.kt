package com.fittrack.core.network

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin-side port of the coverage in
 * `supabase/functions/fitbit-tests/hr-window.test.ts`, checked against the
 * same fixture shape (squat/bench/not-worn) used there so the on-device
 * matcher and the (now-unused-in-production, kept-as-reference) TS
 * implementation stay behaviorally aligned.
 *
 * IMPORTANT: written against no real Gradle/Android build in this sandbox
 * (no JDK/Android SDK available) — see fitbit-HANDOFF.md. This has not been
 * compiled or executed; treat as unverified until run via `./gradlew
 * :core:network:test` on a machine with a JDK.
 */
class HrExerciseMatcherTest {

    @Test
    fun `computeHrWindow pads 5 minutes each side`() {
        val start = Instant.parse("2026-08-01T10:00:00Z")
        val end = Instant.parse("2026-08-01T10:30:00Z")

        val window = HrExerciseMatcher.computeHrWindow(start, end)

        assertEquals(Instant.parse("2026-08-01T09:55:00Z"), window.start)
        assertEquals(Instant.parse("2026-08-01T10:35:00Z"), window.end)
    }

    @Test
    fun `computeHrWindow clamps end inward when padding would exceed 24h`() {
        val start = Instant.parse("2026-08-01T00:00:00Z")
        val end = Instant.parse("2026-08-01T23:58:00Z") // ~24h workout

        val window = HrExerciseMatcher.computeHrWindow(start, end)

        assertEquals(Instant.parse("2026-07-31T23:55:00Z"), window.start)
        assertTrue(
            "window must not exceed 24h",
            java.time.Duration.between(window.start, window.end) <= java.time.Duration.ofDays(1),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `computeHrWindow rejects endedAt before startedAt`() {
        HrExerciseMatcher.computeHrWindow(
            Instant.parse("2026-08-01T10:00:00Z"),
            Instant.parse("2026-08-01T09:00:00Z"),
        )
    }

    @Test
    fun `matchAndAggregate never reports a false zero for an exercise with no samples`() {
        val samples = listOf(
            HrSample(Instant.parse("2026-08-01T10:02:00Z"), 100),
            HrSample(Instant.parse("2026-08-01T10:05:00Z"), 150),
            HrSample(Instant.parse("2026-08-01T10:08:00Z"), 130),
            // ~60s after squat ends (10:12) -> recovery sample
            HrSample(Instant.parse("2026-08-01T10:13:00Z"), 95),
            HrSample(Instant.parse("2026-08-01T10:15:00Z"), 110),
            HrSample(Instant.parse("2026-08-01T10:20:00Z"), 145),
            HrSample(Instant.parse("2026-08-01T10:24:00Z"), 125),
        )
        val exercises = listOf(
            ExerciseTimeRange("ex-squat", Instant.parse("2026-08-01T10:02:00Z"), Instant.parse("2026-08-01T10:12:00Z")),
            ExerciseTimeRange("ex-bench", Instant.parse("2026-08-01T10:15:00Z"), Instant.parse("2026-08-01T10:25:00Z")),
            ExerciseTimeRange("ex-not-worn", Instant.parse("2026-08-01T10:30:00Z"), Instant.parse("2026-08-01T10:32:00Z")),
        )

        val aggregates = HrExerciseMatcher.matchAndAggregate(samples, exercises)

        val squat = aggregates.first { it.exerciseId == "ex-squat" }
        val bench = aggregates.first { it.exerciseId == "ex-bench" }
        val notWorn = aggregates.first { it.exerciseId == "ex-not-worn" }

        assertEquals(3, squat.sampleCount)
        assertEquals(150, squat.maxBpm)
        assertTrue(squat.recoveryDeltaBpm != null)
        assertEquals(150 - 95, squat.recoveryDeltaBpm)

        assertEquals(3, bench.sampleCount)
        assertEquals(145, bench.maxBpm)

        assertEquals(0, notWorn.sampleCount)
        assertNull(notWorn.avgBpm)
        assertNull(notWorn.maxBpm)
        assertNull(notWorn.recoveryDeltaBpm)
    }

    @Test
    fun `matchAndAggregate does not assume evenly spaced samples`() {
        // Deliberately irregular gaps (7s, 41s, 2s) — Health Connect gives no
        // uniform-sampling guarantee (see ADR 0001).
        val samples = listOf(
            HrSample(Instant.parse("2026-08-01T10:00:00Z"), 100),
            HrSample(Instant.parse("2026-08-01T10:00:07Z"), 120),
            HrSample(Instant.parse("2026-08-01T10:00:48Z"), 140),
            HrSample(Instant.parse("2026-08-01T10:00:50Z"), 90),
        )
        val exercises = listOf(
            ExerciseTimeRange("ex", Instant.parse("2026-08-01T10:00:00Z"), Instant.parse("2026-08-01T10:01:00Z")),
        )

        val aggregate = HrExerciseMatcher.matchAndAggregate(samples, exercises).single()

        assertEquals(4, aggregate.sampleCount)
        assertEquals(140, aggregate.maxBpm)
        assertEquals((100 + 120 + 140 + 90) / 4.0, aggregate.avgBpm)
    }
}
