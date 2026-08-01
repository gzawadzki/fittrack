-- Module: M6 (Fitbit backend + HR pipeline)
-- Owner: Agent 6 — exclusive migration prefix "fitbit_" to avoid collisions with M5.
-- This migration is additive-only and never alters tables owned by other modules
-- (e.g. workout_sessions, exercises, sets owned by M5/M1). Cross references to those
-- tables are stored as plain uuid columns WITHOUT foreign key constraints, because
-- migration application order between modules is not guaranteed and this module
-- must be able to run against an empty database on its own for fixture testing.
--
-- Column conventions kept consistent with M5: updated_at (timestamptz), version
-- (int, optimistic concurrency), deleted_at (timestamptz, soft delete / tombstone).

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------------
-- fitbit_tokens: one row per user. access/refresh tokens are AES-GCM encrypted
-- by the edge function (Web Crypto) BEFORE they are written here — Postgres
-- only ever stores ciphertext. The decryption key (FITBIT_TOKEN_ENC_KEY) lives
-- solely in Supabase function secrets and is never stored in the DB.
-- ---------------------------------------------------------------------------
create table if not exists fitbit_tokens (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null unique,
    access_token_ciphertext text not null,
    access_token_iv text not null,
    refresh_token_ciphertext text not null,
    refresh_token_iv text not null,
    scope text not null default '',
    fitbit_user_id text,
    access_token_expires_at timestamptz not null,
    updated_at timestamptz not null default now(),
    version int not null default 1,
    deleted_at timestamptz
);

-- ---------------------------------------------------------------------------
-- fitbit_oauth_states: short-lived CSRF state for the Authorization Code flow.
-- Rows are consumed (deleted) by fitbit-oauth-callback on first use.
-- ---------------------------------------------------------------------------
create table if not exists fitbit_oauth_states (
    state text primary key,
    user_id uuid not null,
    redirect_uri text not null,
    code_verifier text not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '10 minutes')
);

-- ---------------------------------------------------------------------------
-- fitbit_sync_cursors: per-user, per-resource sync bookkeeping so incremental
-- syncs know where to resume (e.g. last imported date per resource type).
-- ---------------------------------------------------------------------------
create table if not exists fitbit_sync_cursors (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    resource text not null check (resource in ('sleep', 'resting_hr', 'steps', 'activities')),
    last_synced_date date,
    updated_at timestamptz not null default now(),
    version int not null default 1,
    unique (user_id, resource)
);

-- ---------------------------------------------------------------------------
-- fitbit_daily_metrics: one row per user/date, holds sleep + resting HR +
-- steps. Missing values are stored as NULL, never as 0 — the plan explicitly
-- forbids representing "not yet synced" as a false zero.
-- ---------------------------------------------------------------------------
create table if not exists fitbit_daily_metrics (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    metric_date date not null,
    sleep_minutes int,
    resting_heart_rate int,
    steps int,
    raw jsonb,
    updated_at timestamptz not null default now(),
    version int not null default 1,
    deleted_at timestamptz,
    unique (user_id, metric_date)
);

-- ---------------------------------------------------------------------------
-- fitbit_activities: imported walk/run activities from the Fitbit activities
-- log. fitbit_log_id is Fitbit's own activity id, used to dedupe re-imports.
-- ---------------------------------------------------------------------------
create table if not exists fitbit_activities (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    fitbit_log_id text not null,
    activity_type text not null,
    started_at timestamptz not null,
    ended_at timestamptz,
    duration_ms bigint,
    distance_km double precision,
    calories int,
    steps int,
    raw jsonb,
    updated_at timestamptz not null default now(),
    version int not null default 1,
    deleted_at timestamptz,
    unique (user_id, fitbit_log_id)
);

-- ---------------------------------------------------------------------------
-- fitbit_workout_hr_sync: tracks the HR-import lifecycle for a single
-- workout_session (owned by M5/M1, referenced by id only, no FK). This is the
-- row whose `status` column backs the domain `fitbitSyncStatus` field
-- (NOT_LINKED -> PENDING -> SYNCED | FAILED).
-- ---------------------------------------------------------------------------
create table if not exists fitbit_workout_hr_sync (
    id uuid primary key default gen_random_uuid(),
    workout_session_id uuid not null,
    user_id uuid not null,
    window_start timestamptz not null,
    window_end timestamptz not null,
    status text not null default 'PENDING' check (status in ('PENDING', 'SYNCED', 'FAILED')),
    attempt_count int not null default 0,
    last_attempt_at timestamptz,
    next_retry_at timestamptz,
    failure_reason text,
    updated_at timestamptz not null default now(),
    version int not null default 1,
    deleted_at timestamptz,
    unique (workout_session_id)
);

