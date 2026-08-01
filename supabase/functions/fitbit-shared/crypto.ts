// AES-GCM encryption for Fitbit access/refresh tokens using Web Crypto, which
// is available both in Deno (edge function runtime) and Node >=19 (test
// harness), so this module needs zero polyfilling in either environment.
//
// The key (FITBIT_TOKEN_ENC_KEY) is a base64-encoded 32-byte AES-256 key that
// lives ONLY in Supabase function secrets. Plaintext tokens are encrypted
// here, inside the edge function, before ever being written to Postgres —
// so the DB (and anyone with read access to it) only ever sees ciphertext.

export interface EncryptedPayload {
  ciphertext: string; // base64
  iv: string; // base64
}

async function importKey(base64Key: string): Promise<CryptoKey> {
  const raw = base64ToBytes(base64Key);
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, [
    "encrypt",
    "decrypt",
  ]);
}

export async function encryptToken(
  plaintext: string,
  base64Key: string,
): Promise<EncryptedPayload> {
  const key = await importKey(base64Key);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encoded = new TextEncoder().encode(plaintext);
  const cipherBuf = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    key,
    encoded,
  );
  return {
    ciphertext: bytesToBase64(new Uint8Array(cipherBuf)),
    iv: bytesToBase64(iv),
  };
}

export async function decryptToken(
  payload: EncryptedPayload,
  base64Key: string,
): Promise<string> {
  const key = await importKey(base64Key);
  const iv = base64ToBytes(payload.iv);
  const cipherBytes = base64ToBytes(payload.ciphertext);
  const plainBuf = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv },
    key,
    cipherBytes,
  );
  return new TextDecoder().decode(plainBuf);
}

export function base64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

export function bytesToBase64(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

// Generates a random 32-byte base64 key. Used by fixtures/tests and to help
// operators mint FITBIT_TOKEN_ENC_KEY once during setup.
export function generateEncryptionKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return bytesToBase64(bytes);
}
