-- Preserve the Android domain's immutable workout snapshot across devices.
-- Existing rows are backfilled from the best information still available in
-- their referenced plan; all new rows send the exact start-time snapshot.

alter table user_profiles
  add column if not exists profile_version integer not null default 1;

alter table workout_plans
  add column if not exists plan_version integer not null default 1;

alter table workout_sessions
  add column if not exists day_label_snapshot text,
  add column if not exists plan_snapshot_json jsonb;

update workout_sessions ws
set
  day_label_snapshot = coalesce(ws.day_label_snapshot, wp.day_label, ''),
  plan_snapshot_json = coalesce(
    ws.plan_snapshot_json,
    jsonb_build_object(
      'planId', ws.plan_id,
      'planName', ws.plan_name_snapshot,
      'planVersion', ws.plan_version_snapshot,
      'dayLabel', coalesce(wp.day_label, ''),
      'exercises', coalesce(
        (
          select jsonb_agg(
            jsonb_build_object(
              'planExerciseId', pe.id,
              'exerciseId', pe.exercise_id,
              'exerciseName', e.name,
              'position', pe.position,
              'restSeconds', pe.rest_seconds,
              'plannedSets',
                coalesce(
                  (
                    select jsonb_agg(set_row.value order by set_row.position)
                    from (
                      select
                        warmup.position,
                        jsonb_build_object(
                          'type', 'WARMUP',
                          'repMin', pe.rep_min,
                          'repMax', pe.rep_max,
                          'targetRir', pe.target_rir,
                          'targetWeightKg', pe.starting_weight_kg * 0.5
                        ) as value
                      from generate_series(0, pe.warmup_sets - 1) warmup(position)
                      union all
                      select
                        pe.warmup_sets + working.position,
                        jsonb_build_object(
                          'type', 'WORKING',
                          'repMin', pe.rep_min,
                          'repMax', pe.rep_max,
                          'targetRir', pe.target_rir,
                          'targetWeightKg', pe.starting_weight_kg
                        ) as value
                      from generate_series(0, pe.working_sets - 1) working(position)
                    ) set_row
                  ),
                  '[]'::jsonb
                )
            )
            order by pe.position
          )
          from plan_exercises pe
          join exercises e on e.id = pe.exercise_id
          where pe.plan_id = ws.plan_id and pe.deleted_at is null
        ),
        '[]'::jsonb
      )
    )
  )
from workout_plans wp
where wp.id = ws.plan_id
  and (ws.day_label_snapshot is null or ws.plan_snapshot_json is null);

-- Rows whose referenced plan disappeared still receive a structurally valid
-- snapshot instead of an invented exercise prescription.
update workout_sessions ws
set
  day_label_snapshot = coalesce(ws.day_label_snapshot, ''),
  plan_snapshot_json = coalesce(
    ws.plan_snapshot_json,
    jsonb_build_object(
      'planId', ws.plan_id,
      'planName', ws.plan_name_snapshot,
      'planVersion', ws.plan_version_snapshot,
      'dayLabel', coalesce(ws.day_label_snapshot, ''),
      'exercises', '[]'::jsonb
    )
  )
where ws.day_label_snapshot is null or ws.plan_snapshot_json is null;

alter table workout_sessions
  alter column day_label_snapshot set not null,
  alter column plan_snapshot_json set not null;

comment on column workout_sessions.plan_snapshot_json is
  'Immutable Android WorkoutPlanSnapshot captured when the session starts; never regenerated on normal writes.';
