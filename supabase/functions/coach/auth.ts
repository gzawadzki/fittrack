// Verifies the Supabase JWT on every request per IMPLEMENTATION_PLAN.md section 4
// ("Every endpoint except the OAuth callback requires a Supabase JWT").

import { createClient, type SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

export interface AuthResult {
  ok: boolean;
  userId: string | null;
  /** Client scoped to the caller's JWT - respects RLS, used for all context reads. */
  supabase: SupabaseClient | null;
}

export async function authenticate(req: Request): Promise<AuthResult> {
  const authHeader = req.headers.get("Authorization") ?? req.headers.get("authorization");
  if (!authHeader?.startsWith("Bearer ")) {
    return { ok: false, userId: null, supabase: null };
  }
  const jwt = authHeader.slice("Bearer ".length).trim();
  if (!jwt) {
    return { ok: false, userId: null, supabase: null };
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  if (!supabaseUrl || !anonKey) {
    return { ok: false, userId: null, supabase: null };
  }

  const supabase = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: `Bearer ${jwt}` } },
    auth: { persistSession: false, autoRefreshToken: false },
  });

  try {
    const { data, error } = await supabase.auth.getUser(jwt);
    if (error || !data?.user) {
      return { ok: false, userId: null, supabase: null };
    }
    return { ok: true, userId: data.user.id, supabase };
  } catch {
    return { ok: false, userId: null, supabase: null };
  }
}
