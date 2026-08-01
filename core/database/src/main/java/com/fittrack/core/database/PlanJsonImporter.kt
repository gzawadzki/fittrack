package com.fittrack.core.database

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the versioned plan JSON documents. Shape mirrors (and extends with
 * an optional `schemaVersion`) the original app/src/main/assets/sample_plan.json:
 *
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "name": "Plan startowy",
 *   "dayLabel": "Full body A",
 *   "scheduledDayOfWeek": 2,
 *   "scheduledMinuteOfDay": 1080,
 *   "exercises": [
 *     {
 *       "name": "Przysiad ze sztangą",
 *       "movementPattern": "SQUAT", "muscleRole": "QUADS", "equipment": "BARBELL",
 *       "warmupSets": 2, "workingSets": 3, "repMin": 6, "repMax": 8,
 *       "targetRir": 2, "startingWeightKg": 40.0, "restSeconds": 180, "weightIncrementKg": 2.5
 *     }
 *   ]
 * }
 * ```
 *
 * `schemaVersion` is optional and defaults to 1 for backward compatibility
 * with the original prototype fixture. Unknown future versions are rejected
 * with a clear error rather than silently misparsed.
 */
object PlanJsonImporter {
    const val SUPPORTED_SCHEMA_VERSION = 1

    data class ParsedExercise(
        val name: String,
        val movementPattern: String,
        val muscleRole: String,
        val equipment: String,
        val warmupSets: Int,
        val workingSets: Int,
        val repMin: Int,
        val repMax: Int,
        val targetRir: Int?,
        val startingWeightKg: Double,
        val restSeconds: Int,
        val weightIncrementKg: Double,
    )

    data class ParsedPlan(
        val schemaVersion: Int,
        val name: String,
        val dayLabel: String,
        val scheduledDayOfWeek: Int?,
        val scheduledMinuteOfDay: Int?,
        val exercises: List<ParsedExercise>,
    )

    data class ParsedLibraryExercise(
        val name: String,
        val movementPattern: String,
        val muscleRole: String,
        val equipment: String,
    )

    fun parsePlan(json: String): ParsedPlan {
        val root = JSONObject(json)
        val schemaVersion = root.optInt("schemaVersion", 1)
        require(schemaVersion in 1..SUPPORTED_SCHEMA_VERSION) {
            "Unsupported plan schema version $schemaVersion (supported: 1..$SUPPORTED_SCHEMA_VERSION)"
        }
        val name = root.getString("name")
        require(name.isNotBlank()) { "Plan name is required" }
        val exercisesJson = root.getJSONArray("exercises")
        require(exercisesJson.length() > 0) { "Plan must contain at least one exercise" }
        val exercises = (0 until exercisesJson.length()).map { i -> parseExercise(exercisesJson.getJSONObject(i)) }
        return ParsedPlan(
            schemaVersion = schemaVersion,
            name = name,
            dayLabel = root.optString("dayLabel", name).ifBlank { name },
            scheduledDayOfWeek = root.optInt("scheduledDayOfWeek", -1).takeIf { it in 1..7 },
            scheduledMinuteOfDay = root.optInt("scheduledMinuteOfDay", -1).takeIf { it in 0..1439 },
            exercises = exercises,
        )
    }

    fun parseLibrary(json: String): List<ParsedLibraryExercise> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            ParsedLibraryExercise(
                name = item.getString("name"),
                movementPattern = item.getString("movementPattern"),
                muscleRole = item.getString("muscleRole"),
                equipment = item.getString("equipment"),
            )
        }
    }

    private fun parseExercise(item: JSONObject): ParsedExercise {
        val name = item.getString("name")
        require(name.isNotBlank()) { "Exercise name is required" }
        val repMin = item.getInt("repMin")
        val repMax = item.getInt("repMax").coerceAtLeast(repMin)
        return ParsedExercise(
            name = name,
            movementPattern = item.getString("movementPattern"),
            muscleRole = item.getString("muscleRole"),
            equipment = item.getString("equipment"),
            warmupSets = item.optInt("warmupSets", 0).coerceIn(0, 10),
            workingSets = item.getInt("workingSets").coerceIn(1, 10),
            repMin = repMin.coerceIn(1, 50),
            repMax = repMax.coerceAtMost(100),
            targetRir = if (item.has("targetRir") && !item.isNull("targetRir")) item.getInt("targetRir").coerceIn(0, 10) else null,
            startingWeightKg = item.getDouble("startingWeightKg").coerceAtLeast(0.0),
            restSeconds = item.getInt("restSeconds").coerceIn(15, 600),
            weightIncrementKg = item.getDouble("weightIncrementKg").coerceAtLeast(0.25),
        )
    }
}
