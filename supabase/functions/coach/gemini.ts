// Thin REST client for the Gemini structured-output call. Uses `fetch` directly instead of
// a heavy SDK so the Edge Function has minimal cold-start weight.
//
// NOTE: `gemini-3.6-flash` and a `thinking_level` parameter are specified by
// IMPLEMENTATION_PLAN.md but did not exist as a documented, callable model at the time this
// was written (this environment has no live Gemini API access - see HANDOFF.md). The request
// shape below follows the current (2025) Gemini `generateContent` REST contract as closely as
// possible: `generationConfig.responseMimeType` + `responseSchema` for structured output, and
// a `thinkingConfig` block for the thinking-level knob. If the real API uses different field
// names for `gemini-3.6-flash`, only GEMINI_API_URL/buildRequestBody below need to change.

import { GEMINI_RESPONSE_SCHEMA } from "./schema.ts";

const DEFAULT_MODEL = "gemini-3.6-flash";
const DEFAULT_THINKING_LEVEL = "medium";

export interface GeminiCallOptions {
  systemInstruction: string;
  userContent: string;
  apiKey: string;
  model?: string;
  thinkingLevel?: "low" | "medium" | "high";
}

export interface GeminiCallResult {
  ok: boolean;
  /** Raw text of the model's response (expected to be JSON matching GEMINI_RESPONSE_SCHEMA). */
  text: string | null;
  /** Set when the HTTP call itself failed or returned a non-2xx status. */
  httpError: string | null;
}

function buildRequestBody(opts: GeminiCallOptions) {
  return {
    systemInstruction: {
      role: "system",
      parts: [{ text: opts.systemInstruction }],
    },
    contents: [
      {
        role: "user",
        parts: [{ text: opts.userContent }],
      },
    ],
    generationConfig: {
      responseMimeType: "application/json",
      responseSchema: GEMINI_RESPONSE_SCHEMA,
      thinkingConfig: {
        thinkingLevel: opts.thinkingLevel ?? DEFAULT_THINKING_LEVEL,
      },
    },
  };
}

export async function callGemini(opts: GeminiCallOptions): Promise<GeminiCallResult> {
  const model = opts.model ?? DEFAULT_MODEL;
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`;

  let response: Response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": opts.apiKey,
      },
      body: JSON.stringify(buildRequestBody(opts)),
    });
  } catch (e) {
    return { ok: false, text: null, httpError: `network error calling Gemini: ${String(e)}` };
  }

  if (!response.ok) {
    const bodyText = await response.text().catch(() => "");
    return { ok: false, text: null, httpError: `Gemini HTTP ${response.status}: ${bodyText.slice(0, 500)}` };
  }

  try {
    const json = await response.json();
    const text = json?.candidates?.[0]?.content?.parts?.map((p: { text?: string }) => p.text ?? "").join("") ?? null;
    if (!text) {
      return { ok: false, text: null, httpError: "Gemini response had no candidate text" };
    }
    return { ok: true, text, httpError: null };
  } catch (e) {
    return { ok: false, text: null, httpError: `failed to parse Gemini response JSON: ${String(e)}` };
  }
}
