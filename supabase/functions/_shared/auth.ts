// M5 shared helper: resolves the calling user from the Supabase JWT in the
// Authorization header. Every endpoint owned by this module requires a
// valid Supabase JWT (per contract section 4: "every endpoint except the
// OAuth callback requires a Supabase JWT").

import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { unauthenticated } from "./errors.ts";

export interface AuthedUser {
  id: string;
  email: string;
}

export type AuthResult =
  | { ok: true; user: AuthedUser }
  | { ok: false; response: Response };

export async function requireUser(req: Request, admin: SupabaseClient): Promise<AuthResult> {
  const authHeader = req.headers.get("Authorization") ?? "";
  const match = /^Bearer\s+(.+)$/i.exec(authHeader);
  if (!match) {
    return { ok: false, response: unauthenticated("Missing bearer token") };
  }
  const token = match[1];
  const { data, error } = await admin.auth.getUser(token);
  if (error || !data?.user) {
    return { ok: false, response: unauthenticated("Invalid or expired session") };
  }
  return { ok: true, user: { id: data.user.id, email: data.user.email ?? "" } };
}
