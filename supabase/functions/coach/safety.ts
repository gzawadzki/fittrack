// Safety enforcement. The model's own `safety.blocked` field is advisory only — the
// deterministic progression engine (progression.ts) is the source of truth for whether
// progression must be blocked. This module makes that enforcement non-optional: even if
// the model ignores instructions or is manipulated via a prompt-injection attempt embedded
// in a workout note, load/volume-increasing suggestions are stripped whenever the
// deterministic engine says STOP_AND_REVIEW.

import type { CoachOutput, Suggestion } from "./types.ts";
import type { ProgressionRecommendation } from "./progression.ts";

const PROGRESSION_TYPES_THAT_INCREASE = new Set(["LOAD", "VOLUME"]);

export interface SafetyEnforcementResult {
  output: CoachOutput;
  overridden: boolean;
  notes: string[];
}

/**
 * Forces safety.blocked = true and strips any suggestion that would increase load/volume
 * whenever the deterministic engine flagged STOP_AND_REVIEW (pain / concerning symptom).
 * Technique issues and holds are enforced by never allowing an INCREASE suggestion for the
 * affected target unless the deterministic recommendation itself is INCREASE_LOAD.
 */
export function enforceSafety(
  output: CoachOutput,
  deterministic: ProgressionRecommendation,
  targetId: string,
): SafetyEnforcementResult {
  const notes: string[] = [];
  let overridden = false;

  let suggestions: Suggestion[] = output.suggestions;
  let safety = output.safety;

  if (deterministic.action === "STOP_AND_REVIEW") {
    const before = suggestions.length;
    suggestions = suggestions.filter((s) => {
      const isIncreaseOnTarget = s.targetId === targetId && PROGRESSION_TYPES_THAT_INCREASE.has(s.type);
      return !isIncreaseOnTarget;
    });
    if (suggestions.length !== before) {
      overridden = true;
      notes.push("Stripped load/volume-increase suggestion(s) on a target flagged PAIN_OR_SYMPTOM.");
    }
    if (!safety.blocked) {
      overridden = true;
      notes.push("Forced safety.blocked=true; deterministic engine flagged STOP_AND_REVIEW.");
    }
    safety = {
      blocked: true,
      reason: safety.reason ?? deterministic.explanation,
    };
  }

  // Never allow a suggestion to increase load beyond what the deterministic engine computed.
  // If the model proposed a LOAD increase for the primary target but the engine says HOLD or
  // DECREASE, downgrade the suggestion's `after` is out of scope here (that's the model's
  // explanatory text) but we still block it if the deterministic action forbids increase.
  if (deterministic.action === "HOLD_LOAD" || deterministic.action === "DECREASE_LOAD" || deterministic.action === "REDUCE_VOLUME") {
    const before = suggestions.length;
    suggestions = suggestions.filter((s) => {
      const isForbiddenIncrease = s.targetId === targetId && s.type === "LOAD" && deterministicSuggestsIncrease(s, deterministic) === false && isIncreaseSuggestion(s);
      return !isForbiddenIncrease;
    });
    if (suggestions.length !== before) {
      overridden = true;
      notes.push(`Stripped a LOAD-increase suggestion that contradicted the deterministic action ${deterministic.action}.`);
    }
  }

  return {
    output: { ...output, suggestions, safety },
    overridden,
    notes,
  };
}

function isIncreaseSuggestion(s: Suggestion): boolean {
  const before = parseFloat(s.before);
  const after = parseFloat(s.after);
  if (Number.isNaN(before) || Number.isNaN(after)) return false;
  return after > before;
}

function deterministicSuggestsIncrease(_s: Suggestion, rec: ProgressionRecommendation): boolean {
  return rec.action === "INCREASE_LOAD";
}

/**
 * Heuristic detector for prompt-injection patterns inside free-text user content (e.g. a
 * workout note). This does NOT change how the note is handled (it is always treated as
 * inert data, never as instructions - see prompt.ts) but is used by evals and logging to
 * confirm injection attempts did not change model behavior.
 */
export function looksLikePromptInjection(text: string): boolean {
  const patterns = [
    /ignore (all|any|previous|the above) instructions/i,
    /disregard (all|any|previous|the above)/i,
    /you are now/i,
    /system prompt/i,
    /reveal (the|your) (system|hidden) prompt/i,
    /act as (a|an) (?!coach)/i,
    /\bDAN\b/,
    /forget (all|everything) (above|before)/i,
    /new instructions:/i,
    /override safety/i,
    /always (approve|increase|block)/i,
    /set (blocked|safety) to false/i,
  ];
  return patterns.some((p) => p.test(text));
}
