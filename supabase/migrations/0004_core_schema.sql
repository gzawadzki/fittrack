-- M5: relational schema for the domain types frozen in
-- IMPLEMENTATION_PLAN.md section 4 (UserProfile, WorkoutSession + plan
-- snapshot + sets, CoachSuggestion). Fitbit/HR tables (health_daily,
-- cardio_activities, heart_rate_samples, exercise_hr_summary, fitbit
-- connection/token tables) are intentionally NOT created here -- they are
-- owned by M6. Table/column naming below mirrors the Room entities in
-- app/src/main/java/com/fittrack/app/data/local/Entities.kt so M4's mappers
-- have a predictable 1:1 surface to target.
--
-- Every table shares this shape:
--   id          uuid primary key
--   user_id     uuid not null references auth.users(id)
--   version     integer not null default 1        (server-authoritative)
--   sync_seq    bigint not null default 0          (pull cursor, see 0002)
--   created_at  timestamptz not null default now()
--   updated_at  timestamptz not null default now()
--   deleted_at  timestamptz null                   (soft delete / tombstone)
--
-- RLS: every table is owner-scoped via `auth.uid() = user_id`. There is no
-- DELETE policy anywhere -- rows are only ever soft-deleted via UPDATE
-- (setting deleted_at), which keeps tombstones syncable.

-- ---------------------------------------------------------------------
-- user_profiles
-- ---------------------------------------------------------------------
create table if not exists user_profiles (
  id           uuid primary key,
  user_id      uuid not null references auth.users(id) on delete cascade,
  goal         text not null,
  experience   text not null,
  equipment    jsonb not null default '[]'::jsonb,   -- Set<String>
  schedule     text not null,
  constraints  text not null,
  preferences  text not null,
  approved     boolean not null default false,
  version      integer not null default 1,
  sync_seq     bigint not null default 0,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  deleted_at   timestamptz,
  unique (user_id)
);

create index if not exists idx_user_profiles_sync on user_profiles(user_id, sync_seq);

drop trigger if exists trg_touch_user_profiles on user_profiles;
create trigger trg_touch_user_profiles
  before insert or update on user_profiles
  for each row execute function fittrack_sync_touch();

alter table user_profiles enable row level security;
create policy user_profiles_owner_select on user_profiles for select to authenticated using (auth.uid() = user_id);
create policy user_profiles_owner_insert on user_profiles for insert to authenticated with check (auth.uid() = user_id);
create policy user_profiles_owner_update on user_profiles for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------
-- exercises (per-user exercise library / catalog)
-- ---------------------------------------------------------------------
create table if not exists exercises (
  id                uuid primary key,
  user_id           uuid not null references auth.users(id) on delete cascade,
  name              text not null,
  movement_pattern  text not null,
  muscle_role       text not null,
  equipment         text not null,
  active            boolean not null default true,
  version           integer not null default 1,
  sync_seq          bigint not null default 0,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  deleted_at        timestamptz
);

create index if not exists idx_exercises_sync on exercises(user_id, sync_seq);
create index if not exists idx_exercises_pattern on exercises(user_id, movement_pattern);

drop trigger if exists trg_touch_exercises on exercises;
create trigger trg_touch_exercises
  before insert or update on exercises
  for each row execute function fittrack_sync_touch();

alter table exercises enable row level security;
create policy exercises_owner_select on exercises for select to authenticated using (auth.uid() = user_id);
create policy exercises_owner_insert on exercises for insert to authenticated with check (auth.uid() = user_id);
create policy exercises_owner_update on exercises for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------
-- workout_plans
-- ---------------------------------------------------------------------
create table if not exists workout_plans (
  id                        uuid primary key,
  user_id                   uuid not null references auth.users(id) on delete cascade,
  name                      text not null,
  day_label                 text not null,
  scheduled_day_of_week     smallint,
  scheduled_minute_of_day   smallint,
  active                    boolean not null default true,
  version                   integer not null default 1,
  sync_seq                  bigint not null default 0,
  created_at                timestamptz not null default now(),
  updated_at                timestamptz not null default now(),
  deleted_at                timestamptz
);

create index if not exists idx_workout_plans_sync on workout_plans(user_id, sync_seq);

