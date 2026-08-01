# M0 handoff — build foundation

Owner: Agent 0 (M0 — integrator / build foundation). Exclusive paths:
`settings.gradle.kts`, root `build.gradle.kts`, `gradle/`, `gradlew*`, `app/`,
`docs/architecture.md`.

## What works (by inspection — see "Not verified" below for why it's not proven by a real run)

- 13-module Gradle graph declared in `settings.gradle.kts`, matching
  `IMPLEMENTATION_PLAN.md` section 2 exactly: `app`, `core:model`, `core:training`,
  `core:database`, `core:network`, `core:designsystem`, `feature:onboarding`,
  `feature:plans`, `feature:workout`, `feature:history`, `feature:coach`,
  `feature:settings`, `platform:reminders`.
- Every new module has a minimal, syntactically valid `build.gradle.kts` with pinned
  (non-dynamic) versions, and a `ModulePlaceholder.kt` marker file (except
  `core:model`/`core:training`, which are pure-Kotlin JVM modules, and
  `platform:reminders`, which has real minimal stub code — see below) so the module
  isn't literally empty.
- `app/`'s previously-broken compile is fixed: `FitTrackApplication.kt` referenced
  `com.fittrack.app.reminders.ReminderScheduler`, `com.fittrack.app.sync.BackgroundWork`,
  and the manifest referenced `.reminders.BootReceiver` — none of which existed in the
  snapshot commit (this is the exact gap flagged in `IMPLEMENTATION_PLAN.md` section 1).
  These now resolve to a real `platform:reminders` module with no-op stub bodies, owned
  going forward by Agent M4.
- Gradle Wrapper is checked in: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
  `gradle/wrapper/gradle-wrapper.properties`, pinned to Gradle 8.13 (no `gradle` binary
  was available locally to run `gradle wrapper`, so the jar/scripts were fetched directly
  from the `gradle/gradle` GitHub repo at tag `v8.13.0` and the properties file written by
  hand — verify the jar's checksum/provenance yourself if that supply chain matters for
  your threat model).
- JDK/AGP/Kotlin/Compose/compileSdk/targetSdk/minSdk are all pinned to exact versions, no
  `+` or ranges anywhere. See `docs/architecture.md` section 5 for the full table.
- DI mechanism chosen and documented: manual DI container, not Hilt (`docs/architecture.md`
  section 4). No Hilt dependency exists anywhere in the project.
- Secret handling checked: `SUPABASE_URL`/`SUPABASE_ANON_KEY` come from `local.properties`
  (gitignored) into `BuildConfig`; no hardcoded keys/tokens found anywhere in `app/` or the
  new scaffolding (repo-wide grep for common key patterns came up empty aside from the plan
  doc's own prose). `.gitignore` already covers `local.properties`, `.env*`, `*.jks`,
  `*.keystore`, `.gradle/`, `**/build/`.

## Not verified — no JDK, Gradle, or Android SDK in this environment

This sandbox has **no `java`, no `gradle`, no Android SDK, no Android Studio** anywhere on
`PATH` or in common install locations (checked `C:\Program Files\Java`,
`C:\Program Files\Eclipse Adoptium`, `%LOCALAPPDATA%\Programs`, `ANDROID_HOME`/
`ANDROID_SDK_ROOT` both unset). `./gradlew --version` fails immediately with
`JAVA_HOME is not set and no 'java' command could be found in your PATH`.

**I did not, and could not, run `./gradlew assembleDebug test lintDebug` to a green state.**
Everything above is correct by careful reading of the Gradle/AGP/Android manifest-merge
model, not by an actual successful build. Treat it as "should build" until proven, not "does
build."

Known specific risks worth checking first when you do have a real environment:

1. **AGP 8.13.2 / Gradle 8.13 compatibility is unverified.** I could not confirm AGP
   8.13.2's minimum-required Gradle version against a real build. If `./gradlew` reports an
   AGP/Gradle incompatibility on first run, bump `gradle/wrapper/gradle-wrapper.properties`'
   `distributionUrl` — do not downgrade AGP/Kotlin/Compose to work around it without updating
   `docs/architecture.md` section 5.
2. **`kapt` + Room in `core/database`** was configured by pattern-matching the existing
   `app/build.gradle.kts` (which already used `kapt` for Room) — never compiled.
3. **Compose BOM `2026.06.00`** and other pinned versions were carried over unchanged from
   the existing `app/build.gradle.kts` (already in the initial snapshot commit) — I did not
   independently verify every one resolves in a real Maven/Google repo lookup beyond
   confirming general internet reachability.
4. `lintDebug` was never run — cannot rule out lint findings in the pre-existing `app/`
   source that a real run would surface.

## How to get to a real green build

1. Install JDK 17 (e.g. Eclipse Temurin 17) and set `JAVA_HOME`.
2. Install Android SDK (compileSdk/targetSdk 36, minSdk 28 platform + build-tools) — easiest
   via Android Studio, or `sdkmanager` if you want CLI-only.
3. Create `local.properties` at repo root (gitignored) with:
   ```
   sdk.dir=/path/to/Android/sdk
   SUPABASE_URL=
   SUPABASE_ANON_KEY=
   ```
   (empty values are fine for a local build — `app/build.gradle.kts` defaults them to `""`.)
4. From repo root: `./gradlew assembleDebug test lintDebug`.
5. If it fails on Gradle/AGP compatibility, see risk #1 above.
6. If it fails on the Gradle wrapper jar itself (corrupted download, GitHub raw served
   something unexpected), regenerate it properly once you have a working Gradle install:
   `gradle wrapper --gradle-version 8.13 --distribution-type bin`, then re-commit.

## Known limitations

- `app/` still contains the full monolithic prototype (Application, MainActivity, ui/,
  data/ — Room entities, DAO, repository, ProgressionEngine). Per the M0 task brief this is
  intentional: `feature/*` modules have no real content yet, so there's nothing to route
  navigation to. `app` is *not yet* the thin composition root described in
  `IMPLEMENTATION_PLAN.md` section 2 — `docs/architecture.md` section 2 documents the
  intended end state and which future agent (M2/M3/M4, coordinated through M0) is
  responsible for each piece of the eventual split.
- `app`'s Room/SQL code technically still lives in `app/` rather than `core/database` — this
  is a pre-existing condition from the snapshot commit, not new work by M0, and per the M0
  brief moving it is M1's job, not M0's ("don't write it yourself, just don't block it").
  The module (`core:database`) exists and is ready to receive it.
- 11 of 13 new modules are empty placeholder shells with no real code — by design, per the
  M0 brief ("scaffold empty buildable module shells for modules other agents will fill in").
- `platform:reminders`'s `ReminderScheduler`/`BackgroundWork`/`BootReceiver` are no-op
  stubs, not real reminder scheduling. Agent M4 owns replacing them.

## Commits made (in order)

1. `feat(build): add gradle module structure and wrapper`
2. `fix(app): compile against platform/reminders instead of missing classes`
3. `docs(architecture): document module wiring, DI choice, and secrets`
4. this file
