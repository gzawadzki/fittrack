// Mock model callers used by pipeline eval cases. Since this environment has no live Gemini
// API access, these simulate the range of outputs a real model call could plausibly produce
// (well-behaved, malformed, adversarial/injected, fabricated-citation, network failure) so the
// validation/enforcement pipeline in supabase/functions/coach/core.ts can be exercised for
// real. See evals/README.md for how to swap these for a live callGemini() once a
// GEMINI_API_KEY is available.

import type { ModelCaller } from "../supabase/functions/coach/core.ts";

export function jsonModel(value: unknown): ModelCaller {
  return async () => ({ ok: true, text: JSON.stringify(value), httpError: null });
}

export function rawTextModel(text: string): ModelCaller {
  return async () => ({ ok: true, text, httpError: null });
}

export function httpErrorModel(message: string): ModelCaller {
  return async () => ({ ok: false, text: null, httpError: message });
}

export function malformedJsonModel(): ModelCaller {
  return async () => ({ ok: true, text: "{ this is not valid json ]", httpError: null });
}
