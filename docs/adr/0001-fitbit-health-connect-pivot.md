# ADR 0001: Pivot from server-side Fitbit OAuth to on-device Health Connect

Status: Accepted
Date: 2026-08-01
Owner: M6 (Fitbit/health data module)

## Context

M6 originally implemented a classic Fitbit Web API integration:
server-side OAuth 2.0 Authorization Code + PKCE flow (`fitbit-oauth-start`,
`fitbit-oauth-callback`), encrypted refresh-token storage
(`fitbit_tokens`), and a puller (`fitbit-sync`) that called
`api.fitbit.com` from a Supabase Edge Function to import sleep/resting
heart rate/steps/activities and post-workout 1-second-resolution
intraday heart rate, matching HR samples to exercises server-side
(`fitbit-shared/hr-window.ts`, `workout-hr-sync.ts`).

Fitbit has since deprecated that registration path for new "Personal"
apps requiring a client secret. The project owner registered a new
**Android OAuth client in Google Cloud Console** scoped to the
**Health Connect API** (package `com.fittrack.app`, debug SHA-1
`97:80:5A:19:BE:E3:AD:64:73:3B:B4:FA:94:D5:CB:E5:03:E8:1C:84`, client id
`861958162465-an44net40v965spi4047a8jr2rr0vgv8.apps.googleusercontent.com`).
That client id is a **public Android app identifier**, not a secret —
unlike the retired Fitbit client secret, it is safe to reference in
code/config and is not something we encrypt, hide, or scrub from logs.

Health data now originates from **Android's Health Connect API**
(`androidx.health.connect:connect-client`). The Fitbit phone app (or any
other wearable app) writes steps, sleep, heart rate and exercise
sessions into Health Connect; our app reads them **on-device** via
runtime permissions. There is no OAuth authorization-code exchange, no
client secret, and no server-side callback endpoint in this model —
Health Connect permissions are granted locally through the Android
permission UI, not through a redirect-based web flow.

## Decision

Rewrite the M6 integration around on-device Health Connect reads
instead of server-side Fitbit API pulls. Concretely:

1. **Retire** the server-side OAuth Authorization Code flow entirely:
   `fitbit-oauth-start`, `fitbit-oauth-callback`, and the
   OAuth/Fitbit-HTTP-specific parts of `fitbit-shared`
   (`oauth-redirect.ts`, `pkce.ts`, `token-provider.ts`,
   `fitbit-api.ts`, `crypto.ts`, `daily-sync.ts`, `retry.ts`,
   `workout-hr-sync.ts`) — deleted outright, along with their tests,
   since nothing in the new architecture calls `api.fitbit.com` from
   the backend anymore. See "What was deleted" below for the exact
   list and why each one has no remaining caller.
2. **Add** an Android-side Health Connect reader in `core/network`
   (`HealthConnectGateway.kt`) that requests runtime permissions for
   steps, sleep sessions, resting heart rate, exercise sessions and
   heart-rate series, reads daily aggregates, and reads heart-rate
   samples for a workout window.
3. **Port** the HR-to-exercise matching math (average/max/recovery-delta
   per exercise, never-false-zero rule) from
   `fitbit-shared/hr-window.ts` to Kotlin
   (`core/network/HrExerciseMatcher.kt`), so matching now happens
   **on-device** instead of inside an Edge Function. The window
   computation (`[startedAt-5min, endedAt+5min]`, clamped to <=24h) is
   ported unchanged; the Fitbit-specific "split window by calendar day"
   step (`splitWindowByDay` in the old TS) is **not** ported, because
   it existed only to work around Fitbit's intraday API being scoped to
   a single calendar day per request — Health Connect's
   `readRecords`/`aggregate` calls accept an arbitrary `TimeRangeFilter`
   spanning any interval, so that constraint does not apply here.
4. **Push** the resulting daily metrics and per-exercise HR aggregates
   to Supabase through the **same `/sync-batch` contract** everything
   else uses, by whitelisting two existing tables
   (`fitbit_daily_metrics`, `fitbit_hr_exercise_aggregates`) as
   client-writable entity types in
   `supabase/functions/_shared/entities.ts`. No new migration was
   needed — the existing column shapes from
   `20260801060000_fitbit_schema.sql` already fit a Health-Connect-
   sourced row (see "What was kept" below).
5. Keep the retention-purge Edge Function endpoint
   (`fitbit-sync`, now `RETENTION_CLEANUP`-only) and its underlying SQL
   function `fitbit_purge_expired_hr_samples()`, since that logic is
   independent of where the raw samples came from.

## What was deleted and why each has no remaining caller

| Path | Reason |
|---|---|
| `supabase/functions/fitbit-oauth-start/` | No more server-side authorize-URL minting; Health Connect permissions are an Android runtime permission dialog, not a redirect flow. |
| `supabase/functions/fitbit-oauth-callback/` | No more authorization-code exchange; nothing calls Fitbit's token endpoint from the backend. |
| `fitbit-shared/oauth-redirect.ts`, `pkce.ts` | Only used by `fitbit-oauth-start`/`callback`, both deleted. |
| `fitbit-shared/token-provider.ts`, `fitbit-api.ts` | Only used by the deleted OAuth functions and by `fitbit-sync`'s `DAILY_METRICS`/`WORKOUT_HR` modes, also removed (see below). |
| `fitbit-shared/crypto.ts` | Only used to encrypt/decrypt the Fitbit refresh/access tokens stored in `fitbit_tokens`. Health Connect issues no refresh token to store server-side — the "token" a user grants is a local Android permission grant, not a bearer credential our backend ever holds. |
| `fitbit-shared/daily-sync.ts` | Implemented the Fitbit-API daily-metrics pull; superseded by the on-device `HealthConnectGateway.readDailyMetrics`. |
| `fitbit-shared/retry.ts` | Implemented 401/429/5xx classification and "not-yet-ingested" backoff **specific to calling `api.fitbit.com`**. Health Connect reads are a local database query with no such upstream-availability failure mode. |
| `fitbit-shared/workout-hr-sync.ts` | Orchestrated fetch-then-match against the Fitbit intraday endpoint. Matching now happens on-device in `HrExerciseMatcher.kt`; there is no server-side fetch step left to orchestrate. |
| `fitbit-tests/{crypto,daily-sync,oauth-redirect,retry,workout-hr-sync}.test.ts` and `fitbit-tests/fixtures/workout-delayed-sync.json` | Tests for the deleted modules above. |
| `fitbit-sync/index.ts` `DAILY_METRICS` / `WORKOUT_HR` modes | Both called the now-deleted Fitbit HTTP client. The function is kept but trimmed to `RETENTION_CLEANUP` only. |

