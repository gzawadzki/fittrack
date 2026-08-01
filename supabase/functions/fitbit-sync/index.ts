// POST /fitbit-sync
// Input:  { "mode": "RETENTION_CLEANUP" }
// Output: { "status": "SYNCED", "importedCounts": { "deletedHrSamples": number }, "retryAfter": null }
//
// Requires a Supabase JWT (Authorization: Bearer <token>).
//
// Trimmed down as part of M6's Fitbit -> Health Connect pivot — see
// docs/adr/0001-fitbit-health-connect-pivot.md. The old `DAILY_METRICS` and
// `WORKOUT_HR` modes called the Fitbit Web API (`fitbit-shared/fitbit-api.ts`,
// `daily-sync.ts`, `workout-hr-sync.ts`), which have been deleted: health
// data now comes from the Android app reading Health Connect on-device and
// pushing results through `/sync-batch` (see
// `supabase/functions/_shared/entities.ts`'s `fitbit_daily_metrics`/
// `fitbit_hr_exercise_aggregates` entries), not from a server-side pull.
//
// mode=RETENTION_CLEANUP -> purges raw HR samples older than 90 days
//                        (aggregates are never purged). Kept because this
//                        logic is independent of where samples came from.
//                        Intended to be invoked by a scheduled trigger
//                        (pg_cron / external scheduler hitting this
//                        function), not by the app. In practice, the new
//                        push path does not write fitbit_hr_samples at all
//                        (see the ADR's "fate of fitbit_hr_samples" section),
//                        so this endpoint currently has nothing to purge —
//                        left in place as a no-op-safe primitive rather than
//                        deleted, in case a future feature starts writing
//                        raw samples again.

import { ERR, errorResponse, FitbitFunctionError } from "../fitbit-shared/errors.ts";
import {
  getAuthenticatedUserId,
  getServiceRoleClient,
} from "../fitbit-shared/supabase-client.ts";

// @ts-ignore - Deno global, not resolved by local Node type-checker
Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return errorResponse(
      new FitbitFunctionError({
        code: ERR.BAD_REQUEST,
        message: "Method not allowed",
        httpStatus: 405,
      }),
    );
  }

  try {
    const userId = await getAuthenticatedUserId(req);
    if (!userId) {
      return errorResponse(
        new FitbitFunctionError({
          code: ERR.UNAUTHORIZED,
          message: "Sign in required",
          httpStatus: 401,
        }),
      );
    }

    const body = await req.json().catch(() => ({}));
    const mode = body?.mode;

    if (mode === "RETENTION_CLEANUP") {
      const supabase = getServiceRoleClient();
      const { data, error } = await supabase.rpc(
        "fitbit_purge_expired_hr_samples",
      );
      if (error) throw new Error(error.message);
      return jsonResponse({
        status: "SYNCED",
        importedCounts: { deletedHrSamples: data ?? 0 },
        retryAfter: null,
      });
    }

    return errorResponse(
      new FitbitFunctionError({
        code: ERR.BAD_REQUEST,
        message: "Unknown mode",
        httpStatus: 400,
      }),
    );
  } catch (err) {
    if (err instanceof FitbitFunctionError) {
      return errorResponse(err);
    }
    console.error("fitbit-sync failed", (err as Error).message);
    return errorResponse(
      new FitbitFunctionError({
        code: ERR.UPSTREAM_ERROR,
        message: "Fitbit sync failed",
        httpStatus: 500,
        retryable: true,
        retryAfterSeconds: 60,
      }),
    );
  }
});

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}
