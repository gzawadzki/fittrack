# M1 handoff — core/model, core/training, core/database

Owner: Agent 1 (M1). Covers all three of M1's exclusive paths.

## What works

- **core/model**: frozen domain types (`UserProfile`, `PlannedSet`, `WorkoutSession`,
  `CoachSuggestion`, all section-4 enums + `Citation`) plus supporting types
  (`WorkoutPlan`, `PlanExercise`, `Exercise`, `WorkoutPlanSnapshot`,
  `PlanExerciseSnapshot`, `ExerciseSession`, `SetLog`, `HealthDay`) and four
  pure-Kotlin repository interfaces: `ProfileRepository`, `PlanRepository`,
  `WorkoutRepository`, `HealthRepository`. Zero Room/Android imports anywhere
  in this module — feature modules can compile fakes against it standalone.
- **core/training**: `ProgressionEngine` + `ReadinessCalculator`, ported
  1:1 from the prototype's business rules (section 5), plus `CoachEngine`,
  a new orchestrator that reads workout/plan/health history through the
  core/model repository interfaces and turns a completed workout into a
  `List<CoachSuggestion>` (always `PENDING`, never auto-applied).
- **core/database**: Room entities/DAO ported from `app/`, JSON plan
  importer (`PlanJsonImporter`, versioned schema, defaults to v1 for the
  original `sample_plan.json` shape), domain↔entity mappers including a
  JSON codec for the immutable plan snapshot, and Room-backed
  implementations of all four repositories (`RoomProfileRepository`,
  `RoomPlanRepository`, `RoomWorkoutRepository`, `RoomHealthRepository`).

## Why core/database doesn't call core/training directly

Section 2's dependency graph only allows `core/training -> core/model` and
`core/database -> core/model` — no edge from database to training. So
`CoachEngine` (the piece that actually runs `ProgressionEngine` against real
data) lives in **core/training** and depends only on the repository
*interfaces*. The wiring a caller (app or a feature module, later waves) needs:

```kotlin
val finished = workoutRepository.finishWorkout(workoutId, note)
val suggestions = CoachEngine(planRepository, workoutRepository, healthRepository)
    .generateSuggestions(workoutId)
workoutRepository.saveSuggestions(suggestions)          // status = PENDING
// ... later, on explicit user action ...
workoutRepository.decideSuggestion(id, accept = true, reason = "")
if (accept) planRepository.applySuggestion(suggestion)   // only path that mutates the plan
```

`WorkoutRepository.decideSuggestion` only flips the suggestion's status; it
never touches the plan. Applying an accepted suggestion is a separate,
explicit call to `PlanRepository.applySuggestion`. Nothing in these three
modules ever calls that automatically.

## Business rules and where they live

All in `core/training/ProgressionEngine.kt` / `ReadinessCalculator.kt`,
exactly as frozen in section 5:

- Increase = exactly one configured increment; without RIR data requires one
  prior consecutive successful session recorded in `consecutiveSuccessfulSessions`
  (computed by `CoachEngine` by walking `WorkoutRepository.getExerciseSessionHistory`).
- First rep-min miss = `HOLD_LOAD`; second consecutive miss = `DECREASE_LOAD`
  by one increment.
- Pain or a concerning symptom = `STOP_AND_REVIEW`, always, even overriding
  the decrease rule — the engine never diagnoses, it only blocks and explains.
  `CoachEngine` turns this into a `PENDING` `HOLD`-typed suggestion so it's
  visible, not silently dropped.
- A technique-issue flag = `HOLD_LOAD`, also overriding a rep-range miss.
- Readiness: `UNKNOWN` until 7 valid days of sleep+RHR history exist;
  `YELLOW` if sleep `<80%` of the 14-day median OR RHR `>= baseline+5`;
  `RED` if both. Readiness only ever gates an *otherwise-earned* increase
  (RED downgrades it to `REDUCE_VOLUME` by one set, YELLOW downgrades to hold).
  `UNKNOWN` never blocks.
- Step count has no input path into either `ProgressionEngine` or
  `ReadinessCalculator` — there is no field for it. It cannot become a
  progression reason by construction, not just by convention.

## How to run the tests

