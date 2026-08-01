// M5 shared helper: service_role client factory.
//
// IMPORTANT: the client built here holds the service_role key, which
// bypasses RLS. It must only ever be constructed and used inside an Edge
// Function's server-side code. Never:
//   - return it, the key, or any header containing it in an HTTP response
//   - log request/response objects that could contain the Authorization
//     header this client sends
//   - pass it to client-supplied code paths
//
// SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided automatically to
// every Edge Function by the Supabase platform; they do not need to be set
// manually as function secrets.

import { createClient, type SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

let cached: SupabaseClient | null = null;

export function getServiceClient(): SupabaseClient {
  if (cached) return cached;
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) {
    // Never interpolate the key itself into an error message/log.
    throw new Error("Server misconfigured: missing SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY");
  }
  cached = createClient(url, key, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  return cached;
}
