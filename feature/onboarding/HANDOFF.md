# M2 onboarding

- `OnboardingViewModel` depends directly on M1 `ProfileRepository`; no feature-local repository facade remains.
- The draft and review step live in `SavedStateHandle`, so rotation/process recreation does not mix a review screen with an empty form.
- `OnboardingRoute` collects lifecycle-aware state. The approved `UserProfile` is the only persisted form and is written only after explicit confirmation.
- Goal, experience, equipment, and schedule are required before review and persistence.
