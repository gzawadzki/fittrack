// Guards the two entity types this module (M6's Fitbit -> Health Connect
// pivot) added to the shared sync-batch whitelist, so a future edit to
// _shared/entities.ts can't silently drop write access for Health-Connect-
// sourced rows or silently widen it to a column the migration doesn't have.

import test from "node:test";
import assert from "node:assert/strict";
import {
  ENTITY_TABLES,
  isWritableEntityType,
} from "../_shared/entities.ts";

test("fitbit_daily_metrics is writable with exactly the migrated columns", () => {
  assert.equal(isWritableEntityType("fitbit_daily_metrics"), true);
  assert.deepEqual(
    [...ENTITY_TABLES.fitbit_daily_metrics.columns].sort(),
    ["metric_date", "raw", "resting_heart_rate", "sleep_minutes", "steps"].sort(),
  );
});

test("fitbit_hr_exercise_aggregates is writable with exactly the migrated columns", () => {
  assert.equal(isWritableEntityType("fitbit_hr_exercise_aggregates"), true);
  assert.deepEqual(
    [...ENTITY_TABLES.fitbit_hr_exercise_aggregates.columns].sort(),
    ["avg_bpm", "exercise_id", "max_bpm", "recovery_delta_bpm", "sample_count", "workout_session_id"].sort(),
  );
});

test("raw Fitbit OAuth/token tables are not part of the writable whitelist", () => {
  assert.equal(isWritableEntityType("fitbit_tokens"), false);
  assert.equal(isWritableEntityType("fitbit_oauth_states"), false);
  assert.equal(isWritableEntityType("fitbit_hr_samples"), false);
});
