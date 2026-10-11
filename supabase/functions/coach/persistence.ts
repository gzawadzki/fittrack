// Persists proposed actions as status PENDING only. The model is never given a tool that can
// write/execute a plan change directly - approval/rejection is the separate
// `suggestion-decision` flow owned by M5. This module only ever INSERTs rows with
// status = 'PENDING'; it has no update/delete path.
//
// Inserts go through the service-role client: coach_suggestions deliberately has no
// INSERT policy for `authenticated` (see 0004_core_schema.sql), so user_id is set explicitly
// from the verified JWT, exactly like suggestion-decision does. coach_suggestions.id has no
// DB default, so ids are generated here. Persistence failures are logged and swallowed - the
// coach response is still returned to the user.

import { getServiceClient } from "../_shared/supabaseAdmin.ts";
import type { Suggestion } from "./types.ts";

export async function persistPendingSuggestions(
  userId: string,
  suggestions: Suggestion[],
): Promise<{ persisted: number; failed: number }> {
  if (suggestions.length === 0) return { persisted: 0, failed: 0 };

  const rows = suggestions.map((s) => ({
    id: crypto.randomUUID(),
    user_id: userId,
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
    const { error } = await getServiceClient().from("coach_suggestions").insert(rows);
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