drop trigger if exists trg_touch_workout_plans on workout_plans;
create trigger trg_touch_workout_plans
  before insert or update on workout_plans
  for each row execute function fittrack_sync_touch();

alter table workout_plans enable row level security;
create policy workout_plans_owner_select on workout_plans for select to authenticated using (auth.uid() = user_id);
create policy workout_plans_owner_insert on workout_plans for insert to authenticated with check (auth.uid() = user_id);
create policy workout_plans_owner_update on workout_plans for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------
-- plan_exercises
-- ---------------------------------------------------------------------
create table if not exists plan_exercises (
  id                   uuid primary key,
  user_id              uuid not null references auth.users(id) on delete cascade,
  plan_id              uuid not null references workout_plans(id) on delete cascade,
  exercise_id          uuid not null references exercises(id),
  position             integer not null,
  warmup_sets          integer not null default 0,
  working_sets         integer not null default 0,
  rep_min              integer not null,
  rep_max              integer not null,
  target_rir           integer,
  starting_weight_kg   numeric(6,2) not null,
  rest_seconds         integer not null,
  weight_increment_kg  numeric(6,2) not null,
  notes                text not null default '',
  version              integer not null default 1,
  sync_seq             bigint not null default 0,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now(),
  deleted_at           timestamptz
);

create index if not exists idx_plan_exercises_sync on plan_exercises(user_id, sync_seq);
create index if not exists idx_plan_exercises_plan on plan_exercises(plan_id);

drop trigger if exists trg_touch_plan_exercises on plan_exercises;
create trigger trg_touch_plan_exercises
  before insert or update on plan_exercises
  for each row execute function fittrack_sync_touch();

alter table plan_exercises enable row level security;
create policy plan_exercises_owner_select on plan_exercises for select to authenticated using (auth.uid() = user_id);
create policy plan_exercises_owner_insert on plan_exercises for insert to authenticated with check (auth.uid() = user_id);
create policy plan_exercises_owner_update on plan_exercises for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------
-- workout_sessions
-- ---------------------------------------------------------------------
create table if not exists workout_sessions (
  id                     uuid primary key,
  user_id                uuid not null references auth.users(id) on delete cascade,
  plan_id                uuid not null references workout_plans(id),
  plan_name_snapshot     text not null,
  plan_version_snapshot  integer not null,
  status                 text not null,        -- e.g. IN_PROGRESS | COMPLETED | ABANDONED (owned by core/model, not constrained here)
  started_at             timestamptz not null,
  ended_at               timestamptz,
  note                   text not null default '',
  fitbit_sync_status     text not null default 'NOT_REQUESTED',
  version                integer not null default 1,
  sync_seq               bigint not null default 0,
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now(),
  deleted_at             timestamptz
);

create index if not exists idx_workout_sessions_sync on workout_sessions(user_id, sync_seq);
create index if not exists idx_workout_sessions_status on workout_sessions(user_id, status);

drop trigger if exists trg_touch_workout_sessions on workout_sessions;
create trigger trg_touch_workout_sessions
  before insert or update on workout_sessions
  for each row execute function fittrack_sync_touch();

alter table workout_sessions enable row level security;
create policy workout_sessions_owner_select on workout_sessions for select to authenticated using (auth.uid() = user_id);
create policy workout_sessions_owner_insert on workout_sessions for insert to authenticated with check (auth.uid() = user_id);
create policy workout_sessions_owner_update on workout_sessions for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------
-- exercise_sessions
-- ---------------------------------------------------------------------
create table if not exists exercise_sessions (
  id                      uuid primary key,
  user_id                 uuid not null references auth.users(id) on delete cascade,
  workout_session_id      uuid not null references workout_sessions(id) on delete cascade,
  plan_exercise_id        uuid not null references plan_exercises(id),
  exercise_id             uuid not null references exercises(id),
  original_exercise_id    uuid not null references exercises(id),
  exercise_name_snapshot  text not null,
  position                integer not null,
  rest_seconds_snapshot   integer not null,
  status                  text not null default 'PENDING',
  started_at              timestamptz,
  ended_at                timestamptz,
  skipped_at              timestamptz,
  skip_reason             text not null default '',
  note                    text not null default '',
  version                 integer not null default 1,
  sync_seq                bigint not null default 0,
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),
  deleted_at              timestamptz
);

