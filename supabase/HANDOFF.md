# M5 handoff — Supabase schema, RLS, sync

Owner: Agent 5. Paths owned: `supabase/migrations/`, `supabase/functions/_shared/`,
`supabase/functions/sync-batch/`, `supabase/functions/suggestion-decision/`.

## What works

- **Schema** (`supabase/migrations/0001`–`0004`): every syncable table has
  `id uuid pk`, `user_id uuid`, `version int` (server-authoritative, +1 per
  update), `sync_seq bigint` (drawn from a shared sequence — a single
  monotonic cursor across *all* tables), `created_at`/`updated_at`, and
  `deleted_at` (soft delete/tombstone). Tables: `user_profiles`, `exercises`,
  `workout_plans`, `plan_exercises`, `workout_sessions`, `exercise_sessions`,
  `set_logs`, `coach_suggestions`. Column names mirror the Room entities in
  `app/src/main/java/com/fittrack/app/data/local/Entities.kt` so M4's mapper
  layer has a predictable target.
- **Fitbit/HR tables are intentionally NOT created here** (`health_daily`,
  `cardio_activities`, `heart_rate_samples`, `exercise_hr_summary`, any
  Fitbit token/connection table) — that's M6's territory. Naming convention
  to follow: snake_case table names, same 7-column syncable-row pattern
  (`id/user_id/version/sync_seq/created_at/updated_at/deleted_at`), migration
  filenames prefixed e.g. `0005_fitbit_*.sql` so ordering stays obvious.
- **RLS**: every table has `auth.uid() = user_id` policies for
  select/insert/update, `authenticated` role only. No delete policies
  anywhere — deletes are always a soft-delete `UPDATE deleted_at = now()`.
  `coach_suggestions` has a select policy only — clients can read their own
  suggestions but can never insert/update one directly; only the
  `service_role` (used inside `coach` and `suggestion-decision`) can write
  it, which is the DB-level backstop for "suggestions never auto-apply."
- **Allowlist / no public signup**: `app_allowlist` is a singleton table
  (service_role only, no client policies) holding one email + optional uid.
  A `before insert on auth.users` trigger (`fittrack_enforce_allowlist`)
  rejects any signup that doesn't match it. This is defense-in-depth — the
  primary switch is still the Supabase Auth "allow signups" setting (see
  Deploy checklist below), but even if that's left on by mistake, the DB
  refuses the insert.
- **sync-batch** (`supabase/functions/sync-batch/index.ts`): validates the
  whole batch against a column whitelist (`_shared/entities.ts`) before
  writing anything, dedupes by `operationId` via a `sync_operations` ledger,
  and caches the full response per `batchId` in `sync_batches` so retrying
  the exact same batch returns the byte-identical response with zero writes.
  Version conflicts on UPDATE/DELETE are detected by comparing the client's
  `baseVersion` to the row's current `version`.
- **suggestion-decision** (`supabase/functions/suggestion-decision/index.ts`):
  only `decision: "ACCEPTED"` mutates the target row (`plan_exercises` for
  LOAD/VOLUME/SWAP, `workout_plans` for RESCHEDULE, no target for HOLD).
  `REJECTED` only closes the suggestion. Re-deciding with the same decision
  is idempotent; re-deciding with a different decision is rejected
  (`ALREADY_DECIDED`).
- **Common error shape** (`supabase/functions/_shared/errors.ts`) used by
  both functions: `{"error":{"code","message","retryable","retryAfterSeconds"}}`.
- **service_role usage**: confined to `_shared/supabaseAdmin.ts`, constructed
  once per invocation, never logged, never included in any response body.

## Request/response contracts for other agents to code against

### `POST /sync-batch` (JWT required)

Request:
```json
{
  "batchId": "uuid",
  "cursor": 0,
  "operations": [
    {
      "operationId": "uuid",
      "entityType": "workout_sessions",
      "entityId": "uuid",
      "op": "CREATE",
      "baseVersion": null,
      "data": { "planId": "...", "status": "IN_PROGRESS", "...": "..." }
    }
  ]
}
```
- `entityType` is one of: `user_profiles`, `exercises`, `workout_plans`,
  `plan_exercises`, `workout_sessions`, `exercise_sessions`, `set_logs`.
  (`coach_suggestions` is pull-only — do not send operations for it.)