This workspace currently has **no local JDK/Android SDK** (noted in
IMPLEMENTATION_PLAN.md section 1 as a pre-existing constraint, confirmed
again at the start of this task — `java -version` fails). All code below was
written carefully against the Kotlin/Room/JUnit APIs but **could not be
compiled or executed locally**. Once M0's Gradle wrapper + JDK 17 + Android
SDK are available:

```bash
./gradlew :core:model:test          # no Android dependency, compiles fast
./gradlew :core:training:test       # ProgressionEngineTest, ReadinessCalculatorTest, CoachEngineTest — all pure JVM
./gradlew :core:database:test       # PlanJsonImporterTest (pure JVM) + RoomWorkoutRepositoryTest (Robolectric)
```

`core/model` and `core/training` are plain `org.jetbrains.kotlin.jvm`
modules (no Android Gradle Plugin needed) specifically so their tests run
without an Android SDK. `core/database` is `com.android.library` because
Room requires it; its unit tests use Robolectric + an in-memory Room DB.

Test coverage against the definition-of-done checklist:
- increase: `ProgressionEngineTest` (`increases by exactly one increment...`, matrix case `increase: top+RIR`)
- hold: `holds while still climbing...`, `hold: first miss`, `technique issue holds...`
- decrease: `decreases by one increment after second consecutive miss`, `decrease never goes below zero`
- missing-RIR: `requires two consecutive successful sessions when RIR is absent`, `one prior successful session alone is not enough...`, and end-to-end in `CoachEngineTest.missing RIR needs one prior qualifying session before increase fires`
- pain: `pain blocks progression...`, `pain blocks progression even after two prior misses`, end-to-end in `CoachEngineTest.pain in the completed session yields a PENDING hold suggestion`
- technique issue: `technique issue takes priority over a rep-range miss`
- readiness: full `ReadinessCalculatorTest` suite (unknown/green/yellow both directions/red/median-not-mean/step-count-has-no-input) plus gating tests in `ProgressionEngineTest`
- transactional guarantees: `RoomWorkoutRepositoryTest` (single active workout, snapshot immutability across a later plan edit, idempotent set completion, finish closes dangling exercises)

## Known limitations / left for later waves

