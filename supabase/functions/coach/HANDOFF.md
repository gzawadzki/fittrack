# M7 handoff — coach, knowledge-reindex, knowledge, evals

Owner: Agent 7. Exclusive paths: `supabase/functions/coach/`, `supabase/functions/knowledge-reindex/`, `knowledge/`, `evals/`.

## What works

### `POST /coach` (`supabase/functions/coach/index.ts`)

- Verifies the Supabase JWT (`auth.ts`) before doing anything else; 401s otherwise.
- Validates the request body (`mode` in `briefing|chat|debrief`, optional `message`, optional `workoutSessionId`).
- Builds a **minimal grounding context** (`context.ts`): profile summary, Fitbit **aggregates only**
  (never raw HR samples), a short recent-workout summary, and a **deterministic pre-computed
  recommendation**.
- The deterministic recommendation comes from `progression.ts`, a TypeScript port of
  `app/src/main/java/com/fittrack/app/domain/ProgressionEngine.kt` (owned by M1/core/training).
  It is a **deliberate duplication, not an import** — core/training is JVM/Kotlin and cannot run
  inside a Deno Edge Function. If M1 changes the business rules in section 5, this file must be
  updated to match (an ADR + simultaneous update per plan section 6.3).
- The model (`gemini.ts`, `prompt.ts`) receives the deterministic recommendation as **ground
  truth to explain**, never as something to compute. The system prompt explicitly forbids the
  model from proposing a LOAD/VOLUME increase that contradicts it, and forbids diagnosing pain.
- **Schema validation** (`schema.ts`) is hand-rolled (no AJV dependency) and mirrors the frozen
  structured-output contract from `IMPLEMENTATION_PLAN.md` section 4 exactly.
- **Citation grounding** (`citations.ts`): every citation the model returns is checked against
  `knowledge/manifest.json` by normalized URL. Anything that doesn't match is **dropped**, never
  "repaired" or passed through — the coach can under-cite but never fabricates.
- **Safety enforcement** (`safety.ts`) is a second, independent layer on top of the model's own
  `safety.blocked` field: whenever the deterministic engine says `STOP_AND_REVIEW` (pain or a
  concerning symptom), the pipeline **forces** `safety.blocked = true` and **strips** any
  LOAD/VOLUME-increase suggestion on the affected target, regardless of what the model returned.
  This is what makes the pipeline robust to prompt-injection attempts embedded in a workout note
  or chat message — the enforcement doesn't depend on the model resisting the injection.
- **Controlled fallback** (`fallback.ts`): if the model call fails, times out, returns non-JSON,
  or fails schema validation, the endpoint returns a schema-valid `CoachOutput` built entirely
  from the deterministic recommendation (no model text, no citations). The endpoint **never**
  returns an unhandled or malformed response.
- **Persistence** (`persistence.ts`): every suggestion the endpoint returns is inserted with
  `status = 'PENDING'` only. There is no update/delete path in this module. The model is never
  given a tool that writes a plan change — approval/rejection is the separate
  `suggestion-decision` flow (M5).

### `POST /knowledge-reindex` (`supabase/functions/knowledge-reindex/index.ts`)

- Protected by a shared secret (`KNOWLEDGE_REINDEX_SECRET`), not a user JWT — this is an
  operator/admin action, not something the mobile app calls directly.
- Every source it will index must **already** appear, byte-identical `id`/`url`/`file`, in the
  checked-in `knowledge/manifest.json`. This is what lets the coach function trust that any
  citation whose URL matches the manifest really was indexed from a curated document.
- Reads and checksums (`sha256`) the referenced file under `knowledge/sources/`; if
  `GEMINI_API_KEY` is set it also attempts a best-effort upload to Gemini's Files API (untested
  against a live key in this environment — see Known limitations).
- Returns `{ indexed, failed[], storeId }`.

### `knowledge/`

- `manifest.json` lists 7 curated sources with `id`, `title`, `file`, `url`, `origin`, `topics`.
- `sources/*.md` are short, hand-curated summaries (not verbatim copies) of established training
  concepts: progressive overload, RIR/autoregulation, deload/fatigue management, sleep+RHR
  readiness, pain-vs-soreness safety screening, volume landmarks, exercise-swap principles.
