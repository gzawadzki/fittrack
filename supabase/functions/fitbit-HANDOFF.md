# Fitbit -> Health Connect pipeline (M6) — handoff

Owner: Agent 6 / M6-healthconnect. Exclusive paths: `supabase/functions/fitbit-*`,
`supabase/migrations/20260801060000_fitbit_schema.sql`, Fitbit test fixtures,
plus the Health Connect integration points named in the task brief:
`core/network/HealthConnectGateway.kt`, `HealthConnectSyncGateway.kt`,
`HrExerciseMatcher.kt`, `supabase/functions/_shared/entities.ts` (whitelist
additions), `core/network/build.gradle.kts`, `docs/adr/0001-*.md`.

**Read `docs/adr/0001-fitbit-health-connect-pivot.md` first.** It has the
full rationale for every deletion/keep/kept-unused decision below; this file
is the "what works, how to test, what's left" companion.

## What changed (2026-08-01)

Fitbit deprecated the client-secret-based OAuth registration path this
module originally used. The project owner registered a new Health Connect
Android OAuth client instead (public client id, not a secret — safe to
reference in code). Health data now comes from **Android Health Connect**,
read on-device, not from a server-side Fitbit API pull. This handoff
replaces the old OAuth/token/HR-pull description with the new architecture.

### Deleted (see the ADR for why each has zero remaining callers)

- `supabase/functions/fitbit-oauth-start/`, `fitbit-oauth-callback/` (whole directories)
- `supabase/functions/fitbit-shared/{oauth-redirect,pkce,token-provider,fitbit-api,crypto,daily-sync,retry,workout-hr-sync}.ts`
- `supabase/functions/fitbit-tests/{crypto,daily-sync,oauth-redirect,retry,workout-hr-sync}.test.ts` and `fitbit-tests/fixtures/`

### Kept

- `supabase/functions/fitbit-shared/{errors.ts,supabase-client.ts,hr-window.ts}` —
  generic helpers + the still-tested (now server-side-unused, kept as
  reference) window/matching math.
- `supabase/functions/fitbit-sync/index.ts` — trimmed to **`RETENTION_CLEANUP`
  only**. `DAILY_METRICS`/`WORKOUT_HR` modes and their Fitbit-API calls are gone.
- `supabase/migrations/20260801060000_fitbit_schema.sql` — **untouched, no
  new migration**. `fitbit_daily_metrics` and `fitbit_hr_exercise_aggregates`
  already have the right shape for Health-Connect-sourced rows.
  `fitbit_tokens`, `fitbit_oauth_states`, `fitbit_hr_samples`,
  `fitbit_workout_hr_sync` are left in place but unused — see the ADR's
  "fate of" section for the tradeoff.

### Added

- **`core/network/src/main/java/com/fittrack/core/network/HealthConnectGateway.kt`** —
  `HealthConnectGateway` interface + `AndroidHealthConnectGateway` impl.
  Requests read permissions for `StepsRecord`, `SleepSessionRecord`,
  `RestingHeartRateRecord`, `ExerciseSessionRecord`, `HeartRateRecord`. Reads
  daily aggregates (`readDailyMetrics`) and a workout's HR window
  (`readWorkoutHeartRate`, using the same `[startedAt-5min, endedAt+5min]`
  padding rule as the old Fitbit pipeline, clamped to <=24h).
- **`core/network/src/main/java/com/fittrack/core/network/HrExerciseMatcher.kt`** —
  pure Kotlin port of `fitbit-shared/hr-window.ts`'s `computeHrWindow` +
  `matchAndAggregate` (average/max/recovery-delta per exercise, never-false-
  zero rule). No Fitbit-specific day-splitting — not needed, Health Connect
  accepts arbitrary time ranges.
- **`core/network/src/main/java/com/fittrack/core/network/HealthConnectSyncGateway.kt`** —
  maps Health Connect reads onto the existing frozen domain types
  (`HealthDay`, `FitbitDataPayload`, `ExerciseHeartRateSummary` in
  `core/model/DomainTypes.kt` — **unchanged**, they already fit) for local
  persistence via the existing `HealthRepository`/`FitbitDataRepository`
  interfaces, and separately pushes daily metrics + HR aggregates to
  Supabase via the existing `/sync-batch` contract (`SyncClient`), targeting
  the two new whitelist entries below. See the class doc comment for the
  documented limitation: this pushes directly rather than through
  core/database's local Room outbox (durable-offline-retry), because wiring
  a fully durable path needs new Room entities/DAO methods in
  `core/database/entity/Entities.kt`/`FitTrackDao.kt` — large, monolithic
  files actively owned by M1 in a separate worktree, out of this module's
  named scope. Flagged as a concrete follow-up, not silently skipped.
