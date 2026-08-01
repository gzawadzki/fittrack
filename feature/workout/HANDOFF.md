# M2 guided workout

- `WorkoutViewModel` depends directly on M1 `WorkoutRepository` and `PlanRepository`; no feature-local repository facade remains.
- It observes the single Room-backed active workout, its exercise sessions and set logs, so the UI resumes after process death and works offline.
- Set logging validates reps, weight and RIR before calling M1. The UI exposes pain, technique, and concerning-symptom flags.
- The current exercise shows the most recent result for the same plan exercise. Swap results are bound to the originating exercise ID and capped at three.
- Finish-exercise requires every seeded set to be completed. Early workout finish is separately confirmed; M1 then atomically marks remaining exercises skipped.
- M1 owns UTC timestamps at the transaction boundary. It activates the first exercise with the workout; every later exercise waits for explicit `startExercise`, so inter-exercise rest is excluded from its HR window.
