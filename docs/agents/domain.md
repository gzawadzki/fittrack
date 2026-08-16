# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT-MAP.md`** at the repo root — it points at one `CONTEXT.md` per module/context. Read each one relevant to the topic.
- **`docs/adr/`** — system-wide ADRs (e.g. `0001-fitbit-health-connect-pivot.md`). Also check `<module-path>/docs/adr/` for module-scoped decisions.

If any of these files don't exist yet for a given module, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill creates them lazily when terms or decisions actually get resolved.

## File structure

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← system-wide decisions (e.g. Health Connect pivot)
├── app/
├── core/
│   ├── model/            (CONTEXT.md, docs/adr/)
│   ├── training/
│   ├── database/
│   ├── network/
│   └── designsystem/
├── feature/
│   ├── onboarding/       (CONTEXT.md, docs/adr/)
│   ├── plans/
│   ├── workout/
│   ├── history/
│   ├── coach/
│   └── settings/
└── platform/
    └── reminders/
```

Each Gradle module (`core:*`, `feature:*`, `platform:*`) is a candidate context — it gets its own `CONTEXT.md` and `docs/adr/` at its module root once concepts or decisions specific to it get resolved.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in the relevant module's `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0001 (Fitbit → Health Connect pivot) — but worth reopening because…_
