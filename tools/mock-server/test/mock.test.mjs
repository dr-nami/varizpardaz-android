import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, before, describe, test } from 'node:test';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { b64u, canonicalString, ed25519Verify, fromB64u, hmacSignature } from '../lib/crypto.mjs';
import { createMockServer, isoTehran, normaliseCode } from '../lib/mock.mjs';
import { normalise, normaliseSender } from '../lib/normalise.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const fixtures = resolve(here, '../../../fixtures');
const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));

describe('shared contract vectors', () => {
  test('normalise.json', () => {
    const vectors = readJson(resolve(fixtures, 'normalise.json'));
    assert.ok(vectors.length > 0);
    for (const v of vectors) assert.equal(normalise(v.in), v.out, JSON.stringify(v.in));
  });

  test('sender normalisation', () => {
    assert.equal(normaliseSender(' +98 912 '), '98912');
    assert.equal(normaliseSender('BankMellat'), 'BankMellat');
  });

  test('cross-language vectors match this implementation', () => {
    const v = readJson(resolve(here, '../../../core/src/test/resources/vectors/cross-language.json'));
    for (const c of v.hmac) {
      const body = Buffer.from(c.body, 'utf8');
      const canonical = canonicalString({ method: c.method, path: c.path, timestamp: c.timestamp, nonce: c.nonce, body });
      assert.equal(canonical, c.canonical, c.name);
      assert.equal(hmacSignature(fromB64u(c.device_secret), canonical), c.signature, c.name);
    }
    assert.ok(ed25519Verify(fromB64u(v.ed25519.public_key), Buffer.from(v.ed25519.message, 'utf8'), v.ed25519.signature));
  });
});