- `data` keys are **camelCase in this doc for readability but the actual
  wire format the function expects is the exact snake_case column name**
  (`plan_id`, `starting_weight_kg`, etc — see `_shared/entities.ts` for the
  authoritative per-entity whitelist). M4, please confirm/align on this;
  if you'd rather send camelCase over the wire, ping M5 for a one-line ADR
  and I'll add a mapping layer — right now the function does a direct
  passthrough to Postgres column names for simplicity and to avoid an extra
  translation surface that could silently drop fields.
- `op: "CREATE"` requires `baseVersion: null`. `op: "UPDATE"` / `"DELETE"`
  require `baseVersion` = the version you last observed for that row.

Response:
```json
{
  "ackedOperationIds": ["uuid", "..."],
  "changes": [
    {
      "entityType": "workout_sessions",
      "id": "uuid",
      "version": 4,
      "updatedAt": "2026-08-01T12:00:00.000Z",
      "deletedAt": null,
      "data": { "id": "uuid", "plan_id": "...", "status": "...", "version": 4, "...": "..." }
    }
  ],
  "nextCursor": 128
}
```
- `changes` is every row across every syncable table with `sync_seq > cursor`
  for the caller's user, capped at 500 rows, ordered by the global cursor.
  If you get exactly 500 changes back, call again with `operations: []` and
  `cursor: nextCursor` to keep draining the backlog.
- Retrying the same `batchId` (even with network-timeout uncertainty) is
  always safe — you get back the exact same response, no duplicate writes.
