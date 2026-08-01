export const FITBIT_APP_REDIRECT_URI = "fittrack://auth/fitbit";

export function requireAllowedAppRedirectUri(value: unknown): string {
  if (typeof value !== "string" || value !== FITBIT_APP_REDIRECT_URI) {
    throw new Error("App redirect URI is not allowlisted");
  }
  return value;
}

export function fitbitBackendCallbackUri(supabaseUrl: string): string {
  const base = new URL(supabaseUrl);
  if (base.protocol !== "https:" || base.username || base.password) {
    throw new Error("SUPABASE_URL must be an absolute HTTPS URL");
  }
  base.pathname = `${base.pathname.replace(/\/$/, "")}/functions/v1/fitbit-oauth-callback`;
  base.search = "";
  base.hash = "";
  return base.toString();
}
