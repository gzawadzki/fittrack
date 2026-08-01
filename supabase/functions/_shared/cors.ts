// M5 shared helper: CORS headers for Edge Functions called from the Android
// app (Android does not require CORS, but this keeps local `supabase
// functions serve` + any future web/admin tooling usable) and a small
// helper to short-circuit CORS preflight requests.

export const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

export function handleCorsPreflight(req: Request): Response | null {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: CORS_HEADERS });
  }
  return null;
}
