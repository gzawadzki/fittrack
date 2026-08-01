// Persists proposed actions as status PENDING only. The model is never given a tool that can
// write/execute a plan change directly - approval/rejection is the separate
// `suggestion-decision` flow owned by M5. This module only ever INSERTs rows with
// status = 'PENDING'; it has no update/delete path.
//
// NOTE on schema: table name/columns assumed (`coach_suggestions`) per the CoachSuggestion
// domain type in IMPLEMENTATION_PLAN.md section 4, since supabase/migrations (M5) had not
// published a schema at the time this was written. Persistence failures are logged and
// swallowed - the coach response is still returned to the user even if persistence fails,
// since the model's advice/explanation is still valid without a stored suggestion row. If a
// suggestion fails to persist, the client-facing response still includes it (marked in-memory
// only) so the user is not silently denied the recommendation; the sibling `suggestion-decision`
// flow author (M5) should confirm the real table shape and adjust the insert below.

import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import type { Suggestion } from "./types.ts";

export async function persistPendingSuggestions(
  supabase: SupabaseClient,
  userId: string,
  workoutSessionId: string | null,
  suggestions: Suggestion[],
): Promise<{ persisted: number; failed: number }> {
  if (suggestions.length === 0) return { persisted: 0, failed: 0 };

  const rows = suggestions.map((s) => ({
    user_id: userId,
    workout_session_id: workoutSessionId,
    type: s.type,
    target_id: s.targetId,
    before_value: s.before,
    after_value: s.after,
    reason_code: s.reasonCode,
    explanation: s.explanation,
    signals: s.signals,
    confidence: s.confidence,
    status: "PENDING",
  }));

  try {
    const { error } = await supabase.from("coach_suggestions").insert(rows);
    if (error) {
      console.error("persistPendingSuggestions: insert failed", error.message);
      return { persisted: 0, failed: rows.length };
    }
    return { persisted: rows.length, failed: 0 };
  } catch (e) {
    console.error("persistPendingSuggestions: unexpected error", String(e));
    return { persisted: 0, failed: rows.length };
  }
}
