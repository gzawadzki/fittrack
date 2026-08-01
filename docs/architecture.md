# FitTrack Coach — build architecture (M0)

Owner of this document: Agent 0 (M0 — integrator / build foundation). Scope matches
`IMPLEMENTATION_PLAN.md` section 3 (M0): `settings.gradle.kts`, root `build.gradle.kts`,
`gradle/`, `gradlew*`, `app/`, `docs/architecture.md`.

## 1. Module graph

The module graph declared in `settings.gradle.kts` matches `IMPLEMENTATION_PLAN.md` section 2
exactly:

```
app
core:model        (Kotlin/JVM, no Android dependency)
core:training      (Kotlin/JVM) -> core:model
core:database      (Android library, Room + kapt) -> core:model
core:network       (Android library) -> core:model
core:designsystem  (Android library, Compose)
feature:onboarding (Android library, Compose) -> core:model, core:designsystem
feature:plans      (Android library, Compose) -> core:model, core:designsystem
feature:workout    (Android library, Compose) -> core:model, core:designsystem
feature:history    (Android library, Compose) -> core:model, core:designsystem
feature:coach      (Android library, Compose) -> core:model, core:designsystem
feature:settings   (Android library, Compose) -> core:model, core:designsystem
platform:reminders (Android library) -> core:model
```

Every module besides `app`, `core:model`, and `core:training` currently contains only a
`ModulePlaceholder` marker object (or, for `platform:reminders`, a minimal functional stub —
see section 3) so the multi-module graph resolves and compiles from day one. Each placeholder
file names its owning agent and the plan section describing what belongs there. Owning agents
should delete the placeholder file when they add real content.

`feature/*` modules do not depend on each other, per plan section 2. Room types are confined to
`core/database` — no other module may import `androidx.room.*` types directly once M1 lands its
real implementation.

## 2. app/ — current state and intended wiring

`app/` still contains the original monolithic prototype (`FitTrackApplication`, `MainActivity`,
`ui/`, `data/`) because `feature/*` modules have no real content yet — there is nothing to route
navigation to. This is intentional per the M0 task brief: the module graph must be ready to
*receive* the split, not perform it.

**Intended end state** (to be carried out incrementally by M2/M3/M4 as their feature modules
gain real content, coordinated through M0):

```
app -> feature:onboarding, feature:plans, feature:workout,
       feature:history, feature:coach, feature:settings,
       platform:reminders
```

`app` becomes a thin composition root: `Application`/`MainActivity`, top-level `NavHost` wiring
feature-module entry points together, and the manual DI container (section 4) that constructs
and hands out the `core/database` / `core/network` repository implementations that feature
modules consume via `core/model` interfaces. `app` must not depend on `core:database` or
`core:network` directly once the split is done — those stay behind repository interfaces owned
by `core/model` and implemented in `core/database`/`core/network`; only the DI container in
`app` wires the concrete implementations to the interfaces.

`app`'s current direct dependency on `platform:reminders` (added in this change so
`FitTrackApplication` compiles against `ReminderScheduler`/`BackgroundWork`/`BootReceiver`) is
the first real piece of that intended graph and should stay as-is when the rest of the split
happens.

## 3. platform/reminders stub

`FitTrackApplication.onCreate()` referenced `com.fittrack.app.reminders.ReminderScheduler` and
`com.fittrack.app.sync.BackgroundWork`, and the manifest referenced
`.reminders.BootReceiver` — none of which existed (this is the "prototype was never compiled"
gap called out in `IMPLEMENTATION_PLAN.md` section 1). Rather than deleting the calls, M0 added
a minimal real `platform:reminders` module:

- `ReminderScheduler.createChannel(context)` / `.rescheduleAll(context)` — no-op bodies.
- `BackgroundWork.schedule(context)` — no-op body.
- `BootReceiver` — a `BroadcastReceiver` that calls `ReminderScheduler.rescheduleAll`, declared
  in `platform/reminders/src/main/AndroidManifest.xml` (merges into the app manifest
  automatically; the app manifest no longer declares it directly).

This is scaffolding only — Agent M4 (owner of `core/network` and `platform/reminders`) replaces
the bodies with real WorkManager scheduling per plan section 3 (M4) and section 5. The stub
exists solely so `app` compiles; it implements no reminder behavior.

## 4. Dependency injection: manual container (decision)

**Decision: manual DI container, not Hilt.**

Rationale:
- The module graph is still mostly placeholders; Hilt's compile-time graph validation would
  force every placeholder module to declare `@Module`/`@InstallIn` scaffolding immediately,
  adding churn for modules that have no real bindings yet.
- Hilt adds a kapt/KSP annotation-processing step to every module that wants to contribute
  bindings; a manual container avoids that build-time cost across 13 modules while the project
  is still being assembled by multiple agents in parallel.
- The dependency graph here is shallow (repositories -> use-case-ish engines -> ViewModels),
  which a manual `ServiceLocator`-style container in `app` can express without much boilerplate.

Convention for the eventual container (to be implemented by whoever wires `app` as the
composition root, see section 2): a single `AppContainer` class instantiated in
`FitTrackApplication`, exposing lazily-constructed repository/engine instances by interface
type from `core/model`. Feature-module `ViewModel`s receive their dependencies via a
`ViewModelProvider.Factory` built from `AppContainer` — no service locator lookups inside
Composables or ViewModels themselves.

Do not introduce Hilt (or any other DI framework) anywhere in this project — this is the one
mechanism for the whole codebase.

## 5. Pinned versions

No dynamic (`+`) or range versions anywhere in the build. Pinned in root `build.gradle.kts` and
mirrored in every module's `build.gradle.kts`:

| Component | Version |
|---|---|
| JDK | 17 |
| AGP | 8.13.2 |
| Kotlin | 2.3.21 |
| Gradle (wrapper) | 8.13 |
| compileSdk / targetSdk | 36 |
| minSdk | 28 |
| Compose BOM | 2026.06.00 |

Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
`gradle/wrapper/gradle-wrapper.properties`) is checked in and pinned to Gradle 8.13 via
`distributionUrl`. AGP 8.13.2's minimum-required Gradle version was not verified against a real
build in this environment (see `HANDOFF.md`, no JDK/Android SDK available) — if `./gradlew`
reports a Gradle/AGP incompatibility on first real run, bump `gradle-wrapper.properties`
`distributionUrl` to the version AGP 8.13.2 actually requires; do not change AGP/Kotlin/Compose
versions to work around it without updating this table.

## 6. Secrets

- `SUPABASE_URL` / `SUPABASE_ANON_KEY` are read from `local.properties` (not committed, covered
  by `.gitignore`) into `BuildConfig` fields in `app/build.gradle.kts`. The anon key is safe to
  ship in the APK by design (Supabase anon key + RLS); the Supabase **service role** key must
  never appear here — it belongs only in Supabase Edge Function secrets (M5/M6/M7 scope), never
  in this Android build.
- `.gitignore` already covers `local.properties`, `.env`, `.env.local`, `*.jks`, `*.keystore`,
  `.gradle/`, `**/build/`, `.idea/`, `supabase/.temp/`.
- No API keys, tokens, or credentials were found hardcoded anywhere in `app/` or the new module
  scaffolding (checked via repo-wide grep for common key patterns).