create index if not exists idx_exercise_sessions_sync on exercise_sessions(user_id, sync_seq);
create index if not exists idx_exercise_sessions_session on exercise_sessions(workout_session_id);

drop trigger if exists trg_touch_exercise_sessions on exercise_sessions;
create trigger trg_touch_exercise_sessions
  before insert or update on exercise_sessions
  for each row execute function fittrack_sync_touch();

alter table exercise_sessions enable row level security;
create policy exercise_sessions_owner_select on exercise_sessions for select to authenticated using (auth.uid() = user_id);
create policy exercise_sessions_owner_insert on exercise_sessions for insert to authenticated with check (auth.uid() = user_id);
create policy exercise_sessions_owner_update on exercise_sessions for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------
-- set_logs
-- ---------------------------------------------------------------------
create table if not exists set_logs (
  id                    uuid primary key,
  user_id               uuid not null references auth.users(id) on delete cascade,
  exercise_session_id   uuid not null references exercise_sessions(id) on delete cascade,
  position              integer not null,
  set_type              text not null,
  target_rep_min        integer not null,
  target_rep_max        integer not null,
  target_rir            integer,
  target_weight_kg      numeric(6,2) not null,
  actual_reps           integer,
  actual_weight_kg      numeric(6,2),
  actual_rir            integer,
  pain                  boolean not null default false,
  technique_issue       boolean not null default false,
  concerning_symptom    boolean not null default false,
  completed_at          timestamptz,
  version               integer not null default 1,
  sync_seq              bigint not null default 0,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now(),
  deleted_at            timestamptz
);

create index if not exists idx_set_logs_sync on set_logs(user_id, sync_seq);
create index if not exists idx_set_logs_session on set_logs(exercise_session_id);

drop trigger if exists trg_touch_set_logs on set_logs;
create trigger trg_touch_set_logs
  before insert or update on set_logs
  for each row execute function fittrack_sync_touch();

alter table set_logs enable row level security;
create policy set_logs_owner_select on set_logs for select to authenticated using (auth.uid() = user_id);
create policy set_logs_owner_insert on set_logs for insert to authenticated with check (auth.uid() = user_id);
create policy set_logs_owner_update on set_logs for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------
-- coach_suggestions
-- ---------------------------------------------------------------------
-- Written server-side only: `coach` (M7) INSERTs as PENDING, and
-- `suggestion-decision` (this module) is the ONLY thing that ever
-- transitions status away from PENDING. Both use the service_role key,
-- which bypasses RLS. There are deliberately no INSERT/UPDATE policies for
-- the `authenticated` role below, so even a client with direct PostgREST
-- access cannot create or mutate a suggestion -- only read its own. This is
-- the DB-level backstop for the business rule "AI suggestions never
-- auto-apply; only an explicit ACCEPTED decision changes plan state."
create table if not exists coach_suggestions (
  id               uuid primary key,
  user_id          uuid not null references auth.users(id) on delete cascade,
  type             text not null check (type in ('LOAD','VOLUME','SWAP','RESCHEDULE','HOLD')),
  target_id        uuid not null,
  before_value     text not null,
  after_value      text not null,
  reason_code      text not null,
  explanation      text not null,
  signals          jsonb not null default '[]'::jsonb,
  citations        jsonb not null default '[]'::jsonb,
  confidence       text not null check (confidence in ('LOW','MEDIUM','HIGH')),
  status           text not null default 'PENDING' check (status in ('PENDING','ACCEPTED','REJECTED')),
  decision_reason  text not null default '',
  decided_at       timestamptz,
  version          integer not null default 1,
  sync_seq         bigint not null default 0,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  deleted_at       timestamptz
);

create index if not exists idx_coach_suggestions_sync on coach_suggestions(user_id, sync_seq);
create index if not exists idx_coach_suggestions_status on coach_suggestions(user_id, status);
create index if not exists idx_coach_suggestions_target on coach_suggestions(target_id);

drop trigger if exists trg_touch_coach_suggestions on coach_suggestions;
create trigger trg_touch_coach_suggestions
  before insert or update on coach_suggestions
  for each row execute function fittrack_sync_touch();

alter table coach_suggestions enable row level security;
create policy coach_suggestions_owner_select on coach_suggestions for select to authenticated using (auth.uid() = user_id);
-- No insert/update policy for `authenticated` on purpose (see comment above).