-- ---------------------------------------------------------------------------
-- fitbit_hr_samples: raw 1-second-resolution heart rate samples for the
-- workout window. Purged after 90 days by a scheduled cleanup (see
-- fitbit-sync's `mode=retention_cleanup` / cron). Aggregates below survive
-- the purge.
-- ---------------------------------------------------------------------------
create table if not exists fitbit_hr_samples (
    id uuid primary key default gen_random_uuid(),
    workout_session_id uuid not null,
    user_id uuid not null,
    sample_ts timestamptz not null,
    bpm int not null,
    created_at timestamptz not null default now(),
    unique (workout_session_id, sample_ts)
);

create index if not exists idx_fitbit_hr_samples_workout on fitbit_hr_samples (workout_session_id);
create index if not exists idx_fitbit_hr_samples_created_at on fitbit_hr_samples (created_at);

-- ---------------------------------------------------------------------------
-- fitbit_hr_exercise_aggregates: computed average/max/recovery-delta per
-- exercise. Kept forever (never purged) even after raw samples are deleted.
-- A NULL here (as opposed to a row with sample_count = 0) means "no HR data
-- overlapped this exercise" and must be rendered as neutral/no-data, not 0.
-- ---------------------------------------------------------------------------
create table if not exists fitbit_hr_exercise_aggregates (
    id uuid primary key default gen_random_uuid(),
    workout_session_id uuid not null,
    exercise_id uuid not null,
    user_id uuid not null,
    avg_bpm double precision,
    max_bpm int,
    recovery_delta_bpm int,
    sample_count int not null default 0,
    computed_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version int not null default 1,
    deleted_at timestamptz,
    unique (workout_session_id, exercise_id)
);

create index if not exists idx_fitbit_activities_user on fitbit_activities (user_id, started_at);
create index if not exists idx_fitbit_daily_metrics_user on fitbit_daily_metrics (user_id, metric_date);
create index if not exists idx_fitbit_workout_hr_sync_status on fitbit_workout_hr_sync (status, next_retry_at);

-- ---------------------------------------------------------------------------
-- RLS: identical convention to M5 — auth.uid() = user_id on every table.
-- service_role (used only inside edge functions) bypasses RLS by default.
-- ---------------------------------------------------------------------------
alter table fitbit_tokens enable row level security;
alter table fitbit_sync_cursors enable row level security;
alter table fitbit_daily_metrics enable row level security;
alter table fitbit_activities enable row level security;
alter table fitbit_workout_hr_sync enable row level security;
alter table fitbit_hr_samples enable row level security;
alter table fitbit_hr_exercise_aggregates enable row level security;
-- fitbit_oauth_states intentionally has no user-facing policy: it is only
-- ever read/written by the service-role edge functions (oauth-start/callback).
alter table fitbit_oauth_states enable row level security;

create policy fitbit_tokens_owner on fitbit_tokens
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy fitbit_sync_cursors_owner on fitbit_sync_cursors
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy fitbit_daily_metrics_owner on fitbit_daily_metrics
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy fitbit_activities_owner on fitbit_activities
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy fitbit_workout_hr_sync_owner on fitbit_workout_hr_sync
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy fitbit_hr_samples_owner on fitbit_hr_samples
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy fitbit_hr_exercise_aggregates_owner on fitbit_hr_exercise_aggregates
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- No RLS policy for fitbit_oauth_states -> default deny for anon/authenticated,
-- service_role still has full access from within edge functions.

-- ---------------------------------------------------------------------------
-- Retention: delete raw HR samples older than 90 days. Exposed as a plain
-- function so it can be invoked by pg_cron (if enabled) or by the
-- fitbit-sync edge function's `mode=retention_cleanup`.
-- ---------------------------------------------------------------------------
create or replace function fitbit_purge_expired_hr_samples()
returns bigint
language plpgsql
security definer
as $$
declare
    deleted_count bigint;
begin
    delete from fitbit_hr_samples
    where created_at < now() - interval '90 days';
    get diagnostics deleted_count = row_count;
    return deleted_count;
end;
$$;
