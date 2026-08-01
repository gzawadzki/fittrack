// M5 shared helper: whitelist of syncable entity types, their table names,
// and the columns a client is allowed to CREATE/UPDATE through sync-batch.
//
// This whitelist is the SQL-injection guard for sync-batch: client payload
// keys are only ever used if they appear in `columns` below, and table
// names are only ever selected from `ENTITY_TABLES` -- never taken directly
// from client input and interpolated into a query.
//
// coach_suggestions is intentionally NOT writable by clients (server/M7
// creates them, suggestion-decision is the only thing that mutates them);
// it is still readable, so it is listed in ALL_TABLES for the pull side of
// sync-batch but omitted from ENTITY_TABLES (the writable set).

export interface EntityDef {
  table: string;
  columns: readonly string[];
}

export const ENTITY_TABLES: Record<string, EntityDef> = {
  user_profiles: {
    table: "user_profiles",
    columns: ["goal", "experience", "equipment", "schedule", "constraints", "preferences", "approved", "profile_version"],
  },
  exercises: {
    table: "exercises",
    columns: ["name", "movement_pattern", "muscle_role", "equipment", "active"],
  },
  workout_plans: {
    table: "workout_plans",
    columns: ["name", "day_label", "scheduled_day_of_week", "scheduled_minute_of_day", "active", "plan_version"],
  },
  plan_exercises: {
    table: "plan_exercises",
    columns: [
      "plan_id",
      "exercise_id",
      "position",
      "warmup_sets",
      "working_sets",
      "rep_min",
      "rep_max",
      "target_rir",
      "starting_weight_kg",
      "rest_seconds",
      "weight_increment_kg",
      "notes",
    ],
  },
  workout_sessions: {
    table: "workout_sessions",
    columns: [
      "plan_id",
      "plan_name_snapshot",
      "plan_version_snapshot",
      "day_label_snapshot",
      "plan_snapshot_json",
      "status",
      "started_at",
      "ended_at",
      "note",
      "fitbit_sync_status",
    ],
  },
  exercise_sessions: {
    table: "exercise_sessions",
    columns: [
      "workout_session_id",
      "plan_exercise_id",
      "exercise_id",
      "original_exercise_id",
      "exercise_name_snapshot",
      "position",
      "rest_seconds_snapshot",
      "status",
      "started_at",
      "ended_at",
      "skipped_at",
      "skip_reason",
      "note",
    ],
  },
  set_logs: {
    table: "set_logs",
    columns: [
      "exercise_session_id",
      "position",
      "set_type",
      "target_rep_min",
      "target_rep_max",
      "target_rir",
      "target_weight_kg",
      "actual_reps",
      "actual_weight_kg",
      "actual_rir",
      "pain",
      "technique_issue",
      "concerning_symptom",
      "completed_at",
    ],
  },
};

// Read-only (via sync-batch pull) in addition to the writable tables above.
export const READONLY_TABLES: Record<string, EntityDef> = {
  coach_suggestions: {
    table: "coach_suggestions",
    columns: [],
  },
};

export const ALL_PULLABLE_TABLES: Record<string, EntityDef> = {
  ...ENTITY_TABLES,
  ...READONLY_TABLES,
};

export function isWritableEntityType(entityType: string): boolean {
  return Object.prototype.hasOwnProperty.call(ENTITY_TABLES, entityType);
}
