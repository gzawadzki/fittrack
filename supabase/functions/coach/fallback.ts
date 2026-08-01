// Controlled fallback: used whenever the model call fails, times out, or returns output that
// does not pass schema validation. Guarantees the endpoint NEVER returns an unhandled or
// malformed response - it always returns a schema-valid CoachOutput, built entirely from the
// deterministic recommendation, with no model-authored claims or citations.

import type { CoachContext } from "./context.ts";
import type { CoachOutput } from "./types.ts";

export function buildFallbackOutput(ctx: CoachContext): CoachOutput {
  if (!ctx.deterministic) {
    return {
      message:
        "I don't have enough workout data yet to give a grounded recommendation. Log a session and I'll take another look.",
      citations: [],
      suggestions: [],
      safety: { blocked: false, reason: null },
    };
  }

  const rec = ctx.deterministic;
  const blocked = rec.action === "STOP_AND_REVIEW";

  const suggestions = blocked || rec.action === "HOLD_LOAD"
    ? []
    : [
      {
        type: (rec.action === "REDUCE_VOLUME" ? "VOLUME" : "LOAD") as "LOAD" | "VOLUME",
        targetId: ctx.targetId ?? "unknown",
        before: String(ctx.recentWorkouts[0]?.exerciseName ? "" : ""),
        after: String(rec.proposedWeightKg),
        reasonCode: rec.reasonCode,
        explanation: rec.explanation,
        signals: [rec.action],
        confidence: "MEDIUM" as const,
      },
    ];

  return {
    message: rec.explanation,
    citations: [],
    suggestions,
    safety: { blocked, reason: blocked ? rec.explanation : null },
  };
}