describe('mock server', () => {
  let mock;
  let base;
  let clock = Date.parse('2026-11-01T06:45:00Z');
  const now = () => clock;

  before(async () => {
    mock = createMockServer({ configPath: resolve(fixtures, 'config.example.json'), now });
    const { port } = await mock.listen(0);
    base = `http://127.0.0.1:${port}`;
  });
  after(() => mock.close());

  const post = (path, json) => fetch(base + path, { method: 'POST', body: JSON.stringify(json), headers: { 'content-type': 'application/json' } });

  async function pairDevice() {
    const { code } = await (await post('/mock/pairing-codes', {})).json();
    const res = await post('/v1/devices/pair', { code, platform: 'android', device_name: 'Test', os_version: '14', app_version_code: 1 });
    assert.equal(res.status, 201);
    return { code, ...(await res.json()) };
  }

  let nonceCounter = 0;
  function signed(device, method, path, bodyObj, overrides = {}) {
    const body = bodyObj === undefined ? Buffer.alloc(0) : Buffer.from(JSON.stringify(bodyObj), 'utf8');
    const timestamp = overrides.timestamp ?? String(Math.floor(clock / 1000));
    const nonce = overrides.nonce ?? b64u(Buffer.from(`nonce-${++nonceCounter}-padding`));
    const signature = overrides.signature ?? hmacSignature(fromB64u(device.device_secret), canonicalString({ method, path, timestamp, nonce, body }));
    return fetch(base + path, {
      method,
      body: method === 'GET' ? undefined : body,
      headers: { 'x-pg-device': device.device_id, 'x-pg-timestamp': timestamp, 'x-pg-nonce': nonce, 'x-pg-signature': signature, 'content-type': 'application/json' },
    });
  }

  const depositItem = (id, body, ts = '2026-11-01T10:15:02+03:30') => ({
    local_id: id, kind: 'deposit', channel: 'sms', sender: 'TESTBANK', package: null, sim_slot: 0, subscription_id: 3,
    sc_timestamp: ts, received_at: ts, body, template_id: 'testbank-dep-v1', config_version: 1, rescan_request_id: null,
  });

  test('pairing returns the contract fields and codes are single use', async () => {
    const dev = await pairDevice();
    assert.match(dev.device_id, /^dev_/);
    assert.equal(fromB64u(dev.device_secret).length, 32);
    assert.equal(fromB64u(dev.server_public_key).length, 32);
    assert.equal(dev.heartbeat_interval_sec, 30);
    const again = await post('/v1/devices/pair', { code: dev.code, platform: 'android', device_name: 'x', os_version: '14', app_version_code: 1 });
    assert.equal(again.status, 400);
    assert.equal((await again.json()).error.code, 'PAIRING_CODE_INVALID');
  });

  test('pairing codes expire after 10 minutes and tolerate look-alike letters', async () => {
    const { code } = await (await post('/mock/pairing-codes', {})).json();
    assert.equal(normaliseCode(code.toLowerCase()), code);
    clock += 11 * 60 * 1000;
    const res = await post('/v1/devices/pair', { code, platform: 'android', device_name: 'x', os_version: '14', app_version_code: 1 });
    assert.equal(res.status, 400);
    clock -= 11 * 60 * 1000;
  });

  test('config is signed over the exact body bytes', async () => {
    const dev = await pairDevice();
    const res = await signed(dev, 'GET', '/v1/devices/config');
    assert.equal(res.status, 200);
    const bytes = Buffer.from(await res.arrayBuffer());
    const signature = res.headers.get('x-pg-config-signature');
    assert.ok(ed25519Verify(fromB64u(dev.server_public_key), bytes, signature));
    assert.equal(JSON.parse(bytes).version, 1);
    assert.equal(typeof JSON.parse(bytes).merchant_name, 'string', 'the mock sends merchant_name like a server that implements CCR-D2');
    bytes[10] ^= 1;
    assert.ok(!ed25519Verify(fromB64u(dev.server_public_key), bytes, signature));
  });

  test('a replayed nonce, a bad signature and clock skew are refused with the contract codes', async () => {
    const dev = await pairDevice();
    const first = await signed(dev, 'GET', '/v1/devices/config', undefined, { nonce: 'AAAAAAAAAAAAAAAAAAAAAA' });
    assert.equal(first.status, 200);
    const replay = await signed(dev, 'GET', '/v1/devices/config', undefined, { nonce: 'AAAAAAAAAAAAAAAAAAAAAA' });
    assert.equal(replay.status, 401);
    assert.equal((await replay.json()).error.code, 'NONCE_REUSED');

    const bad = await signed(dev, 'GET', '/v1/devices/config', undefined, { signature: 'AAAA' });
    assert.equal((await bad.json()).error.code, 'BAD_SIGNATURE');

    const skewed = await signed(dev, 'GET', '/v1/devices/config', undefined, { timestamp: String(Math.floor(clock / 1000) - 400) });
    assert.equal(skewed.status, 401);
    const skewBody = await skewed.json();
    assert.equal(skewBody.error.code, 'CLOCK_SKEW');
    assert.equal(skewBody.error.server_time, isoTehran(clock));
    assert.match(skewBody.error.message, /[؀-ۿ]/, 'messages are Persian');
  });

  test('ingest: accepted, then duplicate; OTP and malformed items never get stored', async () => {
    const dev = await pairDevice();
    const text = 'TESTBANK\nواریز: ۵,۰۰۰,۰۰۰ ریال\nحساب: ۱۲۳۴\nمانده: ۹۸,۰۰۰,۰۰۰';
    const ok = await signed(dev, 'POST', '/v1/ingest/android', { items: [depositItem('a', text)] });
    assert.deepEqual((await ok.json()).results, [{ local_id: 'a', status: 'accepted' }]);

    const dup = await signed(dev, 'POST', '/v1/ingest/android', { items: [depositItem('a2', text)] });
    assert.deepEqual((await dup.json()).results, [{ local_id: 'a2', status: 'duplicate' }]);

    const otp = await signed(dev, 'POST', '/v1/ingest/android', { items: [depositItem('o', 'TESTBANK\nرمز پویا: 482913')] });
    assert.deepEqual((await otp.json()).results, [{ local_id: 'o', status: 'rejected', reason: 'otp_detected' }]);
    assert.equal(mock.state.otpViolations.length, 1);

    const marker = {
      local_id: 'm', kind: 'balance_marker', channel: 'sms', sender: 'TESTBANK', sim_slot: 0, subscription_id: 3,
      sc_timestamp: '2026-11-01T11:02:40+03:30', received_at: '2026-11-01T11:02:41+03:30', bank: 'testbank',
      amount_rial: '1250000', balance_rial: '96750000', template_id: 'testbank-wd-v1', config_version: 1, rescan_request_id: null,
    };
    assert.equal((await (await signed(dev, 'POST', '/v1/ingest/android', { items: [marker] })).json()).results[0].status, 'accepted');

    // CCR-D1 and CCR-1: a marker may carry the template's account, and a notification marker names its package.
    const withAccount = { ...marker, local_id: 'ma', account: '1234', sc_timestamp: '2026-11-01T11:03:40+03:30' };
    assert.equal((await (await signed(dev, 'POST', '/v1/ingest/android', { items: [withAccount] })).json()).results[0].status, 'accepted');
    const notified = { ...marker, local_id: 'mn', channel: 'notification', sender: null, package: 'ir.example.testbank', account: null, sc_timestamp: '2026-11-01T11:04:40+03:30' };
    assert.equal((await (await signed(dev, 'POST', '/v1/ingest/android', { items: [notified] })).json()).results[0].status, 'accepted');
    const noPackage = { ...notified, local_id: 'mnp', package: undefined, sc_timestamp: '2026-11-01T11:05:40+03:30' };
    assert.deepEqual((await (await signed(dev, 'POST', '/v1/ingest/android', { items: [noPackage] })).json()).results, [{ local_id: 'mnp', status: 'rejected', reason: 'invalid_item' }]);
    const badAccount = { ...marker, local_id: 'mba', account: 1234, sc_timestamp: '2026-11-01T11:06:40+03:30' };
    assert.deepEqual((await (await signed(dev, 'POST', '/v1/ingest/android', { items: [badAccount] })).json()).results, [{ local_id: 'mba', status: 'rejected', reason: 'invalid_item' }]);
    assert.equal(mock.state.ingested.find((i) => i.local_id === 'ma').account, '1234');

    // Per-item problems are answered per item, as final `rejected` results (the real server does the same).
    const withText = await signed(dev, 'POST', '/v1/ingest/android', { items: [{ ...marker, local_id: 'm2', body: 'برداشت' }] });
    assert.equal(withText.status, 200);
    assert.deepEqual((await withText.json()).results, [{ local_id: 'm2', status: 'rejected', reason: 'invalid_item' }]);

    const stored = mock.state.ingested.filter((i) => i.device_id === dev.device_id);
    assert.deepEqual(stored.map((i) => i.local_id).sort(), ['a', 'm', 'ma', 'mn']);
  });

  test('batch-level problems are a 422 whose fields are keyed by paths; a huge body is a 413', async () => {
    const dev = await pairDevice();
    const tooMany = Array.from({ length: 51 }, (_, i) => depositItem(`b${i}`, `واریز ${i}`));
    const res = await signed(dev, 'POST', '/v1/ingest/android', { items: tooMany });
    assert.equal(res.status, 422);
    const error = (await res.json()).error;
    assert.equal(error.code, 'VALIDATION_ERROR');
    assert.deepEqual(Object.keys(error.fields), ['items']);

    const noId = await signed(dev, 'POST', '/v1/ingest/android', { items: [depositItem('ok', 'واریز'), { ...depositItem('x', 'واریز'), local_id: undefined }] });
    assert.equal(noId.status, 422);
    assert.deepEqual(Object.keys((await noId.json()).error.fields), ['items.1.local_id']);

    const huge = await signed(dev, 'POST', '/v1/ingest/android', { items: [depositItem('big', 'x'.repeat(300 * 1024))] });
    assert.equal(huge.status, 413);
    assert.equal((await huge.json()).error.code, 'PAYLOAD_TOO_LARGE');
  });

  test('per-item rejections carry the server reasons', async () => {
    const dev = await pairDevice();
    const send = async (item) => (await (await signed(dev, 'POST', '/v1/ingest/android', { items: [item] })).json()).results[0];
    assert.deepEqual(await send({ ...depositItem('t', 'واریز'), sc_timestamp: 'yesterday' }), { local_id: 't', status: 'rejected', reason: 'invalid_timestamp' });
    assert.deepEqual(await send({ ...depositItem('s', 'واریز'), sender: '' }), { local_id: 's', status: 'rejected', reason: 'missing_sender' });
    assert.deepEqual(await send({ ...depositItem('k', 'واریز'), kind: 'nonsense' }), { local_id: 'k', status: 'rejected', reason: 'invalid_item' });
    assert.deepEqual(await send(depositItem('w', 'TESTBANK برداشت: 1,250,000 ریال حساب: 1234 مانده: 96,750,000')), { local_id: 'w', status: 'rejected', reason: 'withdrawal_text' });
    const marker = { local_id: 'u', kind: 'balance_marker', channel: 'sms', sender: 'X', sim_slot: 0, subscription_id: 1, sc_timestamp: '2026-11-01T11:02:40+03:30', received_at: '2026-11-01T11:02:41+03:30', bank: 'nobank', amount_rial: '1', balance_rial: '2', template_id: 't', config_version: 1, rescan_request_id: null };
    assert.deepEqual(await send(marker), { local_id: 'u', status: 'rejected', reason: 'unknown_bank' });
  });

  test('heartbeat announces config changes and delivers rescans until they are reported done', async () => {
    const dev = await pairDevice();
    const hb = { sent_at: isoTehran(clock), app_version_code: 1, queue_depth: 2, config_version: 1, rescans_done: [] };
    const first = await (await signed(dev, 'POST', '/v1/devices/heartbeat', hb)).json();
    assert.equal(first.config_version, mock.state.config.version);
    assert.deepEqual(first.rescan, []);

    await post('/mock/config', { upload: { max_batch: 2 } });
    const rescan = await (await post('/mock/rescan', { from: '2026-11-01T09:00:00+03:30', to: '2026-11-01T10:00:00+03:30', sim_slot: 0 })).json();
    const second = await (await signed(dev, 'POST', '/v1/devices/heartbeat', hb)).json();
    assert.ok(second.config_version > 1);
    assert.equal(second.rescan[0].request_id, rescan.request_id);

    const done = await (await signed(dev, 'POST', '/v1/devices/heartbeat', { ...hb, rescans_done: [{ request_id: rescan.request_id, found: 2 }] })).json();
    assert.deepEqual(done.rescan, []);

    const cfg = JSON.parse(Buffer.from(await (await signed(dev, 'GET', '/v1/devices/config')).arrayBuffer()));
    assert.equal(cfg.upload.max_batch, 2);
    await post('/mock/reset', {});
  });

  test('a revoked device is refused with DEVICE_REVOKED', async () => {
    const dev = await pairDevice();
    await post('/mock/revoke', { device_id: dev.device_id });
    const res = await signed(dev, 'GET', '/v1/devices/config');
    assert.equal(res.status, 403);
    assert.equal((await res.json()).error.code, 'DEVICE_REVOKED');
  });

  test('fail-next injects errors for testing the uploader', async () => {
    const dev = await pairDevice();
    await post('/mock/fail-next', { path: '/v1/ingest/android', status: 503, times: 2 });
    await post('/mock/fail-next', { path: '/v1/ingest/android', code: 'RATE_LIMITED', retry_after: 7 });
    const item = { items: [depositItem('f', 'واریز')] };
    assert.equal((await signed(dev, 'POST', '/v1/ingest/android', item)).status, 503);
    assert.equal((await signed(dev, 'POST', '/v1/ingest/android', item)).status, 503);
    const limited = await signed(dev, 'POST', '/v1/ingest/android', item);
    assert.equal(limited.status, 429);
    assert.equal(limited.headers.get('retry-after'), '7');
    assert.equal((await signed(dev, 'POST', '/v1/ingest/android', item)).status, 200);
  });

  test('ios shortcut endpoint: token required, otp ignored, duplicates detected', async () => {
    const { token } = await (await post('/mock/ios-token', {})).json();
    assert.match(token, /^pgd_ios_[A-Za-z0-9]{32}$/);
    const send = (body, t = token) => fetch(`${base}/v1/ingest/shortcut`, {
      method: 'POST', headers: { authorization: `Bearer ${t}`, 'content-type': 'application/json' },
      body: JSON.stringify({ body, client_time: '2026-11-01T10:15:05+03:30', shortcut_version: 1 }),
    });
    assert.equal((await send('x', 'pgd_ios_nope')).status, 401);
    assert.equal((await (await send('واریز: ۱۰۰ ریال شماره ۱')).json()).status, 'accepted');
    assert.equal((await (await send('واریز: ۱۰۰ ریال شماره ۱')).json()).status, 'duplicate');
    assert.equal((await (await send('رمز پویا: 123456')).json()).status, 'ignored');
  });
});

