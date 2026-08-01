// GET /fitbit-oauth-callback?code=...&state=...
// Output: HTTP 302 redirect to fittrack://auth/fitbit[?status=ok|error]
//
// This is the one endpoint exempt from the Supabase JWT requirement (per the
// frozen contract) because Fitbit itself calls it — identity is instead
// established via the opaque `state` value minted by fitbit-oauth-start and
// bound to a user_id server-side.
//
// The client secret and the exchanged access/refresh tokens never leave this
// function: tokens are encrypted here and only ciphertext is persisted; the
// browser only ever receives a redirect with no token material.

import {
  exchangeCodeForToken,
} from "../fitbit-shared/fitbit-api.ts";
import { encryptToken } from "../fitbit-shared/crypto.ts";
import { getServiceRoleClient } from "../fitbit-shared/supabase-client.ts";

const APP_REDIRECT = "fittrack://auth/fitbit";

function redirect(status: "ok" | "error", reason?: string): Response {
  const url = new URL(APP_REDIRECT);
  url.searchParams.set("status", status);
  if (reason) url.searchParams.set("reason", reason);
  return new Response(null, {
    status: 302,
    headers: { location: url.toString() },
  });
}

// @ts-ignore
Deno.serve(async (req: Request) => {
  try {
    const url = new URL(req.url);
    const code = url.searchParams.get("code");
    const state = url.searchParams.get("state");
    const errorParam = url.searchParams.get("error");

    if (errorParam) {
      // User denied consent on Fitbit's side, etc. Not logged with detail.
      return redirect("error", "denied");
    }
    if (!code || !state) {
      return redirect("error", "missing_params");
    }

    const supabase = getServiceRoleClient();

    const { data: stateRow, error: stateError } = await supabase
      .from("fitbit_oauth_states")
      .select("*")
      .eq("state", state)
      .maybeSingle();

    if (stateError || !stateRow) {
      return redirect("error", "invalid_state");
    }

    // Single-use: delete immediately to prevent replay, regardless of
    // whether the exchange below succeeds.
    await supabase.from("fitbit_oauth_states").delete().eq("state", state);

    if (new Date(stateRow.expires_at).getTime() < Date.now()) {
      return redirect("error", "state_expired");
    }

    // @ts-ignore
    const clientId = Deno.env.get("FITBIT_CLIENT_ID");
    // @ts-ignore
    const clientSecret = Deno.env.get("FITBIT_CLIENT_SECRET");
    // @ts-ignore
    const encKey = Deno.env.get("FITBIT_TOKEN_ENC_KEY");
    if (!clientId || !clientSecret || !encKey) {
      throw new Error(
        "Missing FITBIT_CLIENT_ID/FITBIT_CLIENT_SECRET/FITBIT_TOKEN_ENC_KEY secrets",
      );
    }

    const token = await exchangeCodeForToken(
      { clientId, clientSecret },
      code,
      stateRow.redirect_uri,
      stateRow.code_verifier,
    );

    const accessEnc = await encryptToken(token.access_token, encKey);
    const refreshEnc = await encryptToken(token.refresh_token, encKey);
    const expiresAt = new Date(
      Date.now() + token.expires_in * 1000,
    ).toISOString();

    const { error: upsertError } = await supabase
      .from("fitbit_tokens")
      .upsert(
        {
          user_id: stateRow.user_id,
          access_token_ciphertext: accessEnc.ciphertext,
          access_token_iv: accessEnc.iv,
          refresh_token_ciphertext: refreshEnc.ciphertext,
          refresh_token_iv: refreshEnc.iv,
          scope: token.scope,
          fitbit_user_id: token.user_id,
          access_token_expires_at: expiresAt,
          updated_at: new Date().toISOString(),
        },
        { onConflict: "user_id" },
      );

    if (upsertError) {
      throw new Error(`Failed to persist fitbit tokens: ${upsertError.message}`);
    }

    return redirect("ok");
  } catch (err) {
    console.error("fitbit-oauth-callback failed", (err as Error).message);
    return redirect("error", "internal");
  }
});
