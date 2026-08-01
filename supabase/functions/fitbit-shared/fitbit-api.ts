// Thin wrapper around Fitbit Web API HTTP calls. Isolated here so the edge
// function handlers stay focused on orchestration, and so this file can be
// swapped for a fixture-backed fake in tests (see fitbit-tests/).

const FITBIT_AUTHORIZE_URL = "https://www.fitbit.com/oauth2/authorize";
const FITBIT_TOKEN_URL = "https://api.fitbit.com/oauth2/token";
const FITBIT_API_BASE = "https://api.fitbit.com";

export interface FitbitOAuthConfig {
  clientId: string;
  clientSecret: string;
}

export function buildAuthorizeUrl(
  config: FitbitOAuthConfig,
  redirectUri: string,
  state: string,
  codeChallenge: string,
): string {
  const params = new URLSearchParams({
    response_type: "code",
    client_id: config.clientId,
    redirect_uri: redirectUri,
    scope: "activity heartrate sleep profile",
    state,
    code_challenge: codeChallenge,
    code_challenge_method: "S256",
  });
  return `${FITBIT_AUTHORIZE_URL}?${params.toString()}`;
}

export interface TokenResponse {
  access_token: string;
  refresh_token: string;
  expires_in: number;
  user_id: string;
  scope: string;
  token_type: string;
}

function basicAuthHeader(config: FitbitOAuthConfig): string {
  // btoa is available globally in both Deno and Node >=16.
  return "Basic " + btoa(`${config.clientId}:${config.clientSecret}`);
}

export async function exchangeCodeForToken(
  config: FitbitOAuthConfig,
  code: string,
  redirectUri: string,
  codeVerifier: string,
  fetchImpl: typeof fetch = fetch,
): Promise<TokenResponse> {
  const res = await fetchImpl(FITBIT_TOKEN_URL, {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      authorization: basicAuthHeader(config),
    },
    body: new URLSearchParams({
      client_id: config.clientId,
      grant_type: "authorization_code",
      code,
      redirect_uri: redirectUri,
      code_verifier: codeVerifier,
    }).toString(),
  });
  if (!res.ok) {
    // Never include response body verbatim in logs/errors — it can contain
    // sensitive upstream detail. Surface only the status.
    throw new Error(`fitbit_token_exchange_failed_${res.status}`);
  }
  return (await res.json()) as TokenResponse;
}

export async function refreshAccessToken(
  config: FitbitOAuthConfig,
  refreshToken: string,
  fetchImpl: typeof fetch = fetch,
): Promise<TokenResponse> {
  const res = await fetchImpl(FITBIT_TOKEN_URL, {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      authorization: basicAuthHeader(config),
    },
    body: new URLSearchParams({
      grant_type: "refresh_token",
      refresh_token: refreshToken,
    }).toString(),
  });
  if (!res.ok) {
    throw new Error(`fitbit_token_refresh_failed_${res.status}`);
  }
  return (await res.json()) as TokenResponse;
}

function authedHeaders(accessToken: string): HeadersInit {
  return { authorization: `Bearer ${accessToken}` };
}

/** Intraday HR at 1-second resolution for a single day, restricted to [startTime, endTime] (HH:mm). */
export async function fetchIntradayHeartRate(
  accessToken: string,
  dateYyyyMmDd: string,
  startTimeHhMm: string,
  endTimeHhMm: string,
  fetchImpl: typeof fetch = fetch,
): Promise<Response> {
  const url =
    `${FITBIT_API_BASE}/1/user/-/activities/heart/date/${dateYyyyMmDd}/1d/1sec/time/${startTimeHhMm}/${endTimeHhMm}.json`;
  return fetchImpl(url, { headers: authedHeaders(accessToken) });
}

export async function fetchSleepLog(
  accessToken: string,
  dateYyyyMmDd: string,
  fetchImpl: typeof fetch = fetch,
): Promise<Response> {
  const url = `${FITBIT_API_BASE}/1.2/user/-/sleep/date/${dateYyyyMmDd}.json`;
  return fetchImpl(url, { headers: authedHeaders(accessToken) });
}

export async function fetchRestingHeartRate(
  accessToken: string,
  dateYyyyMmDd: string,
  fetchImpl: typeof fetch = fetch,
): Promise<Response> {
  const url =
    `${FITBIT_API_BASE}/1/user/-/activities/heart/date/${dateYyyyMmDd}/1d.json`;
  return fetchImpl(url, { headers: authedHeaders(accessToken) });
}

export async function fetchDailySteps(
  accessToken: string,
  dateYyyyMmDd: string,
  fetchImpl: typeof fetch = fetch,
): Promise<Response> {
  const url =
    `${FITBIT_API_BASE}/1/user/-/activities/date/${dateYyyyMmDd}.json`;
  return fetchImpl(url, { headers: authedHeaders(accessToken) });
}

export async function fetchActivitiesList(
  accessToken: string,
  afterDateYyyyMmDd: string,
  fetchImpl: typeof fetch = fetch,
): Promise<Response> {
  const url = `${FITBIT_API_BASE}/1/user/-/activities/list.json?` +
    new URLSearchParams({
      afterDate: afterDateYyyyMmDd,
      sort: "asc",
      offset: "0",
      limit: "50",
    }).toString();
  return fetchImpl(url, { headers: authedHeaders(accessToken) });
}
