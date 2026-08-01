// Wires up a TokenProvider (as consumed by daily-sync.ts / workout-hr-sync.ts)
// against the real fitbit_tokens table + Fitbit token endpoint. Access/refresh
// tokens are decrypted only in-memory, for the duration of a single request,
// and are never returned to callers or logged.

import { decryptToken, encryptToken } from "./crypto.ts";
import { refreshAccessToken as fitbitRefresh } from "./fitbit-api.ts";
import type { FitbitOAuthConfig } from "./fitbit-api.ts";
import { ERR, FitbitFunctionError } from "./errors.ts";

export interface TokenProvider {
  getAccessToken(): Promise<string>;
  refreshAccessToken(): Promise<string>;
}

interface FitbitTokensTable {
  getByUser(userId: string): Promise<{
    accessTokenCiphertext: string;
    accessTokenIv: string;
    refreshTokenCiphertext: string;
    refreshTokenIv: string;
    accessTokenExpiresAt: string;
  } | null>;
  save(row: {
    userId: string;
    accessTokenCiphertext: string;
    accessTokenIv: string;
    refreshTokenCiphertext: string;
    refreshTokenIv: string;
    accessTokenExpiresAt: string;
  }): Promise<void>;
}

const EXPIRY_SKEW_MS = 60_000;

export function createSupabaseTokenProvider(opts: {
  userId: string;
  table: FitbitTokensTable;
  oauthConfig: FitbitOAuthConfig;
  encryptionKey: string;
  fetchImpl?: typeof fetch;
}): TokenProvider {
  let cachedAccessToken: string | null = null;

  async function loadAndDecrypt(): Promise<{
    row: NonNullable<Awaited<ReturnType<FitbitTokensTable["getByUser"]>>>;
    accessToken: string;
    refreshToken: string;
  }> {
    const row = await opts.table.getByUser(opts.userId);
    if (!row) {
      throw new FitbitFunctionError({
        code: ERR.NOT_CONNECTED,
        message: "Fitbit account is not connected",
        httpStatus: 409,
      });
    }
    const accessToken = await decryptToken(
      { ciphertext: row.accessTokenCiphertext, iv: row.accessTokenIv },
      opts.encryptionKey,
    );
    const refreshToken = await decryptToken(
      { ciphertext: row.refreshTokenCiphertext, iv: row.refreshTokenIv },
      opts.encryptionKey,
    );
    return { row, accessToken, refreshToken };
  }

  return {
    async getAccessToken(): Promise<string> {
      if (cachedAccessToken) return cachedAccessToken;
      const { row, accessToken } = await loadAndDecrypt();
      const expiresAt = Date.parse(row.accessTokenExpiresAt);
      if (Number.isFinite(expiresAt) && expiresAt - Date.now() > EXPIRY_SKEW_MS) {
        cachedAccessToken = accessToken;
        return accessToken;
      }
      // Expired (or about to be): proactively refresh rather than waiting
      // for a 401 from Fitbit.
      return this.refreshAccessToken();
    },
    async refreshAccessToken(): Promise<string> {
      const { refreshToken } = await loadAndDecrypt();
      const tokenResponse = await fitbitRefresh(
        opts.oauthConfig,
        refreshToken,
        opts.fetchImpl,
      );
      const accessEnc = await encryptToken(
        tokenResponse.access_token,
        opts.encryptionKey,
      );
      const refreshEnc = await encryptToken(
        tokenResponse.refresh_token,
        opts.encryptionKey,
      );
      const expiresAt = new Date(
        Date.now() + tokenResponse.expires_in * 1000,
      ).toISOString();
      await opts.table.save({
        userId: opts.userId,
        accessTokenCiphertext: accessEnc.ciphertext,
        accessTokenIv: accessEnc.iv,
        refreshTokenCiphertext: refreshEnc.ciphertext,
        refreshTokenIv: refreshEnc.iv,
        accessTokenExpiresAt: expiresAt,
      });
      cachedAccessToken = tokenResponse.access_token;
      return tokenResponse.access_token;
    },
  };
}
