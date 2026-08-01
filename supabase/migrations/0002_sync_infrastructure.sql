-- M5: shared sync infrastructure used by every syncable table and by the
-- sync-batch Edge Function.
--
-- Every syncable table gets:
--   id          uuid primary key (client-generated, per contract section 4)
--   user_id     uuid references auth.users(id)
--   version     integer, server-authoritative, increments by 1 on every update
--   sync_seq    bigint, drawn from this shared sequence -> gives a single
--               monotonic ordering across ALL tables, which is what the
--               sync-batch pull cursor is built on.
--   created_at / updated_at timestamptz
--   deleted_at  timestamptz null -> soft delete / tombstone

create sequence if not exists sync_cursor_seq;

-- Stamps sync_seq/updated_at/version on every insert or update of a syncable
-- table, and forbids re-parenting a row to another user on update.
create or replace function fittrack_sync_touch()
returns trigger
language plpgsql
as $$
begin
  if TG_OP = 'INSERT' then
    new.sync_seq := nextval('sync_cursor_seq');
    if new.created_at is null then
      new.created_at := now();
    end if;
    new.updated_at := now();
    if new.version is null or new.version < 1 then
      new.version := 1;
    end if;
    return new;
  elsif TG_OP = 'UPDATE' then
    if new.user_id is distinct from old.user_id then
      raise exception 'user_id is immutable' using errcode = '42501';
    end if;
    new.sync_seq := nextval('sync_cursor_seq');
    new.updated_at := now();
    new.created_at := old.created_at;
    new.version := old.version + 1;
    return new;
  end if;
  return new;
end;
$$;

comment on function fittrack_sync_touch() is
  'BEFORE INSERT OR UPDATE trigger shared by every syncable table. version is server-authoritative: clients send baseVersion for optimistic-concurrency checks, the server (via sync-batch) verifies it, then this trigger bumps version by exactly 1. sync_seq is the monotonic cursor value used for pull ordering.';

-- Idempotency ledger: one row per successfully-applied client operation UUID.
-- sync-batch consults this before applying anything so that retrying an
-- identical (or overlapping) batch never re-applies / duplicates work.
create table if not exists sync_operations (
  operation_id    uuid primary key,
  user_id         uuid not null references auth.users(id) on delete cascade,
  batch_id        uuid not null,
  entity_type     text not null,
  entity_id       uuid not null,
  applied_version integer not null,
  applied_at      timestamptz not null default now()
);

create index if not exists idx_sync_operations_user on sync_operations(user_id);

alter table sync_operations enable row level security;
-- Intentionally zero policies: this table is only ever touched by the
-- sync-batch Edge Function using the service_role key, which bypasses RLS.
-- No authenticated-role policy means clients get zero direct access.

-- Batch-level response cache: retrying the exact same batchId for the same
-- user returns byte-identical output without reprocessing anything, which is
-- the strongest possible guarantee against duplication on retry.
create table if not exists sync_batches (
  batch_id   uuid not null,
  user_id    uuid not null references auth.users(id) on delete cascade,
  response   jsonb not null,
  created_at timestamptz not null default now(),
  primary key (batch_id, user_id)
);

alter table sync_batches enable row level security;
-- Same rationale as sync_operations: service_role only, no client policies.
