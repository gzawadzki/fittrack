// Core request-processing pipeline, decoupled from HTTP/Deno.serve and from the real Gemini
// network call, so it can be exercised directly by the eval harness (evals/harness.ts) with
// fixture contexts and a mock/live model function.

import type { CoachContext } from "./context.ts";
import type { CoachMode, CoachOutput } from "./types.ts";
import { buildPrompt } from "./prompt.ts";
import { validateCoachOutput, asCoachOutput } from "./schema.ts";
import { verifyCitations } from "./citations.ts";
import { enforceSafety } from "./safety.ts";
import { buildFallbackOutput } from "./fallback.ts";

export interface ModelCaller {
  (systemInstruction: string, userContent: string): Promise<{ ok: boolean; text: string | null; httpError: string | null }>;
}

export interface ProcessResult {
  output: CoachOutput;
  usedFallback: boolean;
  fallbackReason: string | null;
  validationErrors: string[];
  rejectedCitations: number;
  safetyOverridden: boolean;
  safetyNotes: string[];
}

export async function processCoachRequest(
  ctx: CoachContext,
  mode: CoachMode,
  message: string | undefined,
  knowledgeSources: string,
  callModel: ModelCaller,
): Promise<ProcessResult> {
  const { systemInstruction, userContent } = buildPrompt(ctx, mode, knowledgeSources, message);

  const modelResult = await callModel(systemInstruction, userContent);

  if (!modelResult.ok || !modelResult.text) {
    return {
      output: buildFallbackOutput(ctx),
      usedFallback: true,
      fallbackReason: modelResult.httpError ?? "model returned no text",
      validationErrors: [],
      rejectedCitations: 0,
      safetyOverridden: false,
      safetyNotes: [],
    };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(modelResult.text);
  } catch {
    return {
      output: buildFallbackOutput(ctx),
      usedFallback: true,
      fallbackReason: "model output was not valid JSON",
      validationErrors: ["root: not valid JSON"],
      rejectedCitations: 0,
      safetyOverridden: false,
      safetyNotes: [],
    };
  }

  const validation = validateCoachOutput(parsed);
  if (!validation.valid) {
    return {
      output: buildFallbackOutput(ctx),
      usedFallback: true,
      fallbackReason: "model output failed schema validation",
      validationErrors: validation.errors,
      rejectedCitations: 0,
      safetyOverridden: false,
      safetyNotes: [],
    };
  }

  let output = asCoachOutput(parsed);

  const citationCheck = await verifyCitations(output.citations);
  output = { ...output, citations: citationCheck.verified };

  let safetyOverridden = false;
  let safetyNotes: string[] = [];
  if (ctx.deterministic && ctx.targetId) {
    const enforced = enforceSafety(output, ctx.deterministic, ctx.targetId);
    output = enforced.output;
    safetyOverridden = enforced.overridden;
    safetyNotes = enforced.notes;
  } else if (ctx.deterministic == null) {
    // No workout data to ground a recommendation - never let the model propose LOAD/VOLUME.
    const before = output.suggestions.length;
    output = { ...output, suggestions: output.suggestions.filter((s) => s.type !== "LOAD" && s.type !== "VOLUME") };
    if (output.suggestions.length !== before) {
      safetyOverridden = true;
      safetyNotes.push("Stripped LOAD/VOLUME suggestion(s) proposed with no deterministic recommendation available.");
    }
  }

  return {
    output,
    usedFallback: false,
    fallbackReason: null,
    validationErrors: [],
    rejectedCitations: citationCheck.rejected.length,
    safetyOverridden,
    safetyNotes,
  };
}
