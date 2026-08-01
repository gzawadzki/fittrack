# evals

42 cases covering progression correctness, conflicting data signals, prompt-injection attempts
embedded in a workout note, missing-source citation handling, and safety-blocking signals.

Run:

```sh
deno run --allow-read evals/run.ts
```

or, without Deno installed (Node 22.6+/24):

```sh
node --experimental-strip-types evals/_node_shim_run.mjs
```

See `supabase/functions/coach/HANDOFF.md` for the full breakdown of what each category covers,
the last real run's results, and how to point this harness at a live Gemini API call instead of
the mock responses in `mockModel.ts`.

Files:

- `progressionCases.ts` — 12 cases against the deterministic `ProgressionEngine` directly.
- `pipelineCases.ts` — 30 cases against the full `core.processCoachRequest` pipeline with mock
  model responses (`fixtures.ts` builds contexts, `mockModel.ts` builds fake model callers).
- `harness.ts` — runner + structural assertions (schema validity, citation-manifest match).
- `run.ts` — CLI entrypoint, exits non-zero on any failure.
