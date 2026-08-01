// M5: POST /suggestion-decision
//
// Contract (IMPLEMENTATION_PLAN.md section 4 & 5):
//   input:  { suggestionId, decision, reason? }
//   output: the finalized suggestion and the new version of whatever it targets
//
// Business rule (section 5, non-negotiable): AI suggestions never
// auto-apply. A suggestion sits at status PENDING until this endpoint
// records an explicit user decision. Only decision === "ACCEPTED" mutates
// the target entity's state; "REJECTED" only closes out the suggestion.
//
// Request body:
// {
//   "suggestionId": "uuid",
//   "decision": "ACCEPTED" | "REJECTED",
//   "reason": "optional free text"
// }
//
// Response body:
// {
//   "suggestion": {
//     "id", "type", "targetId", "before", "after", "reasonCode",
//     "explanation", "signals", "citations", "confidence",
//     "status", "decisionReason"
//   },
//   "target": { "entityType": "plan_exercises" | "workout_plans" | null, "id": "uuid" | null, "version": number | null }
// }
//
// How a suggestion mutates its target when ACCEPTED:
//   suggestion.after_value must be a JSON object string of
//   snake_case_column -> value pairs, restricted to a per-type whitelist
//   (see TARGET_SPECS below). M7 (which creates suggestions) must encode
//   `after` using exactly these column names. targetId is interpreted per
//   suggestion.type:
//     LOAD, VOLUME, SWAP -> target row in plan_exercises
//     RESCHEDULE         -> target row in workout_plans
//     HOLD               -> no target row; suggestion is closed with no mutation
//
// Idempotency: deciding an already-decided suggestion with the SAME
// decision returns the current (already-finalized) state unchanged, rather
// than erroring -- safe to retry. Deciding it with a DIFFERENT decision
// than what is already recorded is rejected with ALREADY_DECIDED, since a
// suggestion decision, once made, is final.

import { getServiceClient } from "../_shared/supabaseAdmin.ts";
import { requireUser } from "../_shared/auth.ts";
import {
  alreadyDecided,
  errorResponse,
  internalError,
  jsonResponse,
  methodNotAllowed,
  notFound,
  validationFailed,
} from "../_shared/errors.ts";
import { handleCorsPreflight } from "../_shared/cors.ts";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type SuggestionType = "LOAD" | "VOLUME" | "SWAP" | "RESCHEDULE" | "HOLD";
type Decision = "ACCEPTED" | "REJECTED";

interface TargetSpec {
  table: string;
  columns: readonly string[];
}

const TARGET_SPECS: Record<SuggestionType, TargetSpec | null> = {
  LOAD: { table: "plan_exercises", columns: ["starting_weight_kg"] },
  VOLUME: { table: "plan_exercises", columns: ["working_sets", "warmup_sets", "rep_min", "rep_max", "target_rir"] },
  SWAP: { table: "plan_exercises", columns: ["exercise_id"] },
  RESCHEDULE: { table: "workout_plans", columns: ["scheduled_day_of_week", "scheduled_minute_of_day"] },
  HOLD: null,
};

function isUuid(v: unknown): v is string {
  return typeof v === "string" && UUID_RE.test(v);
}

function toSuggestionDto(row: Record<string, unknown>) {
  return {
    id: row.id,
    type: row.type,
    targetId: row.target_id,
    before: row.before_value,
    after: row.after_value,
    reasonCode: row.reason_code,
    explanation: row.explanation,
    signals: row.signals,
    citations: row.citations,
    confidence: row.confidence,
    status: row.status,
    decisionReason: row.decision_reason,
  };
}

