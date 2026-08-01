// Thin REST client for OpenRouter's OpenAI-compatible chat completions endpoint. Uses `fetch`
// directly instead of the `@openrouter/sdk` npm package to keep the Edge Function's cold start
// fast and dependency-free (same rationale as the earlier Gemini client this replaced).
//
// Structured output: OpenRouter/DeepSeek support `response_format: { type: "json_object" }`
// (JSON mode) rather than Gemini's schema-enforced `responseSchema`. The exact shape is instead
// spelled out in the system prompt (see prompt.ts) and, as with every model call in this
// pipeline, still goes through core.ts's full manual validateCoachOutput() - an invalid or
// malformed response degrades to the deterministic fallback rather than being trusted blindly.

const DEFAULT_MODEL = "deepseek/deepseek-v4-flash-0731";

export interface OpenRouterCallOptions {
  systemInstruction: string;
  userContent: string;
  apiKey: string;
  model?: string;
}

export interface OpenRouterCallResult {
  ok: boolean;
  /** Raw text of the model's response (expected to be JSON matching the coach output contract). */
  text: string | null;
  /** Set when the HTTP call itself failed or returned a non-2xx status. */
  httpError: string | null;
}

const REQUEST_TIMEOUT_MS = 25_000;
const RETRY_DELAY_MS = 500;

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const isAbortError = (e: unknown) => e instanceof Error && e.name === "AbortError";

/** 429 and 5xx are transient upstream conditions; other 4xx are caller errors and stay fatal. */
const isRetryableStatus = (status: number) => status === 429 || status >= 500;

export async function callOpenRouter(opts: OpenRouterCallOptions): Promise<OpenRouterCallResult> {
  const model = opts.model ?? DEFAULT_MODEL;
  const url = "https://openrouter.ai/api/v1/chat/completions";
  const body = JSON.stringify({
    model,
    messages: [
      { role: "system", content: opts.systemInstruction },
      { role: "user", content: opts.userContent },
    ],
    response_format: { type: "json_object" },
  });

  let response: Response | null = null;
  let failure: OpenRouterCallResult | null = null;

  for (let attempt = 0; attempt < 2; attempt++) {
    if (attempt > 0) await sleep(RETRY_DELAY_MS);

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      response = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${opts.apiKey}`,
        },
        body,
        signal: controller.signal,
      });
    } catch (e) {
      response = null;
      if (isAbortError(e)) {
        return { ok: false, text: null, httpError: "OpenRouter request timed out after 25s" };
      }
      failure = { ok: false, text: null, httpError: `network error calling OpenRouter: ${String(e)}` };
      continue;
    } finally {
      clearTimeout(timeoutId);
    }

    if (response.ok) {
      failure = null;
      break;
    }

    const bodyText = await response.text().catch(() => "");
    failure = {
      ok: false,
      text: null,
      httpError: `OpenRouter HTTP ${response.status}: ${bodyText.slice(0, 500)}`,
    };
    if (!isRetryableStatus(response.status)) break;
    response = null;
  }

  if (failure || !response) {
    return failure ?? { ok: false, text: null, httpError: "network error calling OpenRouter" };
  }

  try {
    const json = await response.json();
    const text = json?.choices?.[0]?.message?.content ?? null;
    if (!text) {
      return { ok: false, text: null, httpError: "OpenRouter response had no choice content" };
    }
    return { ok: true, text, httpError: null };
  } catch (e) {
    return { ok: false, text: null, httpError: `failed to parse OpenRouter response JSON: ${String(e)}` };
  }
}
