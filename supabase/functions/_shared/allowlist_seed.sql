-- M5: one-time seed for the single allowed user. This file is NOT a
-- migration and is never run automatically -- it takes secrets as psql
-- variables so nothing sensitive is committed to the repo.
--
-- Run once after the project is deployed and the one allowed user has been
-- decided on (email required; uid optional and can be filled in after the
-- user's first successful sign-in, since Supabase assigns the uid at
-- creation time):
--
--   psql "$SUPABASE_DB_URL" \
--     -v allowed_email="'owner@example.com'" \
--     -v allowed_uid="null" \
--     -f supabase/functions/_shared/allowlist_seed.sql
--
-- After the owner's auth.users row exists (e.g. created via the Supabase
-- Studio "Add user" screen, or their first invite-accept), re-run with:
--
--   -v allowed_uid="'11111111-2222-3333-4444-555555555555'"
--
-- to pin the allowlist to that exact uid as well (defense in depth beyond
-- email matching).

insert into app_allowlist (singleton, allowed_email, allowed_uid, updated_at)
values (true, :allowed_email, :allowed_uid, now())
on conflict (singleton)
do update set
  allowed_email = excluded.allowed_email,
  allowed_uid   = excluded.allowed_uid,
  updated_at    = now();