- **`supabase/functions/_shared/entities.ts`** — two new writable entity
  types: `fitbit_daily_metrics` (`metric_date`, `sleep_minutes`,
  `resting_heart_rate`, `steps`, `raw`) and `fitbit_hr_exercise_aggregates`
  (`workout_session_id`, `exercise_id`, `avg_bpm`, `max_bpm`,
  `recovery_delta_bpm`, `sample_count`) — column sets match the existing
  migration exactly.
- **`core/network/build.gradle.kts`** — pinned
  `androidx.health.connect:connect-client:1.1.0-alpha12` (no `+` range; this
  repo has no Gradle version catalog, every module pins literal version
  strings directly, so this follows the existing convention). **Could not be
  confirmed against Google Maven** — no network access in this sandbox —
  verify it resolves before a real build and bump if a newer stable exists.
- **`docs/adr/0001-fitbit-health-connect-pivot.md`** — full decision record.

## What a developer (UI/feature-module owner) still needs to build

This module does not own or touch `feature/settings/`, `feature/workout/`,
or app-level DI (`app/`), per IMPLEMENTATION_PLAN.md's module ownership. To
finish wiring:

1. **Permission-request UI** (feature/settings, matching where the old
   "connect Fitbit" button lived): call
   `HealthConnectGateway.requiredPermissions()` and launch it via
   `PermissionController.createRequestPermissionResultContract()` (standard
   Health Connect Compose/Activity Result API — not built here). Show
   connected/not-connected state from `hasAllPermissions()`, same neutral-
   when-absent treatment the old Fitbit "not connected" state had (plan
   section 3, M3: "brak danych Fitbit jest pokazany jako neutralny").
2. **DI wiring** (app/, M0's path): construct `AndroidHealthConnectGateway`
   with an Android `Context`, wrap it in `HealthConnectSyncGateway` with the
   app's existing `SyncClient` instance (the same one `SupabaseSyncClient`
   already provides).
3. **Trigger points** (feature/workout or platform/reminders, wherever the
   old post-workout Fitbit sync call lived): after `finishWorkout`, call
   `readWorkoutHeartRate(...)`, persist locally via
   `FitbitDataRepository.persist(result.toFitbitDataPayload(...))`, then push
   via `HealthConnectSyncGateway.pushWorkoutAggregates(...)`. On a periodic
   or app-open cadence, call `readDailyMetrics()` ->
   `HealthRepository.upsertDailyHealth(result.toHealthDays())` ->
   `HealthConnectSyncGateway.pushDailyMetrics(...)`.
4. **Business rule reminder** (plan section 5): steps are context only,
   never a standalone reason to auto-change a plan/suggestion. Nothing added
   in this module touches `PlanRepository`/suggestion state — whatever code
   a UI owner adds around this data must keep that true.

## How to test

**Ran and passing (Node 24):**

```bash
cd supabase/functions
node --experimental-strip-types --test fitbit-tests/*.test.ts
```

9/9 tests pass:
- `fitbit-tests/hr-window.test.ts` (unchanged, kept as reference — 6 tests:
  window padding/clamping, midnight-crossing day-split, never-false-zero
  matching).
- `fitbit-tests/entities-whitelist.test.ts` (new — 3 tests: the two new
  entity types are writable with exactly the migrated columns, and the
  retired token/state/raw-sample tables are correctly NOT writable).

**Not run — no JDK/Android SDK/emulator available in this sandbox:**
- `HealthConnectGateway.kt`, `HealthConnectSyncGateway.kt` — written against
  the `androidx.health.connect:connect-client` API surface as documented,
  never compiled. Verify the API shapes (`ReadRecordsRequest`,
  `AggregateGroupByPeriodRequest`, `TimeRangeFilter.between`,
  `HeartRateRecord.samples`, `PermissionController`) against the actual
  library version once a real build is available — Health Connect's Kotlin
  API has had breaking changes across alpha releases historically, and the
  pinned version above was not confirmed against Google Maven.