Deno.serve(async (req: Request) => {
  const preflight = handleCorsPreflight(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return methodNotAllowed();

  const admin = getServiceClient();

  const auth = await requireUser(req, admin);
  if (!auth.ok) return auth.response;
  const userId = auth.user.id;

  let json: unknown;
  try {
    json = await req.json();
  } catch {
    return validationFailed("Request body must be valid JSON");
  }

  const body = (json ?? {}) as Record<string, unknown>;
  if (!isUuid(body.suggestionId)) return validationFailed("suggestionId must be a uuid");
  if (body.decision !== "ACCEPTED" && body.decision !== "REJECTED") {
    return validationFailed("decision must be ACCEPTED or REJECTED");
  }
  if (body.reason !== undefined && body.reason !== null && typeof body.reason !== "string") {
    return validationFailed("reason must be a string when provided");
  }
  const suggestionId = body.suggestionId as string;
  const decision = body.decision as Decision;
  const reason = (body.reason as string | undefined) ?? "";

  try {
    const { data: suggestion, error: fetchError } = await admin
      .from("coach_suggestions")
      .select("*")
      .eq("id", suggestionId)
      .eq("user_id", userId)
      .is("deleted_at", null)
      .maybeSingle();
    if (fetchError) throw fetchError;
    if (!suggestion) return notFound(`suggestion ${suggestionId} not found`);

    // Idempotent replay: same decision already recorded -> return current state.
    if (suggestion.status !== "PENDING") {
      if (suggestion.status === decision) {
        const target = await fetchCurrentTarget(admin, suggestion, userId);
        return jsonResponse({ suggestion: toSuggestionDto(suggestion), target });
      }
      return alreadyDecided(
        `suggestion ${suggestionId} was already decided as ${suggestion.status} and cannot be changed to ${decision}`,
      );
    }

    const type = suggestion.type as SuggestionType;
    const spec = TARGET_SPECS[type];

    let targetResult: { entityType: string | null; id: string | null; version: number | null } = {
      entityType: spec?.table ?? null,
      id: spec ? (suggestion.target_id as string) : null,
      version: null,
    };

    if (decision === "ACCEPTED" && spec) {
      let patch: Record<string, unknown>;
      try {
        patch = JSON.parse((suggestion.after_value as string) || "{}");
      } catch {
        return internalError("suggestion.after is not valid JSON; cannot apply");
      }
      if (typeof patch !== "object" || patch === null || Array.isArray(patch)) {
        return internalError("suggestion.after must decode to a JSON object");
      }
      const filtered: Record<string, unknown> = {};
      for (const [key, value] of Object.entries(patch)) {
        if (!spec.columns.includes(key)) {
          return validationFailed(`suggestion.after contains forbidden field '${key}' for type ${type}`);
        }
        filtered[key] = value;
      }
      if (Object.keys(filtered).length === 0) {
        return validationFailed(`suggestion.after has no applicable fields for type ${type}`);
      }

      const { data: updatedTarget, error: targetError } = await admin
        .from(spec.table)
        .update(filtered)
        .eq("id", suggestion.target_id)
        .eq("user_id", userId)
        .is("deleted_at", null)
        .select("id,version")
        .maybeSingle();
      if (targetError) throw targetError;
      if (!updatedTarget) {
        return errorResponse(404, {
          code: "NOT_FOUND",
          message: `target ${type} row ${suggestion.target_id} not found for this user`,
          retryable: false,
          retryAfterSeconds: null,
        });
      }
      targetResult = { entityType: spec.table, id: updatedTarget.id as string, version: updatedTarget.version as number };
    } else if (spec) {
      // REJECTED (or HOLD handled below): report current target version unchanged.
      targetResult = await fetchCurrentTarget(admin, suggestion, userId);
    }

    const { data: finalizedSuggestion, error: updateError } = await admin
      .from("coach_suggestions")
      .update({ status: decision, decision_reason: reason, decided_at: new Date().toISOString() })
      .eq("id", suggestionId)
      .eq("user_id", userId)
      .eq("status", "PENDING") // guards against a concurrent decision racing this one
      .select("*")
      .maybeSingle();
    if (updateError) throw updateError;
    if (!finalizedSuggestion) {
      // Someone else decided it between our read and write; report that.
      return alreadyDecided(`suggestion ${suggestionId} was decided concurrently`);
    }

    return jsonResponse({ suggestion: toSuggestionDto(finalizedSuggestion), target: targetResult });
  } catch (err) {
    console.error("suggestion-decision failed", err instanceof Error ? err.message : String(err));
    return internalError();
  }
});

async function fetchCurrentTarget(
  admin: ReturnType<typeof getServiceClient>,
  suggestion: Record<string, unknown>,
  userId: string,
): Promise<{ entityType: string | null; id: string | null; version: number | null }> {
  const type = suggestion.type as SuggestionType;
  const spec = TARGET_SPECS[type];
  if (!spec) return { entityType: null, id: null, version: null };
  const { data: row } = await admin
    .from(spec.table)
    .select("id,version")
    .eq("id", suggestion.target_id)
    .eq("user_id", userId)
    .maybeSingle();
  if (!row) return { entityType: spec.table, id: suggestion.target_id as string, version: null };
  return { entityType: spec.table, id: row.id as string, version: row.version as number };
}
