// Eval harness. Runs:
//   1. progressionCases against the deterministic ProgressionEngine directly (real, not simulated).
//   2. pipelineCases through the full coach pipeline (core.ts) with mock model responses
//      (simulated, since no live Gemini API access is available in this environment - see
//      evals/README.md for how to run in LIVE mode against a real key).
//
// For every pipeline case it additionally verifies, unconditionally, the two structural
// guarantees required by the M7 Definition of Done:
//   - the final output passes schema validation (schema.ts) OR usedFallback is true
//     (a fallback is by construction always schema-valid - see fallback.ts/buildFallbackOutput).
//   - every citation in the final output traces back to knowledge/manifest.json.

import { ProgressionEngine } from "../supabase/functions/coach/progression.ts";
import { validateCoachOutput } from "../supabase/functions/coach/schema.ts";
import { loadManifest } from "../supabase/functions/coach/citations.ts";
import { processCoachRequest } from "../supabase/functions/coach/core.ts";
import { progressionCases } from "./progressionCases.ts";
import { pipelineCases } from "./pipelineCases.ts";
import { manifestAsPromptContext } from "../supabase/functions/coach/citations.ts";

export interface CaseResult {
  id: string;
  category: string;
  pass: boolean;
  failures: string[];
}

export interface Summary {
  total: number;
  passed: number;
  failed: number;
  byCategory: Record<string, { total: number; passed: number }>;
  results: CaseResult[];
}

function record(results: CaseResult[], id: string, category: string, failures: string[]) {
  results.push({ id, category, pass: failures.length === 0, failures });
}

async function runProgressionCases(results: CaseResult[]) {
  for (const c of progressionCases) {
    const rec = ProgressionEngine.recommend(c.input);
    const failures: string[] = [];
    if (rec.action !== c.expectedAction) {
      failures.push(`expected action ${c.expectedAction}, got ${rec.action}`);
    }
    if (rec.reasonCode !== c.expectedReasonCode) {
      failures.push(`expected reasonCode ${c.expectedReasonCode}, got ${rec.reasonCode}`);
    }
    if (c.expectedWeightKg !== undefined && rec.proposedWeightKg !== c.expectedWeightKg) {
      failures.push(`expected proposedWeightKg ${c.expectedWeightKg}, got ${rec.proposedWeightKg}`);
    }
    record(results, c.id, "PROGRESSION_CORRECTNESS", failures);
  }
}

async function runPipelineCases(results: CaseResult[]) {
  const manifest = await loadManifest();
  const manifestUrls = new Set(manifest.sources.map((s) => s.url));
  const knowledgeSources = await manifestAsPromptContext();

  for (const c of pipelineCases) {
    const failures: string[] = [];
    const result = await processCoachRequest(c.context, c.mode, c.message, knowledgeSources, c.model);

    // Structural guarantee 1: schema-valid or controlled fallback (fallback is schema-valid by
    // construction, but we re-validate it too as a regression guard).
    const validation = validateCoachOutput(result.output);
    if (!validation.valid) {
      failures.push(`final output failed schema validation: ${validation.errors.join("; ")}`);
    }
    if (result.usedFallback && !validation.valid) {
      failures.push("fallback output was not schema-valid (fallback.ts regression)");
    }

    // Structural guarantee 2: every citation traces back to the manifest.
    for (const cit of result.output.citations) {
      if (!manifestUrls.has(cit.url)) {
        failures.push(`citation url not present in knowledge/manifest.json: ${cit.url}`);
      }
    }

    if (c.expect.mustUseFallback && !result.usedFallback) {
      failures.push("expected usedFallback=true");
    }
    if (c.expect.mustBlockSafety && result.output.safety.blocked !== true) {
      failures.push("expected safety.blocked=true");
    }
    if (c.expect.mustNotProposeIncrease) {
      const hasIncrease = result.output.suggestions.some((s) => s.type === "LOAD" || s.type === "VOLUME" ? isIncrease(s) : false);
      if (hasIncrease) {
        failures.push(`expected no LOAD/VOLUME increase suggestion, got: ${JSON.stringify(result.output.suggestions)}`);
      }
    }
    if (c.expect.minCitations !== undefined && result.output.citations.length < c.expect.minCitations) {
      failures.push(`expected at least ${c.expect.minCitations} citation(s), got ${result.output.citations.length}`);
    }
    if (c.expect.maxCitations !== undefined && result.output.citations.length > c.expect.maxCitations) {
      failures.push(`expected at most ${c.expect.maxCitations} citation(s), got ${result.output.citations.length}`);
    }

    record(results, c.id, c.category, failures);
  }
}

function isIncrease(s: { type: string; before: string; after: string }): boolean {
  if (s.type === "VOLUME") {
    // VOLUME suggestions use human-readable set counts (e.g. "3 sets" -> "2 sets"); a VOLUME
    // suggestion is only a problem here if it increases (rare) - our fixtures only ever
    // propose volume decreases, so treat any VOLUME suggestion as non-increase unless the
    // numeric prefix clearly goes up.
    const beforeN = parseFloat(s.before);
    const afterN = parseFloat(s.after);
    if (Number.isNaN(beforeN) || Number.isNaN(afterN)) return false;
    return afterN > beforeN;
  }
  const beforeN = parseFloat(s.before);
  const afterN = parseFloat(s.after);
  if (Number.isNaN(beforeN) || Number.isNaN(afterN)) return true; // can't verify -> treat conservatively as an increase
  return afterN > beforeN;
}

export async function runAll(): Promise<Summary> {
  const results: CaseResult[] = [];
  await runProgressionCases(results);
  await runPipelineCases(results);

  const byCategory: Record<string, { total: number; passed: number }> = {};
  for (const r of results) {
    byCategory[r.category] ??= { total: 0, passed: 0 };
    byCategory[r.category].total += 1;
    if (r.pass) byCategory[r.category].passed += 1;
  }

  return {
    total: results.length,
    passed: results.filter((r) => r.pass).length,
    failed: results.filter((r) => !r.pass).length,
    byCategory,
    results,
  };
}
