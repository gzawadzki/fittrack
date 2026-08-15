package com.fittrack.core.network

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * On-device Health Connect reader. Replaces the old server-side Fitbit Web
 * API pull (see `docs/adr/0001-fitbit-health-connect-pivot.md`). No client
 * secret, no server round-trip: this reads whatever the Health Connect
 * platform already has (typically written there by the Fitbit companion app,
 * or any other app the user has connected), gated by Android runtime
 * permissions the user grants explicitly.
 *
 * Business rule (IMPLEMENTATION_PLAN.md section 5): steps are context only,
 * never a standalone reason to change a plan/suggestion. This class only
 * *reads and reports* data — it has no reference to PlanRepository/
 * WorkoutRepository and cannot itself alter a plan or suggestion; whatever
 * consumes [HealthDay]/[ExerciseHeartRateSummary] downstream (core/training,
 * feature/coach) is responsible for upholding that rule, same as it already
 * was for Fitbit-sourced data.
 */
interface HealthConnectGateway {
    /** True if Health Connect (the provider app) is installed and usable on this device. */
    fun isAvailable(): Boolean

    /** Permission types this app needs. Pass to [PermissionController]-based
     * request contracts from the UI layer that owns the permission prompt
     * (feature/settings, per this module's HANDOFF — not built here). */
    fun requiredPermissions(): Set<String>

    suspend fun hasAllPermissions(): Boolean

    /**
     * Daily aggregates (steps, sleep minutes, resting heart rate) for the
     * last [days] calendar days in the device's local time zone, most recent
     * first. Missing values are `null`, never `0` — same never-false-zero
     * convention as the old Fitbit pipeline.
     *
     * Steps, sleep and resting heart rate are read independently: if one
     * type's read fails, the outcome is [HealthConnectReadOutcome.PartiallyFailed]
     * carrying whatever the other types produced, rather than losing all
     * three. Only fails outright ([HealthConnectReadOutcome.Failed]) if every
     * one of the three reads failed.
     */
    suspend fun readDailyMetrics(days: Int = 14): HealthConnectReadOutcome<List<HealthConnectDailyMetric>>

    /**
     * Heart-rate samples covering `[startedAt - 5min, endedAt + 5min]`
     * (clamped to <=24h, see [HrExerciseMatcher.computeHrWindow]), matched
     * against each exercise's own `[startAt, endAt]` window. Returns both
     * the raw samples actually read (for local display / debugging only —
     * they are not pushed to the backend, see the ADR) and the computed
     * per-exercise aggregates.
     */
    suspend fun readWorkoutHeartRate(
        workoutStartedAt: Instant,
        workoutEndedAt: Instant,
        exercises: List<ExerciseTimeRange>,
    ): HealthConnectReadOutcome<WorkoutHeartRateReadResult>
}

/** Which of [HealthConnectGateway.readDailyMetrics]'s three independent
 * Health Connect record types a read failure applies to. */
enum class HealthMetricType { STEPS, SLEEP, RESTING_HEART_RATE }

/**
 * Outcome of a Health Connect read. Distinguishes "the platform read
 * succeeded and returned this (possibly empty) data" from "the read itself
 * failed" — collapsing the two into the same empty shape hid real read
 * failures from callers that need to retry them (see the M8 architecture
 * review, candidate 1). [PartiallyFailed] only applies to reads composed of
 * more than one independent Health Connect call (currently just
 * [HealthConnectGateway.readDailyMetrics]); callers of single-call reads
 * only ever see [Ok] or [Failed].
 */
sealed interface HealthConnectReadOutcome<out T> {
    data class Ok<T>(val data: T) : HealthConnectReadOutcome<T>
    data class PartiallyFailed<T>(val data: T, val failedTypes: Set<HealthMetricType>) : HealthConnectReadOutcome<T>
    data class Failed(val error: NetworkError) : HealthConnectReadOutcome<Nothing>
}

data class HealthConnectDailyMetric(
    val date: String, // yyyy-MM-dd, local date
    val sleepMinutes: Int?,
    val restingHeartRate: Int?,
    val steps: Int?,
)

data class WorkoutHeartRateReadResult(
    val samples: List<HrSample>,
    val aggregates: List<ExerciseHrAggregate>,
)

/**
 * Classifies a Health Connect read failure. [SecurityException] means the
 * permission was revoked mid-session — retrying won't help until the user
 * re-grants it in Settings, so it is the one case marked non-retryable; any
 * other exception is treated as a transient platform hiccup.
 */
internal fun Throwable.toHealthConnectReadError(): NetworkError = NetworkError(
    code = "HEALTH_CONNECT_READ_FAILED",
    userMessage = "Nie można teraz odczytać danych z Health Connect.",
    retryable = this !is SecurityException,
)

