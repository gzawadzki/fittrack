# M4 core/network handoff

## Works now

- `SupabaseAuthRepository` performs password grant and refresh-token grant over HTTPS, stores sessions in `EncryptedSharedPreferences` protected by Android Keystore, and clears the caller-provided password char array.
- `SupabaseSyncClient` posts the frozen `/sync-batch` contract, retries a single 401 after refresh with the **same** `batchId`, and parses standard backend errors.
- `SyncOrchestrator` is Room-free: it asks the database adapter for a persisted batch, completes acknowledgements + remote apply + cursor advancement atomically, records failed attempts against that batch ID, and cleans raw HR older than 90 days.
- WorkManager handles 15-minute connected periodic sync, expedited post-workout sync, exponential backoff, and daily HR retention cleanup.

## M0 / M1 integration required

1. Add `:core:network` Android Library with dependencies `androidx.security:security-crypto:1.1.0`, `androidx.work:work-runtime-ktx:2.11.0`, and `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2`.
2. M1 implements `SyncOutboxStore` in `core/database`; `prepareBatch(limit)` must keep returning the same persisted `batchId` until `completeBatch(batchId, response)` commits acknowledgement, remote apply, cursor advancement, and prepared-batch removal in one Room transaction. Remote apply must not enqueue the same changes back into the outbox.
3. App DI creates `EncryptedSessionStore`, `SupabaseAuthRepository(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, ...)`, `SupabaseSyncClient("$SUPABASE_URL/functions/v1", ...)`, and assigns `NetworkWorkDependencies.syncOrchestrator` before `BackgroundWork.schedule`.
4. Call `BackgroundWork.syncAfterWorkout(context)` after a successful local workout completion. Do not add Gemini, Fitbit client secret, service role, or password to BuildConfig.

## Focused tests to add after modules exist

- Password login parses session and overwrites old persisted tokens; malformed/Keystore-invalid storage yields signed-out state.
- A 401 refreshes once and reposts the exact same `batchId`; duplicate `ackedOperationIds` are harmless.
- `SyncOrchestrator` only acks reported IDs, records retryable failures, and invokes HR cleanup with `now - 90 days`.