- Errors use the common shape. Notable codes: `VALIDATION_FAILED` (400,
  bad/forbidden field), `VERSION_CONFLICT` (409, retryable — re-pull first),
  `NOT_FOUND` (404, entityId doesn't exist / not yours), `FORBIDDEN` (403).

### `POST /suggestion-decision` (JWT required)

Request:
```json
{ "suggestionId": "uuid", "decision": "ACCEPTED", "reason": "optional text" }
```

Response:
```json
{
  "suggestion": {
    "id": "uuid", "type": "LOAD", "targetId": "uuid",
    "before": "...", "after": "...", "reasonCode": "...", "explanation": "...",
    "signals": [], "citations": [], "confidence": "MEDIUM",
    "status": "ACCEPTED", "decisionReason": "optional text"
  },
  "target": { "entityType": "plan_exercises", "id": "uuid", "version": 6 }
}
```
- **For M7 (coach function, which creates suggestions):** `after` (and
  `before`) must be a JSON-encoded string (per the frozen domain type,
  `CoachSuggestion.after: String`) whose parsed object uses snake_case
  Postgres column names restricted to this per-type whitelist:
  - `LOAD` → `plan_exercises`: `starting_weight_kg`
  - `VOLUME` → `plan_exercises`: `working_sets`, `warmup_sets`, `rep_min`, `rep_max`, `target_rir`
  - `SWAP` → `plan_exercises`: `exercise_id`
  - `RESCHEDULE` → `workout_plans`: `scheduled_day_of_week`, `scheduled_minute_of_day`
  - `HOLD` → no target mutation ever (any `after` content is ignored)

  Example `after` for a LOAD suggestion: `"{\"starting_weight_kg\": 62.5}"`.
  `targetId` must be the `id` of the `plan_exercises` (or `workout_plans` for
  RESCHEDULE) row being proposed for change, owned by the same user.
- Errors: `NOT_FOUND` (404), `ALREADY_DECIDED` (409, not retryable),
  `VALIDATION_FAILED` (400, e.g. forbidden field in `after`).

## How to run/test locally (Supabase CLI)

This environment did not have the Supabase CLI, Docker, or a Postgres
instance available, so none of the below was actually executed — the SQL
and TypeScript are correct to the best of manual review and match Supabase's
documented conventions, but treat "migrations apply cleanly" and "functions
behave as described" as **not yet verified end-to-end**. Please run this
before relying on it:

```bash
supabase start                 # local Postgres + GoTrue + PostgREST + Studio
supabase db reset              # applies supabase/migrations/*.sql from empty, in order
psql "$(supabase status -o env | grep DB_URL | cut -d= -f2)" \
  -f supabase/tests/rls_and_sync.sql   # RLS + idempotency-ledger assertions, wrapped in a rollback

supabase functions serve sync-batch suggestion-decision --env-file supabase/.env.local
```

Manual end-to-end idempotency check for `sync-batch` (once a real user JWT
is available from local GoTrue):
```bash
BATCH='{"batchId":"11111111-0000-0000-0000-000000000001","cursor":0,"operations":[
  {"operationId":"22222222-0000-0000-0000-000000000001","entityType":"workout_plans",
   "entityId":"33333333-0000-0000-0000-000000000001","op":"CREATE","baseVersion":null,
   "data":{"name":"Push Day","day_label":"Push","active":true}}
]}'
curl -s -X POST "http://localhost:54321/functions/v1/sync-batch" \
  -H "Authorization: Bearer $JWT" -H "content-type: application/json" -d "$BATCH" | tee first.json
curl -s -X POST "http://localhost:54321/functions/v1/sync-batch" \
  -H "Authorization: Bearer $JWT" -H "content-type: application/json" -d "$BATCH" | tee second.json
diff first.json second.json   # expect: identical, and only one workout_plans row exists
```

Seed the allowlist (see `supabase/functions/_shared/allowlist_seed.sql` for
the full command) — do this once per environment, values come from a secret
manager / local `.env`, never committed:
```bash
psql "$SUPABASE_DB_URL" -v allowed_email="'owner@example.com'" -v allowed_uid="null" \
  -f supabase/functions/_shared/allowlist_seed.sql
```

Also required (not a file M5 owns, flagging for whoever manages
`supabase/config.toml` / the hosted project's Auth settings): disable public
signup — Studio → Authentication → Providers → Email → turn off "Allow new
users to sign up", or `[auth] enable_signup = false` in `config.toml`. The
DB trigger in `0003_allowlist.sql` is a backstop, not a replacement for this.

## Known limitations

- `sync-batch` applies operations in a validate-then-apply loop within one
  Edge Function invocation, not inside a single Postgres transaction spanning
  all statements (supabase-js over PostgREST doesn't expose that). For a
  single-user, effectively-single-device MVP this is an acceptable tradeoff;
  the `sync_batches` response cache is what actually guarantees "retry never
  duplicates," independent of this. If cross-row atomicity is ever needed
  (e.g. multi-device concurrent writes), the fix is to move the same logic
  into a single `plpgsql` RPC (`fn_sync_batch(...)`) called via
  `admin.rpc(...)`, wrapping everything in one transaction — flagging this as
  a natural follow-up, not doing it now to keep the logic reviewable in
  TypeScript.
- `changes` pull pagination caps at 500 rows per call; large first-syncs need
  multiple round trips (documented above).
- Nothing here has been run against a live Postgres/Supabase instance in
  this environment — no Docker/Supabase CLI was available. `supabase/tests/rls_and_sync.sql`
  is written to be run for real; it has not produced an actual PASS/FAIL log yet.
- `exercises` is modeled as per-user (has `user_id`), not a shared global
  catalog, to keep every table on the same sync/RLS pattern. If a shared
  read-only exercise catalog is wanted later, that's a schema change requiring
  an ADR per section 6 of the plan.
- Enum-like text columns (`workout_sessions.status`, `exercise_sessions.status`,
  `set_logs.set_type`, `workout_sessions.fitbit_sync_status`) are stored as
  unconstrained `text`, not `CHECK`-constrained, because their exact value
  sets aren't frozen in section 4/5 and are owned by `core/model` (M1). Only
  the three enums that ARE frozen in section 4/5 (`coach_suggestions.type`,
  `.confidence`, `.status`) have `CHECK` constraints.

## Definition of done — status

- [x] Migrations are written to apply cleanly from an empty database (order:
      0001 extensions → 0002 sync infra → 0003 allowlist → 0004 core schema).
      **Not yet run against a real Postgres instance in this environment.**
- [x] RLS policies are in place so a user can only ever see their own rows,
      with an anonymous-denied / wrong-uid-denied / owner-allowed SQL test
      script at `supabase/tests/rls_and_sync.sql`. **Not yet executed.**
- [x] `sync-batch` is written so a repeated identical request yields an
      identical result (batch-level response cache + operation-level
      dedup ledger). **Not yet executed against a running function.**