- `HrExerciseMatcherTest.kt` — written to mirror
  `fitbit-tests/hr-window.test.ts`'s coverage exactly (including the same
  squat/bench/not-worn fixture values), but never run with
  `./gradlew :core:network:test`. Do not treat this as "tests passing" —
  it is unverified source, consistent with the rest of this repo's Kotlin
  code (see IMPLEMENTATION_PLAN.md section 1: "Prototyp nie był
  kompilowany").
- No real Health Connect device/emulator round-trip was performed — nothing
  in this sandbox can install the Health Connect provider app or grant
  runtime permissions.

## Required Supabase secrets

None added or removed by this change. `FITBIT_CLIENT_ID`/
`FITBIT_CLIENT_SECRET`/`FITBIT_TOKEN_ENC_KEY` are no longer read by any
function (safe to leave configured or remove — nothing depends on them);
`SUPABASE_URL`/`SUPABASE_SERVICE_ROLE_KEY` are still used by the trimmed
`fitbit-sync`.

## Known limitations (current, see the ADR for full context)

1. **Health-Connect push is not offline-durable.** `HealthConnectSyncGateway`
   calls `/sync-batch` directly rather than through the local Room outbox —
   if the app is killed mid-push or the request fails, the read is lost
   rather than retried by the periodic `SyncWorker`. Fixing this needs new
   Room entities/DAO support in files owned by M1; flagged, not silently
   dropped (see `HealthConnectSyncGateway`'s class doc).
2. **No local version tracking across pushes.** Every push is a `CREATE`
   with `baseVersion = null` and a deterministic (name-derived) entity id;
   there is no read-modify-write cycle against the server's current version.
   Acceptable for now because these rows are append/overwrite-by-day or
   overwrite-by-(workout,exercise) snapshots, not collaboratively edited
   state, but a real conflict (e.g. two devices) is not handled specially.
3. **Health Connect sample granularity is not 1Hz.** See the ADR — do not
   claim continuous 1-second HR data anywhere downstream; a
   `HeartRateRecord`'s internal samples can be sparser and irregular.
4. **Retention cleanup (`RETENTION_CLEANUP` mode) currently has nothing to
   purge** in the new architecture, since the push path does not write
   `fitbit_hr_samples`. Left in place rather than deleted in case a future
   feature starts writing raw samples again (e.g. for a "view raw HR trace"
   feature) — see the ADR.
5. **`core/network/FitbitGateway.kt` (`SupabaseFitbitGateway`) now calls dead
   endpoints and is out of this module's scope to fix.** `core/network/` is
   M4's exclusive path (IMPLEMENTATION_PLAN.md section 3) — this task
   explicitly carved out only the new Health Connect files
   (`HealthConnectGateway.kt`, `HealthConnectSyncGateway.kt`,
   `HrExerciseMatcher.kt`) as an exception, not the pre-existing
   `FitbitGateway.kt`/`SupabaseFitbitGatewayTest.kt`. That file's
   `startOAuth()` POSTs to `/fitbit-oauth-start` (deleted) and `sync()` can
   request `FitbitSyncMode.DAILY_METRICS`/`WORKOUT_HR` (both removed from
   `fitbit-sync`, which now only understands `RETENTION_CLEANUP`, a mode
   `FitbitSyncMode` doesn't even have a case for). Calling either from the
   app today gets a 404 or `FITBIT_BAD_REQUEST`, not a clean "not
   supported" error. **This needs M4 (or the coordinator) to either delete
   `FitbitGateway.kt`/its test or repoint it at `HealthConnectGateway`** —
   flagged here rather than edited directly, since silently rewriting
   another module's exclusively-owned file risked exactly the kind of
   cross-agent collision the ownership convention exists to prevent.
6. **Timezone handling in `HealthConnectGateway.readDailyMetrics`** uses the
   device's local `ZoneId.systemDefault()` for day bucketing (steps/sleep/
   RHR), which is more correct than the old Fitbit pipeline's UTC-only
   bucketing (see the old handoff's timezone caveat) since it's the same
   zone the phone's own Health Connect data was recorded relative to — but
   this has not been exercised against a real device across a DST
   transition or timezone change.
