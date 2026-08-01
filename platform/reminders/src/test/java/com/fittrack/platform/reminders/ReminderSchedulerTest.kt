package com.fittrack.platform.reminders

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderSchedulerTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")

    @Test
    fun `next occurrence follows civil time across daylight saving change`() {
        val clock = Clock.fixed(Instant.parse("2026-03-28T09:00:00Z"), warsaw)
        val plan = WorkoutReminderPlan("plan", "Sunday", DayOfWeek.SUNDAY, 9 * 60)

        val result = ReminderScheduler.nextOccurrence(plan, clock)

        assertEquals(Instant.parse("2026-03-29T07:00:00Z"), result)
    }

    @Test
    fun `next occurrence moves to following week after todays start`() {
        val clock = Clock.fixed(Instant.parse("2026-08-03T17:01:00Z"), warsaw)
        val plan = WorkoutReminderPlan("plan", "Monday", DayOfWeek.MONDAY, 19 * 60)

        val result = ReminderScheduler.nextOccurrence(plan, clock)

        assertEquals(Instant.parse("2026-08-10T17:00:00Z"), result)
    }

    @Test
    fun `previous occurrence remains in current civil week`() {
        val clock = Clock.fixed(Instant.parse("2026-08-03T17:30:00Z"), warsaw)
        val plan = WorkoutReminderPlan("plan", "Monday", DayOfWeek.MONDAY, 19 * 60)

        val result = ReminderScheduler.previousOccurrence(plan, clock)

        assertEquals(Instant.parse("2026-08-03T17:00:00Z"), result)
    }
}
