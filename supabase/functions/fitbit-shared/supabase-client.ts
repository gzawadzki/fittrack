// Service-role Supabase client for use inside edge functions only. The
// service role key must never be echoed in a response body or a log line —
// grep this repo for "SUPABASE_SERVICE_ROLE_KEY" reads before adding any new
// console.log near this client.
//
// Imported via esm.sh, following the standard Supabase Edge Function pattern
// (Deno has no npm resolution without an import map).
// deno-lint-ignore-file
// @ts-ignore - resolved at Deno runtime, not by the local Node type-checker
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

export function getServiceRoleClient() {
  const url = Deno.env.get("SUPABASE_URL");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceRoleKey) {
    throw new Error("Missing SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY");
  }
  return createClient(url, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

/** Verifies the caller's Supabase JWT (from the Authorization header) and returns their user id. */
export async function getAuthenticatedUserId(
  req: Request,
): Promise<string | null> {
  const authHeader = req.headers.get("authorization") ?? "";
  const jwt = authHeader.replace(/^Bearer\s+/i, "");
  if (!jwt) return null;

  const client = getServiceRoleClient();
  const { data, error } = await client.auth.getUser(jwt);
  if (error || !data?.user) return null;
  return data.user.id;
}
