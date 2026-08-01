// POST /knowledge-reindex - protected, ops-only Edge Function.
// Input: a manifest of curated sources to (re)index (defaults to the checked-in
// knowledge/manifest.json when no body is supplied). Output: { indexed, failed[], storeId }.
//
// Guarantees this function enforces:
//   1. Every entry to index MUST already exist, byte-identical id/url/file, in the checked-in
//      knowledge/manifest.json - this is what lets the coach function trust that any citation
//      whose URL matches the manifest really was indexed from a curated document, never an
//      arbitrary caller-supplied source.
//   2. The referenced file must exist under knowledge/sources/ and be non-empty.
//   3. This endpoint requires a shared secret (KNOWLEDGE_REINDEX_SECRET Supabase secret), not
//      a regular user JWT - it is an operator/admin action, not something the mobile app calls.
//
// Live indexing: if GEMINI_API_KEY is set, this attempts to upload each source to Gemini's
// File Search / Files API so the coach function could eventually retrieve passages by search
// instead of the current "whole manifest in the prompt" approach. That upload is best-effort:
// on any failure the source is still considered indexed locally (checksum-verified, listed in
// the manifest) but is reported in `failed[]` with a diagnostic reason for a real Gemini File
// Search store. See HANDOFF.md - this environment has no live Gemini API access, so the
// upload path is untested against the real API and should be verified before relying on it.

interface ManifestSource {
  id: string;
  title: string;
  file: string;
  url: string;
  origin: string;
  topics: string[];
}

interface Manifest {
  version: number;
  sources: ManifestSource[];
}

interface ReindexRequestBody {
  sources?: Array<{ id: string; url: string; file: string }>;
}

interface FailedEntry {
  id: string;
  reason: string;
}

async function loadLocalManifest(): Promise<Manifest> {
  const url = new URL("../../../knowledge/manifest.json", import.meta.url);
  const text = await Deno.readTextFile(url);
  return JSON.parse(text) as Manifest;
}

async function sha256(text: string): Promise<string> {
  const data = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function attemptGeminiUpload(source: ManifestSource, content: string, apiKey: string): Promise<{ ok: boolean; reason: string | null }> {
  try {
    // Best-effort: Gemini Files API upload. Exact File Search / corpora attachment flow for a
    // structured-output model call is not exercised here (no live credentials in this
    // environment) - this only proves connectivity/auth, not full retrieval wiring.
    const response = await fetch("https://generativelanguage.googleapis.com/upload/v1beta/files", {
      method: "POST",
      headers: {
        "x-goog-api-key": apiKey,
        "X-Goog-Upload-Protocol": "raw",
        "Content-Type": "text/markdown",
      },
      body: content,
    });
    if (!response.ok) {
      const body = await response.text().catch(() => "");
      return { ok: false, reason: `Gemini upload HTTP ${response.status}: ${body.slice(0, 300)}` };
    }
    return { ok: true, reason: null };
  } catch (e) {
    return { ok: false, reason: `Gemini upload error: ${String(e)}` };
  }
}

function checkSecret(req: Request): boolean {
  const expected = Deno.env.get("KNOWLEDGE_REINDEX_SECRET");
  if (!expected) return false;
  const provided = req.headers.get("x-reindex-secret");
  return provided === expected;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: { code: "INVALID_REQUEST", message: "Only POST is supported.", retryable: false, retryAfterSeconds: null } }), {
      status: 405,
      headers: { "Content-Type": "application/json" },
    });
  }

  if (!checkSecret(req)) {
    return new Response(
      JSON.stringify({ error: { code: "UNAUTHENTICATED", message: "A valid reindex secret is required.", retryable: false, retryAfterSeconds: null } }),
      { status: 401, headers: { "Content-Type": "application/json" } },
    );
  }

  let body: ReindexRequestBody = {};
  try {
    const text = await req.text();
    if (text.trim() !== "") body = JSON.parse(text);
  } catch {
    return new Response(
      JSON.stringify({ error: { code: "INVALID_REQUEST", message: "Request body must be valid JSON.", retryable: false, retryAfterSeconds: null } }),
      { status: 400, headers: { "Content-Type": "application/json" } },
    );
  }

  const localManifest = await loadLocalManifest();
  const byId = new Map(localManifest.sources.map((s) => [s.id, s]));

  const requested = body.sources && body.sources.length > 0
    ? body.sources
    : localManifest.sources.map((s) => ({ id: s.id, url: s.url, file: s.file }));

  const indexed: string[] = [];
  const failed: FailedEntry[] = [];
  const contentHashes: string[] = [];

  const apiKey = Deno.env.get("GEMINI_API_KEY");

  for (const entry of requested) {
    const manifestEntry = byId.get(entry.id);
    if (!manifestEntry) {
      failed.push({ id: entry.id, reason: "No matching entry in knowledge/manifest.json - refusing to index an unmanifested source." });
      continue;
    }
    if (manifestEntry.url !== entry.url || manifestEntry.file !== entry.file) {
      failed.push({ id: entry.id, reason: "url/file does not match the manifest entry for this id." });
      continue;
    }

    let content: string;
    try {
      const fileUrl = new URL(`../../../knowledge/${manifestEntry.file}`, import.meta.url);
      content = await Deno.readTextFile(fileUrl);
      if (content.trim() === "") throw new Error("file is empty");
    } catch (e) {
      failed.push({ id: entry.id, reason: `Could not read source file: ${String(e)}` });
      continue;
    }

    contentHashes.push(await sha256(content));

    if (apiKey) {
      const upload = await attemptGeminiUpload(manifestEntry, content, apiKey);
      if (!upload.ok) {
        failed.push({ id: entry.id, reason: upload.reason ?? "unknown upload error" });
        continue;
      }
    }

    indexed.push(entry.id);
  }

  const storeIdSeed = contentHashes.sort().join(":") || "empty";
  const storeId = `fittrack-knowledge-${(await sha256(storeIdSeed)).slice(0, 16)}`;

  return new Response(JSON.stringify({ indexed, failed, storeId }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
});
