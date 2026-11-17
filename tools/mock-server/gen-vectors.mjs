// Generates core/src/test/resources/vectors/cross-language.json: known-answer vectors made with
// Node's crypto, so the Kotlin signer and the Ed25519 verifier are checked against an implementation
// that shares no code with them. Output is deterministic; re-run only if the rules change:
//
//   node tools/mock-server/gen-vectors.mjs
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { b64u, canonicalString, ed25519KeyPair, ed25519Sign, hmacSignature } from './lib/crypto.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const out = resolve(here, '../../core/src/test/resources/vectors/cross-language.json');

const secret = Buffer.from(Array.from({ length: 32 }, (_, i) => i));
const nonce = Buffer.from(Array.from({ length: 16 }, (_, i) => 255 - i));

const hmacCases = [
  { name: 'get config, empty body', method: 'GET', path: '/v1/devices/config', body: '' },
  { name: 'get with query string', method: 'GET', path: '/v1/devices/config?since=7&x=%D8%A7', body: '' },
  { name: 'post heartbeat', method: 'POST', path: '/v1/devices/heartbeat', body: '{"sent_at":"2026-11-01T10:15:30+03:30","queue_depth":0}' },
  { name: 'post ingest with persian body', method: 'POST', path: '/v1/ingest/android', body: '{"items":[{"body":"واریز: ۵,۰۰۰ ریال\\nمانده: ۹۸"}]}' },
].map((c) => {
  const timestamp = '1793600000';
  const body = Buffer.from(c.body, 'utf8');
  return {
    ...c,
    device_id: 'dev_vector',
    device_secret: b64u(secret),
    timestamp,
    nonce: b64u(nonce),
    canonical: canonicalString({ method: c.method, path: c.path, timestamp, nonce: b64u(nonce), body }),
    signature: hmacSignature(secret, canonicalString({ method: c.method, path: c.path, timestamp, nonce: b64u(nonce), body })),
  };
});

const { privateKey, publicKeyRaw } = ed25519KeyPair(Buffer.alloc(32, 9));
const message = '{"version":7,"issued_at":"2026-11-01T10:00:00+03:30","note":"واریز"}';
const ed25519 = {
  public_key: b64u(publicKeyRaw),
  message,
  signature: ed25519Sign(privateKey, Buffer.from(message, 'utf8')),
};

mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, JSON.stringify({ hmac: hmacCases, ed25519 }, null, 2) + '\n');
console.log(`wrote ${out}`);
