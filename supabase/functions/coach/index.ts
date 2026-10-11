// POST /coach - Edge Function entrypoint.
// Input: { mode, message?, workoutSessionId? }. Requires Supabase JWT (Authorization: Bearer).
// Output: { message, citations[], suggestions[], safety } (see IMPLEMENTATION_PLAN.md section 4).

import { authenticate } from "./auth.ts";
import { buildCoachContext } from "./context.ts";
import { manifestAsPromptContext } from "./citations.ts";
import { processCoachRequest } from "./core.ts";
import { persistPendingSuggestions } from "./persistence.ts";
import { callGemini } from "./gemini.ts";
import { errorResponse, jsonResponse, ErrorCodes } from "./errors.ts";
import type { CoachMode, CoachRequest } from "./types.ts";

const VALID_MODES: CoachMode[] = ["briefing", "chat", "debrief"];

function parseRequestBody(body: unknown): { ok: true; req: CoachRequest } | { ok: false; message: string } {
  if (typeof body !== "object" || body === null) {
    return { ok: false, message: "Request body must be a JSON object." };
  }
  const obj = body as Record<string, unknown>;
  if (typeof obj.mode !== "string" || !VALID_MODES.includes(obj.mode as CoachMode)) {
    return { ok: false, message: "`mode` must be one of briefing|chat|debrief." };
  }
  if (obj.message !== undefined && typeof obj.message !== "string") {
    return { ok: false, message: "`message` must be a string when present." };
  }
  if (obj.workoutSessionId !== undefined && typeof obj.workoutSessionId !== "string") {
    return { ok: false, message: "`workoutSessionId` must be a string when present." };
  }
  return {
    ok: true,
    req: {
      mode: obj.mode as CoachMode,
      message: obj.message as string | undefined,
      workoutSessionId: obj.workoutSessionId as string | undefined,
    },
  };
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return errorResponse(405, ErrorCodes.INVALID_REQUEST, "Only POST is supported.");
  }

  const auth = await authenticate(req);
  if (!auth.ok || !auth.userId || !auth.supabase) {
    return errorResponse(401, ErrorCodes.UNAUTHENTICATED, "A valid Supabase session is required.");
  }

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, ErrorCodes.INVALID_REQUEST, "Request body must be valid JSON.");
  }

  const parsedBody = parseRequestBody(body);
  if (!parsedBody.ok) {
    return errorResponse(400, ErrorCodes.INVALID_REQUEST, parsedBody.message);
  }
  const coachRequest = parsedBody.req;

  let ctx;
  try {
    ctx = await buildCoachContext(auth.supabase, auth.userId, coachRequest);
  } catch (e) {
    console.error("coach: failed to build context", String(e));
    return errorResponse(500, ErrorCodes.INTERNAL_ERROR, "Could not load context for the coach.", { retryable: true });
  }

  const knowledgeSources = await manifestAsPromptContext();

  const apiKey = Deno.env.get("GEMINI_API_KEY");
  if (!apiKey) {
    // No model access configured - the controlled fallback still returns a valid, grounded
    // response computed entirely from deterministic logic.
    const { buildFallbackOutput } = await import("./fallback.ts");
    const output = buildFallbackOutput(ctx);
    await persistPendingSuggestions(auth.userId, output.suggestions);
    return jsonResponse(output, 200);
  }

  const result = await processCoachRequest(
    ctx,
    coachRequest.mode,
    coachRequest.message,
    knowledgeSources,
    (systemInstruction, userContent) => callGemini({ systemInstruction, userContent, apiKey }),
  );

  if (result.usedFallback) {
    console.warn("coach: used fallback", result.fallbackReason, result.validationErrors);
  }
  if (result.safetyOverridden) {
    console.warn("coach: safety override applied", result.safetyNotes);
  }

  await persistPendingSuggestions(auth.userId, result.output.suggestions);

  return jsonResponse(result.output, 200);
});
