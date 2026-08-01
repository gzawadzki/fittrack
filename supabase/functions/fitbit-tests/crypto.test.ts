import test from "node:test";
import assert from "node:assert/strict";
import { decryptToken, encryptToken, generateEncryptionKey } from "../fitbit-shared/crypto.ts";

test("encryptToken/decryptToken round-trips a token and never stores plaintext", async () => {
  const key = generateEncryptionKey();
  const plaintext = "super-secret-fitbit-refresh-token";
  const enc = await encryptToken(plaintext, key);

  assert.notEqual(enc.ciphertext, plaintext);
  assert.ok(!enc.ciphertext.includes(plaintext));

  const decrypted = await decryptToken(enc, key);
  assert.equal(decrypted, plaintext);
});

test("decryptToken fails with the wrong key", async () => {
  const key = generateEncryptionKey();
  const wrongKey = generateEncryptionKey();
  const enc = await encryptToken("token-value", key);
  await assert.rejects(() => decryptToken(enc, wrongKey));
});

test("each encryption uses a fresh IV (no nonce reuse)", async () => {
  const key = generateEncryptionKey();
  const a = await encryptToken("same-plaintext", key);
  const b = await encryptToken("same-plaintext", key);
  assert.notEqual(a.iv, b.iv);
  assert.notEqual(a.ciphertext, b.ciphertext);
});
