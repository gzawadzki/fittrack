-- Enforce the cross-device invariant used by the Android guided-workout flow:
-- one non-deleted ACTIVE workout per user.
--
-- Repair legacy duplicates before creating the partial unique index. The most
-- recently started row wins; updated_at and id make the choice deterministic.

with ranked as (
  select
    id,
    row_number() over (
      partition by user_id
      order by started_at desc, updated_at desc, id desc
    ) as active_rank
  from workout_sessions
  where status = 'ACTIVE' and deleted_at is null
), losers as (
  select id from ranked where active_rank > 1
)
update exercise_sessions es
set
  status = case when es.status in ('ACTIVE', 'PENDING') then 'SKIPPED' else es.status end,
  ended_at = case
    when es.status in ('ACTIVE', 'PENDING') then coalesce(es.ended_at, es.updated_at, es.started_at, now())
    else es.ended_at
  end,
  skipped_at = case
    when es.status in ('ACTIVE', 'PENDING') then coalesce(es.skipped_at, es.updated_at, es.started_at, now())
    else es.skipped_at
  end,
  skip_reason = case
    when es.status in ('ACTIVE', 'PENDING') and es.skip_reason = ''
      then 'Another active workout won during server reconciliation'
    else es.skip_reason
  end
where es.workout_session_id in (select id from losers);

with ranked as (
  select
    id,
    row_number() over (
      partition by user_id
      order by started_at desc, updated_at desc, id desc
    ) as active_rank
  from workout_sessions
  where status = 'ACTIVE' and deleted_at is null
)
update workout_sessions ws
set
  status = 'CANCELLED',
  ended_at = coalesce(ws.ended_at, ws.updated_at, ws.started_at)
from ranked
where ws.id = ranked.id and ranked.active_rank > 1;

create unique index if not exists uq_workout_sessions_one_active_per_user
  on workout_sessions(user_id)
  where status = 'ACTIVE' and deleted_at is null;

