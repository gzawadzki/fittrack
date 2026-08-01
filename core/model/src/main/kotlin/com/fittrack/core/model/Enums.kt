package com.fittrack.core.model

/**
 * Frozen cross-module enums — see IMPLEMENTATION_PLAN.md section 4.
 * Do not rename/remove members without an ADR + coordinated update across
 * client, backend and contract tests.
 */

enum class SetType { WARMUP, WORKING }

enum class WorkoutStatus { ACTIVE, COMPLETED, CANCELLED }

enum class FitbitSyncStatus { NOT_REQUESTED, PENDING, SYNCED, FAILED }

enum class SuggestionType { LOAD, VOLUME, SWAP, RESCHEDULE, HOLD }

enum class SuggestionStatus { PENDING, ACCEPTED, REJECTED }

enum class Confidence { LOW, MEDIUM, HIGH }

/** Live status of a single exercise inside an in-progress or finished workout. */
enum class ExerciseSessionStatus { PENDING, ACTIVE, COMPLETED, SKIPPED }
