# Fitbit backend + HR pipeline (M6) — handoff

Owner: Agent 6. Exclusive paths: `supabase/functions/fitbit-*`,
`supabase/migrations/20260801060000_fitbit_schema.sql`, Fitbit test fixtures.

## What works

- **OAuth Authorization Code + PKCE flow**, server-side only:
  - `fitbit-oauth-start` (`supabase/functions/fitbit-oauth-start/index.ts`) — mints a
    CSRF `state` + PKCE `code_verifier`/`code_challenge`, persists them in
    `fitbit_oauth_states` (10 min TTL, single-use), returns `authorizeUrl`.
  - `fitbit-oauth-callback` (`supabase/functions/fitbit-oauth-callback/index.ts`) —
    exchanges `code` for tokens, encrypts access+refresh tokens (AES-256-GCM,
    fresh IV per encryption) and upserts `fitbit_tokens`, then 302-redirects to
    `fittrack://auth/fitbit?status=ok|error&reason=...`. The client secret and
    the tokens never appear in the redirect, the response body, or any log line.
  - State row is deleted immediately after being read (before the token
    exchange), so a replayed `code`/`state` cannot succeed twice.
- **`fitbit-sync`** (`supabase/functions/fitbit-sync/index.ts`), three modes:
  - `DAILY_METRICS` — imports sleep, resting heart rate, steps (last 7 days)
    and recent walk/run activities (filtered from the full activities list by
    activity name). Missing values are written as SQL `NULL`, never `0`.
  - `WORKOUT_HR` — given `workoutSessionId`, computes
    `[startedAt-5min, endedAt+5min]` clamped to ≤24h, fetches Fitbit's 1s
    intraday HR (split per calendar day if the window crosses midnight),
    matches samples into each exercise's time range, computes
    avg/max/recovery-delta per exercise, and flips
    `fitbit_workout_hr_sync.status` `PENDING -> SYNCED` (or `FAILED`).
  - `RETENTION_CLEANUP` — calls the `fitbit_purge_expired_hr_samples()` SQL
    function to delete raw HR samples older than 90 days. Aggregates in
    `fitbit_hr_exercise_aggregates` are never touched by this.
