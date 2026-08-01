// TEMPORARY, non-shipped shim to run the Deno-targeted eval harness under local Node for a
// dry-run sanity check (no Deno binary available in this environment). Not part of the
// deliverable - safe to delete. Provides just enough of the `Deno` global for the pure-logic
// modules the harness touches (progression, schema, citations, core, fallback).
import { readFile } from "node:fs/promises";

globalThis.Deno = {
  readTextFile: async (url) => readFile(url, "utf8"),
  env: { get: () => undefined },
};

const mod = await import("./run.ts");
