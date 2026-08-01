# M2 plans

- `PlansViewModel` depends directly on M1 `PlanRepository`; no feature-local repository facade remains.
- The editor draft is kept in `SavedStateHandle`. M1 assigns the first version and atomically increments later versions; the UI never increments a version.
- The editor covers schedule, warm-up/work sets, rep range, RIR, load, rest, increment, movement pattern, muscle role, equipment, and notes.
- Exercise library metadata is joined into persisted plans, so later session-only swap filtering keeps working after an edit.
- Final validation runs in the ViewModel immediately before any exercise or plan write.
