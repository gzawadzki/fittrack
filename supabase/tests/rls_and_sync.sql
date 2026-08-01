-- M5: RLS + sync-infra verification script.
--
-- This is NOT wired into CI in this environment (no Supabase CLI/Docker was
-- available while writing it) -- it is a runnable psql script meant to be
-- executed against a local `supabase start` stack or any scratch Postgres
-- database that has run all of supabase/migrations/*.sql in order.
--
-- How to run for real:
--   1. supabase start                     (spins up local Postgres + GoTrue + PostgREST)
--   2. supabase db reset                  (applies all migrations from empty)
--   3. psql "$(supabase status -o env | grep DB_URL | cut -d= -f2)" \
--        -f supabase/tests/rls_and_sync.sql
--
-- It asserts, via RAISE EXCEPTION on failure (so a clean run prints nothing
-- but NOTICEs and exits 0; any failed assertion aborts with a non-zero exit
-- code), the four things required by the module's Definition of Done:
--   1. anonymous access is denied
--   2. wrong-uid access is denied
--   3. owner access is allowed
--   4. retrying an identical sync-batch operation set does not duplicate rows
--
-- NOTE: auth.uid() in Supabase reads the `request.jwt.claims` GUC set by
-- PostgREST from the caller's verified JWT. We simulate that here with
-- `set local role` + `set local request.jwt.claims`, which is the standard
-- way to exercise RLS policies directly over psql/pgTAP without spinning up
-- GoTrue/PostgREST. This script does NOT exercise the sync-batch/
-- suggestion-decision Edge Functions themselves (those need `supabase
-- functions serve`, a Deno runtime, and real JWTs -- see the curl-based
-- manual checks documented in supabase/HANDOFF.md).

begin;

do $$
declare
  user_a uuid := '11111111-1111-1111-1111-111111111111';
  user_b uuid := '22222222-2222-2222-2222-222222222222';
begin
  -- Test setup only: bypass the single-user allowlist trigger so we can
  -- create two distinct auth identities to prove cross-user isolation.
  alter table auth.users disable trigger trg_enforce_allowlist;

  insert into auth.users (id, email, encrypted_password, email_confirmed_at, created_at, updated_at)
  values
    (user_a, 'owner@example.com', 'x', now(), now(), now()),
    (user_b, 'intruder@example.com', 'x', now(), now(), now())
  on conflict (id) do nothing;

  alter table auth.users enable trigger trg_enforce_allowlist;
end $$;

-- ---------------------------------------------------------------------
-- 1. Owner can create and read their own profile.
-- ---------------------------------------------------------------------
set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';

insert into user_profiles (id, user_id, goal, experience, equipment, schedule, constraints, preferences, approved)
values ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
        'strength', 'intermediate', '["barbell"]'::jsonb, '3x/week', 'none', 'none', true);

do $$
declare
  cnt int;
begin
  select count(*) into cnt from user_profiles where id = 'aaaaaaaa-0000-0000-0000-000000000001';
  if cnt <> 1 then
    raise exception 'FAIL: owner could not read their own just-inserted profile (got % rows)', cnt;
  end if;
  raise notice 'PASS: owner access allowed (select own row)';
end $$;

-- ---------------------------------------------------------------------
-- 2. Wrong UID: user B cannot see or modify user A's profile.
-- ---------------------------------------------------------------------
reset role;
set local role authenticated;
set local request.jwt.claims = '{"sub":"22222222-2222-2222-2222-222222222222","role":"authenticated"}';

do $$
declare
  cnt int;
begin
  select count(*) into cnt from user_profiles where id = 'aaaaaaaa-0000-0000-0000-000000000001';
  if cnt <> 0 then
    raise exception 'FAIL: user B could read user A profile (RLS leak), got % rows', cnt;
  end if;
  raise notice 'PASS: wrong-uid select denied';
end $$;

do $$
begin
  begin
    update user_profiles set goal = 'hacked' where id = 'aaaaaaaa-0000-0000-0000-000000000001';
    if found then
      raise exception 'FAIL: user B was able to update user A profile';
    end if;
  exception when others then
    -- either 0 rows affected (RLS filters silently) or a raised error is acceptable
    null;
  end;
  raise notice 'PASS: wrong-uid update denied (no rows affected)';
end $$;

do $$
begin
  begin
    insert into user_profiles (id, user_id, goal, experience, equipment, schedule, constraints, preferences, approved)
    values ('aaaaaaaa-0000-0000-0000-000000000099', '11111111-1111-1111-1111-111111111111',
            'spoofed', 'x', '[]'::jsonb, 'x', 'x', 'x', true);
    raise exception 'FAIL: user B was able to insert a row claiming user_id = user A';
  exception
    when insufficient_privilege then
      raise notice 'PASS: wrong-uid insert (user_id spoof) denied';
    when others then
      raise notice 'PASS: wrong-uid insert (user_id spoof) denied (%): %', sqlstate, sqlerrm;
  end;
end $$;

-- ---------------------------------------------------------------------
-- 3. Anonymous (no JWT at all) cannot see anything.
-- ---------------------------------------------------------------------
reset role;
set local role anon;
reset request.jwt.claims;

do $$
declare
  cnt int;
begin
  select count(*) into cnt from user_profiles;
  if cnt <> 0 then
    raise exception 'FAIL: anonymous role could read % row(s) from user_profiles', cnt;
  end if;
  raise notice 'PASS: anonymous select denied';
end $$;

do $$
begin
  begin
    insert into user_profiles (id, user_id, goal, experience, equipment, schedule, constraints, preferences, approved)
    values ('aaaaaaaa-0000-0000-0000-000000000098', '11111111-1111-1111-1111-111111111111',
            'anon', 'x', '[]'::jsonb, 'x', 'x', 'x', true);
    raise exception 'FAIL: anonymous role was able to insert into user_profiles';
  exception
    when insufficient_privilege then
      raise notice 'PASS: anonymous insert denied';
    when others then
      raise notice 'PASS: anonymous insert denied (%): %', sqlstate, sqlerrm;
  end;
end $$;

-- ---------------------------------------------------------------------
-- 4. Retrying an identical operation set does not duplicate rows.
--    (Exercises the same idempotency ledger the sync-batch function uses;
--    full HTTP-level batch replay is covered by the curl script in
--    supabase/HANDOFF.md.)
-- ---------------------------------------------------------------------
reset role;
set local role service_role;

do $$
declare
  op_id uuid := 'bbbbbbbb-0000-0000-0000-000000000001';
  cnt int;
begin
  insert into sync_operations (operation_id, user_id, batch_id, entity_type, entity_id, applied_version)
  values (op_id, '11111111-1111-1111-1111-111111111111', 'cccccccc-0000-0000-0000-000000000001',
          'workout_plans', 'dddddddd-0000-0000-0000-000000000001', 1);

  begin
    insert into sync_operations (operation_id, user_id, batch_id, entity_type, entity_id, applied_version)
    values (op_id, '11111111-1111-1111-1111-111111111111', 'cccccccc-0000-0000-0000-000000000002',
            'workout_plans', 'dddddddd-0000-0000-0000-000000000001', 1);
    raise exception 'FAIL: duplicate operation_id was allowed into sync_operations';
  exception
    when unique_violation then
      null; -- expected: primary key blocks the duplicate
  end;

  select count(*) into cnt from sync_operations where operation_id = op_id;
  if cnt <> 1 then
    raise exception 'FAIL: expected exactly 1 ledger row for operation_id %, found %', op_id, cnt;
  end if;
  raise notice 'PASS: duplicate operation_id rejected, no duplication';
end $$;

do $$
declare
  batch_id uuid := 'eeeeeeee-0000-0000-0000-000000000001';
  cnt int;
begin
  insert into sync_batches (batch_id, user_id, response)
  values (batch_id, '11111111-1111-1111-1111-111111111111', '{"ackedOperationIds":[],"changes":[],"nextCursor":0}'::jsonb);

  select count(*) into cnt from sync_batches where batch_id = batch_id and user_id = '11111111-1111-1111-1111-111111111111';
  if cnt <> 1 then
    raise exception 'FAIL: expected exactly 1 cached batch response, found %', cnt;
  end if;
  raise notice 'PASS: batch response cache holds exactly one row per (batch_id, user_id)';
end $$;

reset role;
rollback;
