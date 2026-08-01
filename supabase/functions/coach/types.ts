// Shared types for the `coach` Edge Function.
// Mirrors the frozen contracts in IMPLEMENTATION_PLAN.md section 4.
// Timestamps at module boundaries are UTC ISO-8601. Weight is Double kg. IDs are client-generated UUIDs.

export type SuggestionType = "LOAD" | "VOLUME" | "SWAP" | "RESCHEDULE" | "HOLD";
export type Confidence = "LOW" | "MEDIUM" | "HIGH";
export type CoachMode = "briefing" | "chat" | "debrief";

export interface Citation {
  title: string;
  url: string;
  claim: string;
}

export interface Suggestion {
  type: SuggestionType;
  targetId: string;
  before: string;
  after: string;
  reasonCode: string;
  explanation: string;
  signals: string[];
  confidence: Confidence;
}

export interface Safety {
  blocked: boolean;
  reason: string | null;
}

/** Structured output the model must produce, and what we persist/return. */
export interface CoachOutput {
  message: string;
  citations: Citation[];
  suggestions: Suggestion[];
  safety: Safety;
}

export interface CoachRequest {
  mode: CoachMode;
  message?: string;
  workoutSessionId?: string;
}

/** POST /coach response body: message, citations[], suggestions[] (safety is included per section 4 schema). */
export interface CoachResponse {
  message: string;
  citations: Citation[];
  suggestions: Suggestion[];
  safety: Safety;
}

export interface ApiError {
  error: {
    code: string;
    message: string;
    retryable: boolean;
    retryAfterSeconds: number | null;
  };
}

export function apiError(
  code: string,
  message: string,
  opts: { retryable?: boolean; retryAfterSeconds?: number | null } = {},
): ApiError {
  return {
    error: {
      code,
      message,
      retryable: opts.retryable ?? false,
      retryAfterSeconds: opts.retryAfterSeconds ?? null,
    },
  };
}
