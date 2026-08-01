// POST /fitbit-oauth-start
// Input:  { "redirectUri": "https://..." }
// Output: { "authorizeUrl": "https://www.fitbit.com/oauth2/authorize?..." }
//
// Requires a Supabase JWT (Authorization: Bearer <token>), per the frozen
// contract: "Każdy endpoint poza OAuth callback wymaga Supabase JWT."
//
// @ts-ignore - Deno global, not resolved by local Node type-checker
import { buildAuthorizeUrl } from "../fitbit-shared/fitbit-api.ts";
import { codeChallengeFromVerifier, randomUrlSafeToken } from "../fitbit-shared/pkce.ts";
import { ERR, errorResponse, FitbitFunctionError } from "../fitbit-shared/errors.ts";
import {
  getAuthenticatedUserId,
  getServiceRoleClient,
} from "../fitbit-shared/supabase-client.ts";

// @ts-ignore
Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return errorResponse(
      new FitbitFunctionError({
        code: ERR.BAD_REQUEST,
        message: "Method not allowed",
        httpStatus: 405,
      }),
    );
  }

  try {
    const userId = await getAuthenticatedUserId(req);
    if (!userId) {
      return errorResponse(
        new FitbitFunctionError({
          code: ERR.UNAUTHORIZED,
          message: "Sign in required",
          httpStatus: 401,
        }),
      );
    }

    const body = await req.json().catch(() => null);
    const redirectUri = body?.redirectUri;
    if (!redirectUri || typeof redirectUri !== "string") {
      return errorResponse(
        new FitbitFunctionError({
          code: ERR.BAD_REQUEST,
          message: "redirectUri is required",
          httpStatus: 400,
        }),
      );
    }

    // @ts-ignore
    const clientId = Deno.env.get("FITBIT_CLIENT_ID");
    // @ts-ignore
    const clientSecret = Deno.env.get("FITBIT_CLIENT_SECRET");
    if (!clientId || !clientSecret) {
      throw new Error("Missing FITBIT_CLIENT_ID/FITBIT_CLIENT_SECRET secrets");
    }

    const state = randomUrlSafeToken(24);
    const codeVerifier = randomUrlSafeToken(48);
    const codeChallenge = await codeChallengeFromVerifier(codeVerifier);

    const supabase = getServiceRoleClient();
    const { error: insertError } = await supabase
      .from("fitbit_oauth_states")
      .insert({
        state,
        user_id: userId,
        redirect_uri: redirectUri,
        code_verifier: codeVerifier,
      });
    if (insertError) {
      throw new Error(`Failed to persist oauth state: ${insertError.message}`);
    }

    const authorizeUrl = buildAuthorizeUrl(
      { clientId, clientSecret },
      redirectUri,
      state,
      codeChallenge,
    );

    return new Response(JSON.stringify({ authorizeUrl }), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  } catch (err) {
    // Never log request/response bodies here — they never contain the
    // client secret anyway (see fitbit-shared/fitbit-api.ts), but keep the
    // logged message minimal regardless.
    console.error("fitbit-oauth-start failed", (err as Error).message);
    return errorResponse(
      new FitbitFunctionError({
        code: ERR.BAD_REQUEST,
        message: "Could not start Fitbit connection",
        httpStatus: 500,
      }),
    );
  }
});