- **Not wired into `app/`.** M0 owns `settings.gradle.kts`; these three
  modules exist on disk with their own `build.gradle.kts` but need
  `include(":core:model", ":core:training", ":core:database")` added to
  root `settings.gradle.kts` before anything can build. I did not touch that
  file (M0's exclusive path).
- **Nothing compiled locally** — no JDK/Android SDK in this workspace (see
  above). Please run the commands above as soon as toolchain is available
  and report back any type errors; I've cross-checked signatures by hand but
  a real compiler pass hasn't happened yet.
- **HR/cardio local tables carried over but not wrapped.** `CardioActivityEntity`,
  `HeartRateSampleEntity`, `ExerciseHrSummaryEntity` were migrated from the
  prototype into `core/database` entities + minimal DAO insert/delete methods
  (including the 90-day raw-HR retention delete query), but there is no
  domain type or repository method for them yet — `HealthRepository` only
  covers daily sleep/RHR/steps. M4 (Fitbit sync) or M6 should either extend
  `HealthRepository` or add a small `HeartRateRepository` here; ping M1's
  owner (or just add it directly, these paths are now open for a coordinated
  follow-up) rather than duplicating tables in another module.
- **Sync outbox is write-only from here.** Every mutating repository call
  enqueues a `sync_outbox` row (entity type, id, operation, minimal JSON
  payload, `version = 1`). Draining/POSTing the outbox and handling
  `ackedOperationIds`/`nextCursor` from `/sync-batch` is M4's job — the
  outbox table and `FitTrackDao.getPendingSync`/`markSynced` are ready for it.
- **`PlanRepository.applySuggestion`** only understands `LOAD` (sets
  `startingWeightKg`) and `VOLUME` (sets `workingSets`, using the suggestion's
  `after` as the new absolute value, not a delta — see `CoachEngine`, which
  always emits absolute values in `before`/`after`). `SWAP`/`RESCHEDULE`/`HOLD`
  suggestions are recorded and decided like any other, but applying them is a
  UI/feature-module concern (swap needs a chosen candidate id; reschedule
  needs a new day/time) — no-op here by design, documented at the call site.
- **No instrumented (on-device) tests** — only JVM/Robolectric unit tests
  were written, per the "no Android SDK available" constraint above.

## Public interfaces for M2/M3 to code against (fake these)

```kotlin
package com.fittrack.core.model.repository

interface ProfileRepository {
    fun observeProfile(): Flow<UserProfile?>
    suspend fun getProfile(): UserProfile?
    suspend fun saveProfile(profile: UserProfile)
}

interface PlanRepository {
    fun observePlans(): Flow<List<WorkoutPlan>>
    suspend fun getPlan(planId: String): WorkoutPlan?
    suspend fun getPlanExercise(planExerciseId: String): PlanExercise?
    suspend fun createPlan(plan: WorkoutPlan)
    suspend fun updatePlan(plan: WorkoutPlan)
    suspend fun deletePlan(planId: String)
    suspend fun applySuggestion(suggestion: CoachSuggestion)
    suspend fun importPlanFromJson(json: String): WorkoutPlan
    suspend fun importExerciseLibraryFromJson(json: String)
    suspend fun findSwapCandidates(exerciseId: String, availableEquipment: Set<String>, limit: Int = 3): List<Exercise>
    fun observeExerciseLibrary(): Flow<List<Exercise>>
    suspend fun getExercise(exerciseId: String): Exercise?
    suspend fun upsertExercise(exercise: Exercise)
}

interface WorkoutRepository {
    fun observeActiveWorkout(): Flow<WorkoutSession?>
    suspend fun getActiveWorkout(): WorkoutSession?
    fun observeHistory(limit: Int = 30): Flow<List<WorkoutSession>>
    suspend fun getWorkout(workoutId: String): WorkoutSession?
    fun observeExerciseSessions(workoutId: String): Flow<List<ExerciseSession>>
    suspend fun getExerciseSessions(workoutId: String): List<ExerciseSession>
    fun observeSetLogs(exerciseSessionId: String): Flow<List<SetLog>>
    suspend fun getSetLogs(exerciseSessionId: String): List<SetLog>
    suspend fun getExerciseSessionHistory(planExerciseId: String, excludeWorkoutId: String? = null, limit: Int = 10): List<ExerciseSession>
    suspend fun startWorkout(planId: String): WorkoutSession
    suspend fun completeSet(setId: String, reps: Int, weightKg: Double, rir: Int?, pain: Boolean, techniqueIssue: Boolean, concerningSymptom: Boolean): SetLog
    suspend fun finishExercise(exerciseSessionId: String, note: String)
    suspend fun skipExercise(exerciseSessionId: String, reason: String)
    suspend fun swapExercise(exerciseSessionId: String, replacementExerciseId: String)
    suspend fun finishWorkout(workoutId: String, note: String): WorkoutSession
    suspend fun cancelWorkout(workoutId: String)
    fun observePendingSuggestions(): Flow<List<CoachSuggestion>>
    suspend fun getSuggestion(id: String): CoachSuggestion?
    suspend fun saveSuggestions(suggestions: List<CoachSuggestion>)
    suspend fun decideSuggestion(id: String, accept: Boolean, reason: String = "")
}

interface HealthRepository {
    fun observeDailyHealth(limit: Int = 30): Flow<List<HealthDay>>
    suspend fun getRecentHealth(days: Int = 14): List<HealthDay>
    suspend fun upsertDailyHealth(days: List<HealthDay>)
}
```

Construction for real usage (once M0 wires the modules and an app-level DI
container exists):

```kotlin
val database = FitTrackDatabase.create(context)
val profileRepository: ProfileRepository = RoomProfileRepository(database.dao())
val planRepository: PlanRepository = RoomPlanRepository(database)
val workoutRepository: WorkoutRepository = RoomWorkoutRepository(database)
val healthRepository: HealthRepository = RoomHealthRepository(database.dao())
val coachEngine = CoachEngine(planRepository, workoutRepository, healthRepository)
```

`PlanJsonImporter`/`planRepository.importPlanFromJson` accepts the same
shape as the original `app/src/main/assets/sample_plan.json`, plus an
optional `"schemaVersion"` (defaults to 1). `importExerciseLibraryFromJson`
accepts the same array shape as `exercise_library.json` and is a no-op if
the library already has entries.
