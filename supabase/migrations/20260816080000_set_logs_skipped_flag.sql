-- Adds a per-set "skipped" flag so the client can record a planned set as
-- deliberately not performed (e.g. not enough strength left for it) instead
-- of forcing a numeric log. A skipped set still gets completed_at (it's
-- "resolved" for the exercise-finish gate) but actual_reps/actual_weight_kg
-- stay null, so it's already excluded from progression the same way any
-- other null-actual_reps set is.
ALTER TABLE set_logs ADD COLUMN skipped boolean NOT NULL DEFAULT false;