- The `url` field is the trust anchor `citations.ts` matches against — do not change a `url`
  without also updating any eval fixtures that reference it (`evals/fixtures.ts`).

### `evals/`

42 cases total, all runnable offline right now (see "How to run the evals" below):

- `progressionCases.ts` — 12 cases, run **directly** against `ProgressionEngine` (no model
  involved at all — this is real verification, not simulated).
- `pipelineCases.ts` — 30 cases run through the **full** `core.processCoachRequest` pipeline
  against mock model responses, split across the four required categories:
  - `CONFLICTING_SIGNALS` (8)
  - `PROMPT_INJECTION` (8)
  - `MISSING_SOURCE_CITATION` (6)
  - `SAFETY_BLOCKING` (8)

Every pipeline case is also checked, unconditionally, for the two structural DoD guarantees:
final output is schema-valid or `usedFallback` is true, and every returned citation traces back
to `knowledge/manifest.json`.

## How to run the evals

**With Deno (canonical — this is a Deno Edge Function project):**

```sh
deno run --allow-read evals/run.ts
```

**Without Deno (this development environment had no `deno` binary installed):** a small Node
shim (`evals/_node_shim_run.mjs`) polyfills just the `Deno.readTextFile`/`Deno.env` surface the
pure-logic modules touch, and runs on Node 22.6+/24 via native TS type-stripping:

```sh
node --experimental-strip-types evals/_node_shim_run.mjs
```

Both entrypoints exit non-zero if any case fails, so either is CI-friendly.

### Actual result from this environment (2026-08-01)

Ran via the Node shim (`node --experimental-strip-types evals/_node_shim_run.mjs`):

```
42/42 passed (100.0%)
PROGRESSION_CORRECTNESS      12/12
CONFLICTING_SIGNALS          8/8
PROMPT_INJECTION             8/8
MISSING_SOURCE_CITATION      6/6
SAFETY_BLOCKING              8/8
```

This is a **real** run of the actual pipeline code (`core.ts`, `schema.ts`, `citations.ts`,
`safety.ts`, `fallback.ts`, `progression.ts`) against **mock/simulated model responses** — this
environment has no live `GEMINI_API_KEY` / network access to the real Gemini API, so no
end-to-end call to `gemini-3.6-flash` was made. The mock responses in `evals/mockModel.ts` +
`evals/pipelineCases.ts` deliberately include well-behaved, malformed, and adversarial
(injection-compliant, citation-fabricating, safety-unblocking) responses specifically so the
deterministic enforcement layers are what's being proven, not the model's own good behavior.
**Do not read the 100% figure above as a live-model eval pass rate** — it is a pass rate against
the validation/enforcement pipeline under a fixed, hand-authored set of model response fixtures.

### Running against the real Gemini API

Once `GEMINI_API_KEY` is set as a Supabase secret:

1. Swap the mock `model:` callers in `evals/pipelineCases.ts` for a live caller, e.g.:
   ```ts
   import { callGemini } from "../supabase/functions/coach/gemini.ts";
   const liveModel: ModelCaller = (systemInstruction, userContent) =>
     callGemini({ systemInstruction, userContent, apiKey: Deno.env.get("GEMINI_API_KEY")! });
   ```
2. Re-run `deno run --allow-read --allow-net --allow-env evals/run.ts`.
3. Expect the *shape* of the pass rate to hold (schema validity + fallback + safety enforcement
   are pipeline-level guarantees independent of the model), but individual case expectations
   that assume a "well-behaved" model reply (e.g. `mc-01`, `cs-01`) may need loosening since a
   real model's exact wording/citation choices will vary — only the structural assertions
   (`mustBlockSafety`, `mustNotProposeIncrease`, citation-in-manifest) are guaranteed to hold.
4. Verify the request/response shape in `gemini.ts` against the real `gemini-3.6-flash` API
   docs once available — `thinkingConfig.thinkingLevel` and the model id itself were not a
   documented, callable contract at the time this was written; see the note at the top of
   `gemini.ts`.

## Required secrets (Supabase Edge Function secrets)

