// Citation grounding: every citation the coach returns must trace back to an indexed,
// manifested knowledge document (knowledge/manifest.json). We never trust the model's
// claim that a citation is real — we check its URL against the manifest ourselves.

import type { Citation } from "./types.ts";

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

let cachedManifest: Manifest | null = null;

export async function loadManifest(): Promise<Manifest> {
  if (cachedManifest) return cachedManifest;
  const url = new URL("../../../knowledge/manifest.json", import.meta.url);
  const text = await Deno.readTextFile(url);
  cachedManifest = JSON.parse(text) as Manifest;
  return cachedManifest;
}

export interface CitationCheckResult {
  /** Citations that matched a manifested source (title/url normalized to the manifest entry). */
  verified: Citation[];
  /** Citations the model produced that did NOT match any manifested source; these are dropped. */
  rejected: Citation[];
}

/**
 * Filters model-produced citations down to only those whose URL matches a manifested,
 * indexed knowledge source. Never fabricates or "repairs" a citation - unmatched
 * citations are dropped, not passed through.
 */
export async function verifyCitations(citations: Citation[]): Promise<CitationCheckResult> {
  const manifest = await loadManifest();
  const byUrl = new Map(manifest.sources.map((s) => [normalizeUrl(s.url), s]));

  const verified: Citation[] = [];
  const rejected: Citation[] = [];

  for (const c of citations) {
    const match = byUrl.get(normalizeUrl(c.url));
    if (match) {
      verified.push({ title: match.title, url: match.url, claim: c.claim });
    } else {
      rejected.push(c);
    }
  }

  return { verified, rejected };
}

function normalizeUrl(u: string): string {
  try {
    const parsed = new URL(u);
    return `${parsed.protocol}//${parsed.host}${parsed.pathname}`.replace(/\/$/, "").toLowerCase();
  } catch {
    return u.trim().toLowerCase();
  }
}

/** All manifested sources formatted as compact grounding text for the model prompt. */
export async function manifestAsPromptContext(): Promise<string> {
  const manifest = await loadManifest();
  return manifest.sources
    .map((s) => `- title: "${s.title}"\n  url: ${s.url}\n  topics: ${s.topics.join(", ")}`)
    .join("\n");
}
