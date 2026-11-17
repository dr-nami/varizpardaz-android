// Contract section 4 (request signing) and section 5 (config signature), using only node:crypto.
import { createHash, createHmac, createPrivateKey, createPublicKey, randomBytes, sign, verify } from 'node:crypto';

export const b64u = (buf) => Buffer.from(buf).toString('base64url');
export const fromB64u = (text) => Buffer.from(text, 'base64url');

export const sha256Hex = (bytes) => createHash('sha256').update(bytes).digest('hex');

/** canonical = "v1\nMETHOD\nPATH_WITH_QUERY\ntimestamp\nnonce\nhex(sha256(raw_body_bytes))" */
export function canonicalString({ method, path, timestamp, nonce, body }) {
  return ['v1', method.toUpperCase(), path, String(timestamp), nonce, sha256Hex(body ?? Buffer.alloc(0))].join('\n');
}

/** base64url(HMAC-SHA256(device_secret, canonical)); `secret` is the decoded 32-byte device secret. */
export function hmacSignature(secret, canonical) {
  return b64u(createHmac('sha256', secret).update(canonical, 'utf8').digest());
}

export function signRequest({ secret, method, path, timestamp, nonce, body }) {
  return hmacSignature(secret, canonicalString({ method, path, timestamp, nonce, body }));
}

export const newDeviceSecret = () => randomBytes(32);

export function constantTimeEqual(a, b) {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && createHmac('sha256', 'k').update(x).digest().equals(createHmac('sha256', 'k').update(y).digest());
}

// ---- Ed25519 -------------------------------------------------------------------------------

const PKCS8_ED25519_PREFIX = Buffer.from('302e020100300506032b657004220420', 'hex');

/** Ed25519 key pair from a 32-byte seed (RFC 8032). Random if no seed is given. */
export function ed25519KeyPair(seed = randomBytes(32)) {
  const privateKey = createPrivateKey({ key: Buffer.concat([PKCS8_ED25519_PREFIX, seed]), format: 'der', type: 'pkcs8' });
  const spki = createPublicKey(privateKey).export({ type: 'spki', format: 'der' });
  return { privateKey, publicKeyRaw: spki.subarray(spki.length - 32) };
}

export const ed25519Sign = (privateKey, message) => b64u(sign(null, message, privateKey));

export function ed25519Verify(publicKeyRaw, message, signatureB64u) {
  const spkiPrefix = Buffer.from('302a300506032b6570032100', 'hex');
  const key = createPublicKey({ key: Buffer.concat([spkiPrefix, publicKeyRaw]), format: 'der', type: 'spki' });
  return verify(null, message, key, fromB64u(signatureB64u));
}