| Secret | Used by | Purpose |
|---|---|---|
| `GEMINI_API_KEY` | `coach`, `knowledge-reindex` | Calls to the Gemini API. If unset, `coach` degrades to the deterministic-only fallback response (still schema-valid, still safe) instead of failing. |
| `KNOWLEDGE_REINDEX_SECRET` | `knowledge-reindex` | Shared secret required in the `x-reindex-secret` header; this endpoint is operator-only, not user-facing. |
| `SUPABASE_URL`, `SUPABASE_ANON_KEY` | `coach` | Used to build a JWT-scoped Supabase client for context reads (RLS applies). Standard Edge Function env vars, not something this module sets. |
| File Search store id | not yet wired | `knowledge-reindex` returns a deterministic `storeId` today (hash of indexed content) whether or not a real Gemini File Search store exists; there is no separate stored config for a real store id yet. If/when live File Search is wired up, that id should be persisted (table owned by M5, TBD) rather than recomputed per call. |

## Known limitations / assumptions

1. **No live Gemini access in this environment.** `gemini.ts` and the Files-API upload path in
   `knowledge-reindex/index.ts` are written against the current (2025) Gemini REST contract as
   closely as I could and are believed correct, but were never exercised against a real
   `gemini-3.6-flash` endpoint. Verify field names (especially `thinkingConfig`) before shipping.
2. **DB schema not yet published by M5 at the time this was written.** `context.ts` and
   `persistence.ts` query best-guess table/column names (`profiles`, `fitbit_daily_aggregates`,
   `workout_sessions`, `workout_sets`, `coach_suggestions`) inferred from the domain types in
   plan section 4. All reads are defensive (a missing table degrades to a neutral default rather
   than throwing) so `coach` won't 500 while the real schema lands, but the query bodies in those
   two files will need a follow-up pass once M5's migrations exist. The `CoachContext`/
   `Suggestion` shapes those functions produce are what the rest of the pipeline depends on and
   should not need to change.
3. **Text-only safety signals are a real gap (see eval `sb-08`).** The deterministic engine only
   sees pain/technique/symptom flags that are structured per-set data. A concerning symptom
   mentioned only in free chat text is not seen by `progression.ts`, so `safety.ts` cannot force
   a block on it — it relies entirely on the model itself choosing to set `safety.blocked = true`
   in that case. The enforcement layer only ever forces `blocked` **on**, never off, so a
   model-initiated block from free text is preserved, but a model that misses/ignores it is not
   currently caught by anything deterministic. If this needs to be hardened, the fix belongs in
   `safety.ts` as a keyword/heuristic pre-screen over `message`/notes, similar to
   `looksLikePromptInjection`, forcing a block on concerning-symptom language even without a
   structured flag.
4. **`knowledge-reindex`'s real File Search wiring is unverified.** The endpoint proves the
   integrity/manifest-matching logic for real (checksum, id/url/file match) but the actual
   Gemini Files/File-Search upload call is best-effort and untested.
5. **Citation grounding is prompt-only, not retrieval-based.** The full manifest (titles + URLs +
   topics) is included in every prompt as `KNOWLEDGE_SOURCES`, rather than doing a real
   File-Search retrieval call per request. This is fine at the current manifest size (7 sources)
   but won't scale indefinitely — a real File Search retrieval step should replace
   `manifestAsPromptContext()` in `citations.ts` once `knowledge-reindex` is confirmed working
   end-to-end against a live store.
6. **`suggestion-decision` integration is a stub.** `persistence.ts` inserts `PENDING` rows into
   an assumed `coach_suggestions` table; M5 owns the actual approve/reject flow and the real
   table shape. Confirm the table name/columns with M5 before relying on persisted suggestions
   surfacing correctly in the M3 UI.

## Definition of done — self-check

- [x] 100% of (simulated) eval outputs pass schema validation or hit the controlled fallback —
      verified structurally in `harness.ts` for every one of the 30 pipeline cases, not just
      asserted; see run output above.
- [x] 100% of the 8 safety-triggering pipeline cases block progression (`mustBlockSafety`/
      `mustNotProposeIncrease` all pass) — including malformed-output and pure-hallucination
      cases with no injection attempt at all.
- [x] Every knowledge-grounded pipeline case either returns a citation traceable to
      `knowledge/manifest.json` or explicitly says no source was found (`mc-03`); fabricated/
      mismatched citations are always dropped (`mc-02`, `mc-04`, `mc-06`).
- [ ] Not verified against the real Gemini API (no credentials in this environment — see "Known
      limitations" #1).
