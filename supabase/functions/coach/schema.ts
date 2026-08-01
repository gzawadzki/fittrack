// JSON schema (for Gemini structured output config) and a hand-rolled runtime validator
// for the trainer structured output contract (IMPLEMENTATION_PLAN.md section 4).
//
// We validate manually instead of pulling in an AJV dependency: the schema is small,
// stable, and this keeps the Edge Function's cold start fast and dependency-free.

import type { CoachOutput, Suggestion, Citation } from "./types.ts";

const SUGGESTION_TYPES = new Set(["LOAD", "VOLUME", "SWAP", "RESCHEDULE", "HOLD"]);
const CONFIDENCE_LEVELS = new Set(["LOW", "MEDIUM", "HIGH"]);

/** Gemini `responseSchema` — mirrors the frozen contract exactly; no additional fields defined. */
export const GEMINI_RESPONSE_SCHEMA = {
  type: "OBJECT",
  properties: {
    message: { type: "STRING" },
    citations: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          title: { type: "STRING" },
          url: { type: "STRING" },
          claim: { type: "STRING" },
        },
        required: ["title", "url", "claim"],
      },
    },
    suggestions: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          type: { type: "STRING", enum: ["LOAD", "VOLUME", "SWAP", "RESCHEDULE", "HOLD"] },
          targetId: { type: "STRING" },
          before: { type: "STRING" },
          after: { type: "STRING" },
          reasonCode: { type: "STRING" },
          explanation: { type: "STRING" },
          signals: { type: "ARRAY", items: { type: "STRING" } },
          confidence: { type: "STRING", enum: ["LOW", "MEDIUM", "HIGH"] },
        },
        required: ["type", "targetId", "before", "after", "reasonCode", "explanation", "signals", "confidence"],
      },
    },
    safety: {
      type: "OBJECT",
      properties: {
        blocked: { type: "BOOLEAN" },
        reason: { type: "STRING", nullable: true },
      },
      required: ["blocked", "reason"],
    },
  },
  required: ["message", "citations", "suggestions", "safety"],
} as const;

export interface ValidationResult {
  valid: boolean;
  errors: string[];
}

function isString(v: unknown): v is string {
  return typeof v === "string";
}

function isValidUrl(v: string): boolean {
  try {
    const u = new URL(v);
    return u.protocol === "https:";
  } catch {
    return false;
  }
}

function validateCitation(c: unknown, path: string, errors: string[]): c is Citation {
  if (typeof c !== "object" || c === null) {
    errors.push(`${path}: expected object`);
    return false;
  }
  const obj = c as Record<string, unknown>;
  let ok = true;
  if (!isString(obj.title) || obj.title.trim() === "") {
    errors.push(`${path}.title: missing or empty`);
    ok = false;
  }
  if (!isString(obj.url) || !isValidUrl(obj.url)) {
    errors.push(`${path}.url: missing or not a valid https URL`);
    ok = false;
  }
  if (!isString(obj.claim) || obj.claim.trim() === "") {
    errors.push(`${path}.claim: missing or empty`);
    ok = false;
  }
  return ok;
}

function validateSuggestion(s: unknown, path: string, errors: string[]): s is Suggestion {
  if (typeof s !== "object" || s === null) {
    errors.push(`${path}: expected object`);
    return false;
  }
  const obj = s as Record<string, unknown>;
  let ok = true;
  if (!isString(obj.type) || !SUGGESTION_TYPES.has(obj.type)) {
    errors.push(`${path}.type: must be one of LOAD|VOLUME|SWAP|RESCHEDULE|HOLD`);
    ok = false;
  }
  if (!isString(obj.targetId) || obj.targetId.trim() === "") {
    errors.push(`${path}.targetId: missing`);
    ok = false;
  }
  for (const field of ["before", "after", "reasonCode", "explanation"]) {
    if (!isString(obj[field])) {
      errors.push(`${path}.${field}: missing or not a string`);
      ok = false;
    }
  }
  if (!Array.isArray(obj.signals) || !obj.signals.every(isString)) {
    errors.push(`${path}.signals: must be a string array`);
    ok = false;
  }
  if (!isString(obj.confidence) || !CONFIDENCE_LEVELS.has(obj.confidence)) {
    errors.push(`${path}.confidence: must be one of LOW|MEDIUM|HIGH`);
    ok = false;
  }
  return ok;
}

/**
 * Validates a parsed model response against the frozen trainer output contract.
 * Extra/unknown fields are ignored (backend ignores additional fields per section 4);
 * missing required fields fail validation.
 */
export function validateCoachOutput(input: unknown): ValidationResult {
  const errors: string[] = [];
  if (typeof input !== "object" || input === null) {
    return { valid: false, errors: ["root: expected object"] };
  }
  const obj = input as Record<string, unknown>;

  if (!isString(obj.message) || obj.message.trim() === "") {
    errors.push("message: missing or empty");
  }

  if (!Array.isArray(obj.citations)) {
    errors.push("citations: missing or not an array");
  } else {
    obj.citations.forEach((c, i) => validateCitation(c, `citations[${i}]`, errors));
  }

  if (!Array.isArray(obj.suggestions)) {
    errors.push("suggestions: missing or not an array");
  } else {
    obj.suggestions.forEach((s, i) => validateSuggestion(s, `suggestions[${i}]`, errors));
  }

  if (typeof obj.safety !== "object" || obj.safety === null) {
    errors.push("safety: missing or not an object");
  } else {
    const safety = obj.safety as Record<string, unknown>;
    if (typeof safety.blocked !== "boolean") {
      errors.push("safety.blocked: missing or not a boolean");
    }
    if (safety.reason !== null && !isString(safety.reason)) {
      errors.push("safety.reason: must be a string or null");
    }
  }

  return { valid: errors.length === 0, errors };
}

/** Narrows unknown JSON to CoachOutput after validateCoachOutput() has returned valid: true. */
export function asCoachOutput(input: unknown): CoachOutput {
  return input as CoachOutput;
}