- **Retry/error handling** (`fitbit-shared/retry.ts`):
  - `401` → refresh access token once via the stored (encrypted) refresh
    token, retry the call; a second `401` fails the sync (`fitbit_reauth_required`).
  - `429` → `PENDING`/`PARTIAL` with `retryAfter` sourced from Fitbit's
    `Retry-After` header (falls back to 60s if absent/invalid).
  - Empty intraday dataset within 20 minutes of the workout window's end is
    treated as "Fitbit hasn't ingested the device data yet", not as "no HR
    recorded" — this defers to `PENDING` with exponential backoff (30s →
    ..., capped at 30 min). Past that grace period, an empty result is
    accepted as final (e.g. the device wasn't worn).
- **Never-false-zero guarantee**: an exercise with zero overlapping HR
  samples gets `avgBpm/maxBpm/recoveryDeltaBpm = null` and `sampleCount = 0`
  in `fitbit_hr_exercise_aggregates` — verified by test.
- **Token encryption**: `fitbit-shared/crypto.ts` does AES-256-GCM via Web
  Crypto (works identically in Deno and Node). Plaintext tokens exist only
  in-memory inside the edge function for the duration of a request/response
  cycle; Postgres only ever stores ciphertext + IV.

## Migration

`supabase/migrations/20260801060000_fitbit_schema.sql` — additive only,
never alters another module's tables. Creates:

| Table | Purpose |
|---|---|
| `fitbit_tokens` | one row/user, encrypted access+refresh token |
| `fitbit_oauth_states` | short-lived PKCE/CSRF state, service-role only (no RLS policy = default deny for anon/authenticated) |
| `fitbit_sync_cursors` | per-resource incremental sync bookkeeping (present for future use; `fitbit-sync` currently just re-pulls the last 7 days each call) |
| `fitbit_daily_metrics` | sleep/RHR/steps per user/date, `NULL` for missing values |
| `fitbit_activities` | imported walk/run activities, deduped by `fitbit_log_id` |
| `fitbit_workout_hr_sync` | one row per workout, backs the `fitbitSyncStatus` domain field (`PENDING`/`SYNCED`/`FAILED`) |
| `fitbit_hr_samples` | raw 1s HR samples, purged after 90 days |
| `fitbit_hr_exercise_aggregates` | per-exercise avg/max/recovery-delta, kept forever |

RLS: `auth.uid() = user_id` on every user-owned table, same convention as M5.
`workout_session_id` / `exercise_id` columns are plain `uuid` with **no FK
constraint** to M5/M1's tables, since this module must migrate cleanly against
an empty database independent of migration ordering.

**Naming coordination**: filename prefix `20260801060000_fitbit_*` was chosen
to be distinct from M5's `0001_*`/`0002_*`/`0003_*` sequential prefixes seen
in its worktree at the time this was written — confirm no collision once
branches are merged.

## Known limitation / integration point to confirm with M5

`fitbit-sync`'s `WORKOUT_HR` mode reads:
- `workout_sessions(id, user_id, started_at, ended_at)` to get the workout
  window, and
- a `sets(exercise_id, workout_session_id, started_at, completed_at)` shaped
  table to derive each exercise's time range (min/max across its sets).

Neither table exists yet in this module's own migration (by design — they're
owned by M1/M5) and their exact column names were **not** in the frozen
Kotlin contract in section 4, only inferred from the domain model. **Once
M5's workout/exercise/set schema lands, verify these two queries in
`fitbit-sync/index.ts` (search for "NOTE: workout_sessions is owned by M5's
schema" and "NOTE: exact table/columns owned by M1/M5") against the real
column names and adjust if they differ.** The rest of the pipeline
(`fitbit-shared/workout-hr-sync.ts`) is schema-agnostic — it takes
`WorkoutInfo` and `ExerciseTimeRange[]` as plain data, so only the two
Supabase queries in `fitbit-sync/index.ts` need updating, not the pipeline
logic itself.

## How to test

No Supabase CLI, Docker, or live Fitbit account was available in this
environment, so verification is split:

**Ran and passing (Node 24, no Deno needed for pure logic):**

```bash
cd supabase/functions
node --experimental-strip-types --test fitbit-tests/*.test.ts
```

22/22 tests pass, covering:
- `fitbit-tests/hr-window.test.ts` — window padding/clamping to ≤1 day,
  midnight-crossing day-split, HR-to-exercise matching with the
  never-false-zero rule.
- `fitbit-tests/retry.test.ts` — 401/429/5xx classification, Retry-After
  parsing, the "empty result within grace period" defer decision.
- `fitbit-tests/crypto.test.ts` — encrypt/decrypt round-trip, wrong-key
  failure, fresh IV per call (no nonce reuse).
- `fitbit-tests/workout-hr-sync.test.ts` — **the definition-of-done fixture**:
  `fitbit-tests/fixtures/workout-delayed-sync.json` simulates a workout whose
  HR data isn't in Fitbit yet; attempt 1 (called 2 min after the workout ends)
  returns `PENDING` with a `retryAfter` and writes no aggregates; attempt 2
  (called later, with the fixture now returning the full dataset) returns
  `SYNCED`, persists aggregates, and an exercise with zero overlapping
  samples still gets `null` fields rather than `0`. Also covers the 401
  auto-refresh-and-retry path and 429 handling end-to-end through
  `syncWorkoutHr`.
- `fitbit-tests/daily-sync.test.ts` — missing sleep/RHR stored as `null` not
  `0`, walk/run filtering from the full activities list, 429 → `PARTIAL`.

This works because all business logic (`fitbit-shared/hr-window.ts`,
`retry.ts`, `crypto.ts`, `workout-hr-sync.ts`, `daily-sync.ts`) is written
with zero Deno-specific APIs and takes its Fitbit HTTP client / DB access as
injected interfaces — the same shape is wired to real `fetch` +
`@supabase/supabase-js` in the three `index.ts` handlers, but that wiring
itself was **not** executed (no Deno/Supabase CLI here).

**Not run — requires the real environment:**
- `supabase functions serve` / `supabase db reset` to apply the migration
  and boot the three edge functions.
- An actual Fitbit "Personal" app registration and OAuth round-trip against
  `https://api.fitbit.com`.
- The `fitbit-oauth-start`/`callback`/`sync` `index.ts` files themselves
  (they import `Deno.serve` and `https://esm.sh/@supabase/supabase-js@2`,
  which only resolve under the Deno edge runtime).

To verify against the real API once Supabase CLI + a Fitbit dev app are
available:
1. `supabase secrets set FITBIT_CLIENT_ID=... FITBIT_CLIENT_SECRET=... FITBIT_TOKEN_ENC_KEY=$(node -e "console.log(require('crypto').randomBytes(32).toString('base64'))")`
2. `supabase db push` (applies `20260801060000_fitbit_schema.sql`).
3. `supabase functions serve fitbit-oauth-start fitbit-oauth-callback fitbit-sync`.
4. Call `fitbit-oauth-start` with a valid Supabase JWT and a `redirectUri`
   registered on the Fitbit app; open the returned `authorizeUrl` in a
   browser, approve, confirm the browser lands on
   `fittrack://auth/fitbit?status=ok`.
5. Call `fitbit-sync` with `{"mode":"DAILY_METRICS"}` and inspect
   `fitbit_daily_metrics`/`fitbit_activities`.
6. Create a `workout_sessions` row with real `started_at`/`ended_at`, call
   `fitbit-sync` with `{"mode":"WORKOUT_HR","workoutSessionId":"..."}` shortly
   after — expect `PENDING`; call again a few minutes later — expect
   `SYNCED` and populated `fitbit_hr_exercise_aggregates`.

## Exact request/response contracts (as implemented, matching section 4)

**`POST /fitbit-oauth-start`** (requires `Authorization: Bearer <supabase JWT>`)
```json
// request
{ "redirectUri": "fittrack://auth/fitbit-redirect" }
// response 200
{ "authorizeUrl": "https://www.fitbit.com/oauth2/authorize?..." }
```

**`GET /fitbit-oauth-callback?code=...&state=...`** (no JWT; Fitbit calls this directly)
```
302 Location: fittrack://auth/fitbit?status=ok
302 Location: fittrack://auth/fitbit?status=error&reason=<denied|missing_params|invalid_state|state_expired|internal>
```

**`POST /fitbit-sync`** (requires JWT)
```json
// request
{ "mode": "DAILY_METRICS" }
// response 200
{ "status": "SYNCED", "importedCounts": { "sleep": 1, "restingHeartRate": 1, "steps": 1, "activities": 2 }, "retryAfter": null }
```
```json
// request
{ "mode": "WORKOUT_HR", "workoutSessionId": "9c9b1e2e-1111-4a11-8a11-000000000001" }
// response 200 (Fitbit not synced yet)
{ "status": "PENDING", "importedCounts": { "heartRateSamples": 0 }, "retryAfter": 42 }
// response 200 (synced)
{ "status": "SYNCED", "importedCounts": { "heartRateSamples": 2312 }, "retryAfter": null }
```
```json
// request
{ "mode": "RETENTION_CLEANUP" }
// response 200
{ "status": "SYNCED", "importedCounts": { "deletedHrSamples": 143 }, "retryAfter": null }
```

**Errors** (all three functions except the oauth-callback redirect path) use
the frozen shape:
```json
{ "error": { "code": "FITBIT_NOT_CONNECTED", "message": "Fitbit account is not connected", "retryable": false, "retryAfterSeconds": null } }
```
Stable codes emitted by this module: `FITBIT_UNAUTHORIZED`,
`FITBIT_NOT_CONNECTED`, `FITBIT_INVALID_OAUTH_STATE`, `FITBIT_RATE_LIMITED`,
`FITBIT_UPSTREAM_ERROR`, `FITBIT_BAD_REQUEST`, `FITBIT_TOKEN_EXCHANGE_FAILED`.

## Required Supabase secrets

| Secret | Purpose |
|---|---|
| `FITBIT_CLIENT_ID` | Fitbit "Personal" app client id |
| `FITBIT_CLIENT_SECRET` | Fitbit app client secret — used only inside `fitbit-oauth-callback`/`fitbit-sync`'s token-refresh path, never returned or logged |
| `FITBIT_TOKEN_ENC_KEY` | base64-encoded 32-byte AES-256 key for encrypting stored tokens (generate with `node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"`) |
| `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` | standard Supabase-provided function secrets, used for the service-role client |

The Fitbit app's OAuth **Redirect URL** must be set (in the Fitbit developer
console) to this function's deployed `fitbit-oauth-callback` URL.

