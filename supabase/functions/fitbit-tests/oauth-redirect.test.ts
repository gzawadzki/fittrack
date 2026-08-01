import test from "node:test";
import assert from "node:assert/strict";
import {
  FITBIT_APP_REDIRECT_URI,
  fitbitBackendCallbackUri,
  requireAllowedAppRedirectUri,
} from "../fitbit-shared/oauth-redirect.ts";

test("Fitbit receives the HTTPS backend callback, not the app deep link", () => {
  assert.equal(
    fitbitBackendCallbackUri("https://project.supabase.co"),
    "https://project.supabase.co/functions/v1/fitbit-oauth-callback",
  );
  assert.notEqual(
    fitbitBackendCallbackUri("https://project.supabase.co"),
    FITBIT_APP_REDIRECT_URI,
  );
});

test("only the declared app deep link is allowlisted", () => {
  assert.equal(
    requireAllowedAppRedirectUri("fittrack://auth/fitbit"),
    FITBIT_APP_REDIRECT_URI,
  );
  assert.throws(() => requireAllowedAppRedirectUri("https://evil.example/callback"));
  assert.throws(() => requireAllowedAppRedirectUri("fittrack://auth/fitbit/extra"));
});

test("backend callback derivation rejects non-HTTPS Supabase URLs", () => {
  assert.throws(() => fitbitBackendCallbackUri("http://project.supabase.co"));
});
