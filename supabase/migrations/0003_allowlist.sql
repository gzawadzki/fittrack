-- M5: single-user allowlist. Public signup must be disabled at the project
-- level too (Studio -> Authentication -> Providers -> Email -> "Allow new
-- users to sign up" OFF, or `enable_signup = false` under [auth] in the
-- project's supabase/config.toml, owned by the integrator). This migration
-- is defense-in-depth at the database layer: even if signup were ever left
-- on by mistake, auth.users inserts are rejected unless they match the
-- single allowed identity configured here.
--
-- The allowed identity is NOT hardcoded in this migration. It is seeded by
-- running supabase/functions/_shared/allowlist_seed.sql once after deploy,
-- with the email/uid supplied as psql variables sourced from a Supabase
-- secret / local env var -- never committed to the repo. See
-- supabase/HANDOFF.md for the exact command.

create table if not exists app_allowlist (
  singleton     boolean primary key default true,
  allowed_email text not null,
  allowed_uid   uuid,
  updated_at    timestamptz not null default now(),
  constraint app_allowlist_is_singleton check (singleton)
);

alter table app_allowlist enable row level security;
-- No policies: only service_role (migrations, seed script, Edge Functions)
-- can read or write this table. It is never queried on behalf of a client.

create or replace function fittrack_enforce_allowlist()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_allowed_email text;
  v_allowed_uid   uuid;
begin
  select allowed_email, allowed_uid
    into v_allowed_email, v_allowed_uid
    from public.app_allowlist
   where singleton = true;

  if v_allowed_email is null then
    raise exception 'Signup disabled: allowlist is not configured' using errcode = '42501';
  end if;

  if lower(new.email) is distinct from lower(v_allowed_email)
     and (v_allowed_uid is null or new.id is distinct from v_allowed_uid) then
    raise exception 'Signup disabled: % is not on the allowlist', new.email using errcode = '42501';
  end if;

  return new;
end;
$$;

drop trigger if exists trg_enforce_allowlist on auth.users;
create trigger trg_enforce_allowlist
  before insert on auth.users
  for each row execute function fittrack_enforce_allowlist();