## Known limitations

1. **Exercise/set schema assumption** — see "Integration point to confirm
   with M5" above; the two queries in `fitbit-sync/index.ts` (`WORKOUT_HR`
   mode) assume table/column names not present in any migration this module
   owns.
2. **Timezone handling** — `computeHrWindow`/`splitWindowByDay` operate in
   UTC. Fitbit's intraday endpoint is scoped to the user's Fitbit-account
   timezone, not UTC. For most single-timezone users this is invisible, but
   a workout that starts very close to local midnight in a non-UTC timezone
   could compute a UTC day-split that doesn't line up with Fitbit's actual
   day boundary. Not exercised by fixtures; flagged for follow-up if
   multi-timezone correctness becomes a requirement.
3. **`fitbit_sync_cursors` table exists but isn't used yet** — `DAILY_METRICS`
   mode currently always re-pulls the last 7 days rather than resuming from a
   cursor. Cheap to fix later (the table is already migrated) but not wired
   up, since 7-day re-pull is idempotent (`upsert` on `(user_id, metric_date)`
   / `(user_id, fitbit_log_id)`) and cheap enough for a personal-use app.
4. **Retention cleanup isn't scheduled** — `RETENTION_CLEANUP` mode and the
   underlying `fitbit_purge_expired_hr_samples()` SQL function exist and are
   invokable, but nothing in this module wires up a cron/schedule to call it
   automatically. Needs a `pg_cron` job or an external scheduler hitting
   `fitbit-sync` with `{"mode":"RETENTION_CLEANUP"}` periodically — left to
   M8/ops since scheduling infrastructure is outside this module's owned
   paths.
5. **No live-API verification** — see "How to test" above; everything was
   validated against fixtures/fakes, not the real Fitbit API or a live
   Supabase instance, since neither was available in this environment.
6. **`fitbit-oauth-start`'s `redirectUri` is not allowlisted** — it's echoed
   into the Fitbit authorize URL as provided by the caller. For a "Personal"
   app with a single allowed user this is low-risk, but if multi-tenant use
   is ever considered, add an allowlist check before the OAuth redirect.