class AndroidHealthConnectGateway(
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : HealthConnectGateway {

    private val client: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    override fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    override fun requiredPermissions(): Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    override suspend fun hasAllPermissions(): Boolean {
        val hc = client ?: return false
        val granted = hc.permissionController.getGrantedPermissions()
        return granted.containsAll(requiredPermissions())
    }

    override suspend fun readDailyMetrics(days: Int): HealthConnectReadOutcome<List<HealthConnectDailyMetric>> {
        require(days > 0) { "days must be positive" }
        val hc = client ?: return HealthConnectReadOutcome.Ok(emptyDailyMetrics(days))

        val today = LocalDate.now(zoneId)
        val startZoned = today.minusDays((days - 1).toLong()).atStartOfDay(zoneId)
        val endZoned = today.plusDays(1).atStartOfDay(zoneId)

        val failedTypes = mutableSetOf<HealthMetricType>()
        var lastFailure: Throwable? = null

        val stepsByDay = try {
            readStepsByDay(hc, startZoned, endZoned)
        } catch (e: Exception) {
            failedTypes += HealthMetricType.STEPS
            lastFailure = e
            emptyMap()
        }
        val sleepByDay = try {
            readSleepMinutesByDay(hc, startZoned, endZoned)
        } catch (e: Exception) {
            failedTypes += HealthMetricType.SLEEP
            lastFailure = e
            emptyMap()
        }
        val restingHrByDay = try {
            readRestingHeartRateByDay(hc, startZoned, endZoned)
        } catch (e: Exception) {
            failedTypes += HealthMetricType.RESTING_HEART_RATE
            lastFailure = e
            emptyMap()
        }

        val metrics = (0 until days).map { offset ->
            val date = today.minusDays(offset.toLong())
            val key = date.toString()
            HealthConnectDailyMetric(
                date = key,
                sleepMinutes = sleepByDay[key],
                restingHeartRate = restingHrByDay[key],
                steps = stepsByDay[key],
            )
        }

        return when {
            failedTypes.size == 3 -> HealthConnectReadOutcome.Failed(lastFailure!!.toHealthConnectReadError())
            failedTypes.isEmpty() -> HealthConnectReadOutcome.Ok(metrics)
            else -> HealthConnectReadOutcome.PartiallyFailed(metrics, failedTypes)
        }
    }

    override suspend fun readWorkoutHeartRate(
        workoutStartedAt: Instant,
        workoutEndedAt: Instant,
        exercises: List<ExerciseTimeRange>,
    ): HealthConnectReadOutcome<WorkoutHeartRateReadResult> {
        val hc = client ?: return HealthConnectReadOutcome.Ok(WorkoutHeartRateReadResult(emptyList(), emptyList()))
        val window = HrExerciseMatcher.computeHrWindow(workoutStartedAt, workoutEndedAt)

        val records = try {
            hc.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(window.start, window.end),
                ),
            ).records
        } catch (e: Exception) {
            return HealthConnectReadOutcome.Failed(e.toHealthConnectReadError())
        }

        // Each HeartRateRecord spans [startTime, endTime] and holds a list of
        // instantaneous samples inside it — NOT one record per sample. See
        // the ADR: this is not guaranteed 1Hz/evenly-spaced data.
        val samples = records
            .flatMap { record -> record.samples }
            .map { sample -> HrSample(sampledAt = sample.time, bpm = sample.beatsPerMinute.toInt()) }
            .filter { !it.sampledAt.isBefore(window.start) && !it.sampledAt.isAfter(window.end) }

        val aggregates = HrExerciseMatcher.matchAndAggregate(samples, exercises)
        return HealthConnectReadOutcome.Ok(WorkoutHeartRateReadResult(samples = samples, aggregates = aggregates))
    }

    private fun emptyDailyMetrics(days: Int): List<HealthConnectDailyMetric> {
        val today = LocalDate.now(zoneId)
        return (0 until days).map { offset ->
            HealthConnectDailyMetric(
                date = today.minusDays(offset.toLong()).toString(),
                sleepMinutes = null,
                restingHeartRate = null,
                steps = null,
            )
        }
    }

    private suspend fun readStepsByDay(
        hc: HealthConnectClient,
        start: ZonedDateTime,
        end: ZonedDateTime,
    ): Map<String, Int> {
        val response = hc.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start.toLocalDateTime(), end.toLocalDateTime()),
                timeRangeSlicer = Period.ofDays(1),
            ),
        )
        return buildMap {
            response.forEach { bucket ->
                val total = bucket.result[StepsRecord.COUNT_TOTAL] ?: return@forEach
                put(bucket.startTime.toLocalDate().toString(), total.toInt())
            }
        }
    }

    private suspend fun readSleepMinutesByDay(
        hc: HealthConnectClient,
        start: ZonedDateTime,
        end: ZonedDateTime,
    ): Map<String, Int> {
        // Sleep sessions are attributed to the day they *end* on (wake-up
        // day), matching how the old Fitbit sleep log was keyed by date.
        val sessions = hc.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start.toInstant(), end.toInstant()),
            ),
        ).records

        val minutesByDay = mutableMapOf<String, Long>()
        sessions.forEach { session ->
            val durationMinutes = java.time.Duration.between(session.startTime, session.endTime).toMinutes()
            val day = session.endTime.atZone(zoneId).toLocalDate().toString()
            minutesByDay[day] = (minutesByDay[day] ?: 0L) + durationMinutes
        }
        return minutesByDay.mapValues { it.value.toInt() }
    }

    private suspend fun readRestingHeartRateByDay(
        hc: HealthConnectClient,
        start: ZonedDateTime,
        end: ZonedDateTime,
    ): Map<String, Int> {
        val records = hc.readRecords(
            ReadRecordsRequest(
                recordType = RestingHeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start.toInstant(), end.toInstant()),
            ),
        ).records

        // If a device writes more than one resting-HR reading for a day, keep
        // the latest one for that day, mirroring "one value per day" from the
        // old fitbit_daily_metrics.resting_heart_rate column.
        val byDay = mutableMapOf<String, RestingHeartRateRecord>()
        records.forEach { record ->
            val day = record.time.atZone(zoneId).toLocalDate().toString()
            val existing = byDay[day]
            if (existing == null || record.time.isAfter(existing.time)) {
                byDay[day] = record
            }
        }
        return byDay.mapValues { it.value.beatsPerMinute.toInt() }
    }
}