## What was kept and why

- **`fitbit-shared/hr-window.ts`** (`computeHrWindow`, `matchAndAggregate`,
  `splitWindowByDay`) and its test — still valid, dependency-free, pure
  logic. Nothing server-side calls it anymore (matching moved
  on-device), but it is left in place as tested reference logic the
  Kotlin port was checked against, and in case a future feature needs
  server-side recomputation of aggregates from client-pushed raw data.
  It is genuinely dead code today; flagged here rather than silently
  removed, since deleting well-tested pure logic that isn't causing
  harm is a judgment call better left explicit.
- **`fitbit-shared/errors.ts`**, **`fitbit-shared/supabase-client.ts`** —
  generic, reused by the trimmed `fitbit-sync`.
- **`fitbit-sync/index.ts`** (trimmed) and
  **`fitbit_purge_expired_hr_samples()`** SQL function — retention
  purge is orthogonal to how samples arrived.
- **Migration `20260801060000_fitbit_schema.sql`** — left untouched.
  Additive-only convention in this repo (see other modules' migrations)
  makes dropping columns/tables riskier than leaving unused ones in
  place; see the tokens/raw-samples decision below.

## Decision: fate of `fitbit_tokens`, `fitbit_oauth_states`, `fitbit_hr_samples`, `fitbit_workout_hr_sync`

All four tables are **left in place, unused, not dropped**:

- `fitbit_tokens`, `fitbit_oauth_states` — no longer written or read by
  anything. Health Connect grants a local Android permission, not a
  server-held refresh token; there is nothing to store here anymore.
- `fitbit_hr_samples` (raw 1s-equivalent samples) — **not written by
  the new push path**. See "Health Connect sample granularity" below
  for why "1s" was never a guaranteed granularity even conceptually,
  and why the new design pushes only the computed per-exercise
  aggregate, not raw series, to keep payloads small and avoid
  re-litigating the retention-purge question for data whose source of
  truth is already the phone's own Health Connect store.
- `fitbit_workout_hr_sync` — the domain-visible sync status field
  (`WorkoutSession.fitbitSyncStatus` / `workout_sessions.fitbit_sync_status`,
  owned by M5/M1) already carries `PENDING`/`SYNCED`/`FAILED` and is
  set directly by the client once it has computed and pushed the
  aggregates (a local, synchronous Health Connect read, not a
  poll-and-retry against a flaky upstream API). This module-local
  shadow status table no longer has a distinct purpose.

**Tradeoff considered:** a migration dropping these four tables would
keep the schema minimal. We chose not to write one because (a) this
repo's convention (see M5/M1 migrations) is additive-only, and a drop
migration is the one operation that cannot be replayed against a
database that already has dependent data or in-flight rows from the
old deployment; (b) this integration is already live in production
Supabase per the coordinator's brief — dropping tables under a running
system is a strictly higher-risk operation than leaving four unused
tables around; (c) if disk/row-count ever becomes a real concern, a
follow-up ADR can add a `DROP TABLE` migration once it's confirmed
nothing in production still references them. Leaving them in place
costs nothing functionally: they have RLS `auth.uid() = user_id`
policies already, so no user can read another user's leftover rows,
and no code path reads or writes them anymore after this change.

## Health Connect sample granularity (do not overstate this)

Fitbit's intraday HR endpoint returns one value per second for the
requested window. **Health Connect's `HeartRateRecord` is not the same
shape.** A single `HeartRateRecord` covers a `startTime`/`endTime` span
and contains a list of `HeartRateRecord.Sample`, each an
(`instantaneous time`, `beatsPerMinute`) pair — the sampling interval
between those points is decided by whichever app wrote the record (the
Fitbit app, in the expected deployment), and is not guaranteed by the
Health Connect platform to be 1 Hz, or even evenly spaced. In practice
wearables commonly write bursts every 5-15 seconds rather than every
second, and can leave gaps. `HealthConnectGateway`/`HrExerciseMatcher`
treat the sample list as an **irregularly-spaced series** — the
average/max/recovery-delta math does not assume uniform spacing, and
the recovery-delta lookup already tolerated approximate timing (+/-15s
tolerance) in the original TS implementation, which carries over
unchanged. Do not read "HR samples" in code comments or logs as a claim
of 1-second resolution; that claim is specific to the old Fitbit
intraday API and is false for Health Connect.

## What a developer still needs to wire up

See `supabase/functions/fitbit-HANDOFF.md` (updated alongside this
ADR) for the Android permission-request UI flow that a feature-module
owner (not M6) still needs to build, and for the current verification
status of the Kotlin code (written, not run — no JDK/Android
runtime/emulator was available in this sandbox).
