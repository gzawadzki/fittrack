# M4 platform/reminders handoff

## Works now

- Persists civil-time weekly schedules and rebuilds each reminder after boot, manual clock change, or timezone change.
- Schedules a `PRE_WORKOUT` work request 60 minutes before and a `MISSED_FOLLOW_UP` request 120 minutes after the planned start.
- Creates one notification channel and supports **Rozpocznij**, **Odłóż 15 min**, and **Przełóż** actions.
- Skips notifications when Android 13+ notification permission is absent. Permission must be requested by UI, not the receiver.

## M0 integration required

1. Add the Gradle module `:platform:reminders` with Android Library + dependencies `androidx.work:work-runtime-ktx:2.11.0` and `androidx.core:core-ktx:1.17.0`.
2. `BootReceiver` and `ReminderActionReceiver` are declared by this module's manifest and merge into the app automatically.
3. Install `ReminderDependencies.workoutStartStateProvider` with a database-backed implementation. It must report whether the planned workout has started since the occurrence; until installed, the missed follow-up retries rather than show a false reminder.
4. Call `ReminderScheduler.updatePlans(context, plans)` whenever plan day/time changes. Request `POST_NOTIFICATIONS` in settings/onboarding and route `EXTRA_REMINDER_ACTION` + `EXTRA_PLAN_ID` from `MainActivity` to start/reschedule UI.

## Focused manual verification

1. Grant notification permission, call `updatePlans` with an occurrence a few minutes ahead, then inspect WorkManager unique work names.
2. Trigger `WorkoutReminderWorker` with `PRE_WORKOUT`; verify three actions.
3. Send `BOOT_COMPLETED` and `TIMEZONE_CHANGED`; verify schedules are rebuilt from stored weekday/minute rather than stale instants.
