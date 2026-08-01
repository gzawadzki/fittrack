package com.fittrack.feature.plans

import com.fittrack.core.model.Exercise
import com.fittrack.core.model.PlanExercise
import com.fittrack.core.model.WorkoutPlan
import java.io.Serializable
import java.util.UUID

data class ExerciseTemplate(
    val id: String = UUID.randomUUID().toString(),
    val exerciseId: String = UUID.randomUUID().toString(),
    val name: String = "",
    val movementPattern: String = "OTHER",
    val muscleRole: String = "OTHER",
    val equipment: String = "OTHER",
    val warmupSets: Int = 0,
    val workingSets: Int = 3,
    val repMin: Int = 8,
    val repMax: Int = 12,
    val targetRir: Int? = 2,
    val startingWeightKg: Double = 0.0,
    val restSeconds: Int = 120,
    val weightIncrementKg: Double = 2.5,
    val note: String = "",
) : Serializable

data class WorkoutPlanDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val dayLabel: String = "",
    val scheduledDayOfWeek: Int? = null,
    val scheduledMinuteOfDay: Int? = null,
    val version: Int = 0,
    val active: Boolean = true,
    val exercises: List<ExerciseTemplate> = emptyList(),
) : Serializable

fun WorkoutPlan.toDraft(library: Map<String, Exercise> = emptyMap()) = WorkoutPlanDraft(
    id, name, dayLabel, scheduledDayOfWeek, scheduledMinuteOfDay, version, active,
    exercises.map { it.toTemplate(library[it.exerciseId]) },
)

fun PlanExercise.toTemplate(exercise: Exercise? = null) = ExerciseTemplate(
    id = id,
    exerciseId = exerciseId,
    name = exerciseName,
    movementPattern = exercise?.movementPattern ?: "OTHER",
    muscleRole = exercise?.muscleRole ?: "OTHER",
    equipment = exercise?.equipment ?: "OTHER",
    warmupSets = warmupSets,
    workingSets = workingSets,
    repMin = repMin,
    repMax = repMax,
    targetRir = targetRir,
    startingWeightKg = startingWeightKg,
    restSeconds = restSeconds,
    weightIncrementKg = weightIncrementKg,
    note = notes,
)

fun WorkoutPlanDraft.toDomain() = WorkoutPlan(
    id = id,
    name = name.trim(),
    dayLabel = dayLabel.trim(),
    scheduledDayOfWeek = scheduledDayOfWeek,
    scheduledMinuteOfDay = scheduledMinuteOfDay,
    version = version.coerceAtLeast(1),
    active = active,
    exercises = exercises.mapIndexed { index, item ->
        PlanExercise(
            id = item.id,
            exerciseId = item.exerciseId,
            exerciseName = item.name.trim(),
            position = index,
            warmupSets = item.warmupSets,
            workingSets = item.workingSets,
            repMin = item.repMin,
            repMax = item.repMax,
            targetRir = item.targetRir,
            startingWeightKg = item.startingWeightKg,
            restSeconds = item.restSeconds,
            weightIncrementKg = item.weightIncrementKg,
            notes = item.note.trim(),
        )
    },
)

fun ExerciseTemplate.toExercise() = Exercise(exerciseId, name.trim(), movementPattern.uppercase(), muscleRole.uppercase(), equipment.uppercase())

fun WorkoutPlanDraft.validationError(): String? = when {
    name.isBlank() -> "Podaj nazwę planu"
    exercises.isEmpty() -> "Dodaj przynajmniej jedno ćwiczenie"
    scheduledDayOfWeek != null && scheduledDayOfWeek !in 1..7 -> "Dzień tygodnia musi być w zakresie 1–7"
    scheduledMinuteOfDay != null && scheduledMinuteOfDay !in 0..1439 -> "Godzina planu jest nieprawidłowa"
    exercises.any { it.name.isBlank() } -> "Każde ćwiczenie musi mieć nazwę"
    exercises.any { it.warmupSets !in 0..10 || it.workingSets !in 1..10 } -> "Nieprawidłowa liczba serii"
    exercises.any { it.repMin !in 1..100 || it.repMax < it.repMin || it.repMax > 100 } -> "Nieprawidłowy zakres powtórzeń"
    exercises.any { it.targetRir != null && it.targetRir !in 0..10 } -> "RIR musi być w zakresie 0–10"
    exercises.any { !it.startingWeightKg.isFinite() || !it.weightIncrementKg.isFinite() || it.startingWeightKg < 0 || it.restSeconds !in 15..600 || it.weightIncrementKg < 2.5 } -> "Sprawdź ciężar, przerwę i krok progresji (minimum 2,5 kg)"
    else -> null
}