describe('mock server: update files', () => {
  let mock;
  let base;
  let dir;
  const bytes = Buffer.from('not really an apk, but it has bytes');

  before(async () => {
    const { mkdtempSync, writeFileSync } = await import('node:fs');
    const { tmpdir } = await import('node:os');
    dir = mkdtempSync(resolve(tmpdir(), 'mock-files-'));
    writeFileSync(resolve(dir, 'listener-2.apk'), bytes);
    mock = createMockServer({ configPath: resolve(fixtures, 'config.example.json'), filesDir: dir });
    base = `http://127.0.0.1:${(await mock.listen(0)).port}`;
  });
  after(async () => {
    await mock.close();
    const { rmSync } = await import('node:fs');
    rmSync(dir, { recursive: true, force: true });
  });

  const post = (path, json) => fetch(base + path, { method: 'POST', body: JSON.stringify(json), headers: { 'content-type': 'application/json' } });

  test('serves a file with its length and the apk type, and nothing outside the folder', async () => {
    const res = await fetch(`${base}/mock/files/listener-2.apk`);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get('content-type'), 'application/vnd.android.package-archive');
    assert.equal(res.headers.get('content-length'), String(bytes.length));
    assert.deepEqual(Buffer.from(await res.arrayBuffer()), bytes);
    assert.equal((await fetch(`${base}/mock/files/missing.apk`)).status, 404);
    assert.equal((await fetch(`${base}/mock/files/..%2F..%2Fpackage.json`)).status, 404);
  });

  test('POST /mock/app-update offers the file with its real checksum and a link to itself', async () => {
    const { createHash } = await import('node:crypto');
    const before = (await (await fetch(`${base}/mock/state`)).json()).config_version;
    const res = await post('/mock/app-update', { file: 'listener-2.apk', latest_version_code: 2, min_version_code: 2 });
    assert.equal(res.status, 200);
    const { version, app_update } = await res.json();
    assert.equal(version, before + 1);
    assert.equal(app_update.latest_version_code, 2);
    assert.equal(app_update.min_version_code, 2);
    assert.equal(app_update.sha256, createHash('sha256').update(bytes).digest('hex'));
    assert.match(app_update.url, /\/mock\/files\/listener-2\.apk$/);
    assert.equal((await post('/mock/app-update', { file: 'nope.apk', latest_version_code: 3 })).status, 404);
  });
});
