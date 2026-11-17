// In-memory implementation of the device-facing endpoints of docs/device-api.md, so the Android app
// can be built and tested before the real server exists. It is a development tool: it is not secure,
// not rate limited and keeps everything in memory (optionally mirrored to a JSON file).
import { createHash, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { readFileSync, writeFileSync, existsSync, statSync } from 'node:fs';
import { basename, join } from 'node:path';
import { b64u, canonicalString, constantTimeEqual, ed25519KeyPair, ed25519Sign, fromB64u, hmacSignature } from './crypto.mjs';
import { normalise, normaliseSender } from './normalise.mjs';

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const MAX_BODY_BYTES = 256 * 1024;
const SKEW_SECONDS = 300;
const NONCE_WINDOW_MS = 10 * 60 * 1000;
const PAIRING_TTL_MS = 10 * 60 * 1000;

const MESSAGES = {
  BAD_SIGNATURE: 'امضای درخواست معتبر نیست.',
  CLOCK_SKEW: 'ساعت گوشی با ساعت سرور اختلاف دارد.',
  NONCE_REUSED: 'درخواست تکراری است.',
  DEVICE_REVOKED: 'این دستگاه از حساب جدا شده است. دوباره وصل کنید.',
  PAIRING_CODE_INVALID: 'کد اتصال نامعتبر یا منقضی است.',
  VALIDATION_ERROR: 'اطلاعات ارسالی نامعتبر است.',
  PAYLOAD_TOO_LARGE: 'حجم درخواست زیاد است.',
  RATE_LIMITED: 'تعداد درخواست‌ها زیاد است؛ کمی بعد دوباره تلاش کنید.',
};
const HTTP_STATUS = {
  BAD_SIGNATURE: 401, CLOCK_SKEW: 401, NONCE_REUSED: 401, DEVICE_REVOKED: 403,
  PAIRING_CODE_INVALID: 400, VALIDATION_ERROR: 422, PAYLOAD_TOO_LARGE: 413, RATE_LIMITED: 429,
};

/** ISO-8601 with +03:30 offset, whole seconds, like the phones send. */
export function isoTehran(ms) {
  const shifted = new Date(ms + (3 * 60 + 30) * 60 * 1000);
  return shifted.toISOString().replace(/\.\d{3}Z$/, '+03:30');
}

export function normaliseCode(raw) {
  const code = String(raw).toUpperCase().replace(/-/g, '').replace(/[IL]/g, '1').replace(/O/g, '0');
  return code.length === 8 && [...code].every((c) => CROCKFORD.includes(c)) ? code : null;
}

export function createMockServer({
  configPath,
  publicUrl = 'http://localhost:8787',
  stateFile = null,
  /** A folder of files (an update APK, say) served at /mock/files/<name> and offered by POST /mock/app-update. */
  filesDir = null,
  /** Sent as `merchant_name` in the config (CCR-D2) unless the base config already has one; null leaves it out, like an older server. */
  merchantName = 'فروشگاه آزمایشی',
  now = () => Date.now(),
  seed = null,
} = {}) {
  const baseConfig = JSON.parse(readFileSync(configPath, 'utf8'));
  if (merchantName && baseConfig.merchant_name === undefined) baseConfig.merchant_name = merchantName;
  const keys = ed25519KeyPair(seed ?? randomBytes(32));
  const state = freshState(baseConfig);
  if (stateFile && existsSync(stateFile)) restore(state, JSON.parse(readFileSync(stateFile, 'utf8')));

  function persist() {
    if (stateFile) writeFileSync(stateFile, JSON.stringify(snapshot(state), null, 2));
  }

  const server = createServer((req, res) => {
    readBody(req)
      .then((body) => route(req, res, body))
      .catch((err) => {
        if (err?.code === 'TOO_LARGE') return sendError(res, 'PAYLOAD_TOO_LARGE');
        console.error('mock server error:', err?.message);
        res.writeHead(500).end();
      });
  });

  // ---- helpers -------------------------------------------------------------------------------

  const serverTime = () => isoTehran(now());

  function sendJson(res, status, obj, headers = {}) {
    const text = JSON.stringify(obj);
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', ...headers });
    res.end(text);
  }

  function sendError(res, code, extra = {}, headers = {}) {
    sendJson(res, HTTP_STATUS[code] ?? 400, { ok: false, error: { code, message: MESSAGES[code], server_time: serverTime(), ...extra } }, headers);
  }

  function injectedFailure(req, res) {
    const path = req.url.split('?')[0];
    const idx = state.failNext.findIndex((f) => !f.path || f.path === path);
    if (idx < 0) return false;
    const f = state.failNext[idx];
    if (--f.times <= 0) state.failNext.splice(idx, 1);
    const headers = f.retry_after ? { 'retry-after': String(f.retry_after) } : {};
    if (f.code) sendError(res, f.code, {}, headers);
    else sendJson(res, f.status ?? 503, { ok: false, error: { code: 'INTERNAL', message: 'خطای موقت سرور', server_time: serverTime() } }, headers);
    return true;
  }

  /** Section 4. Returns the device, or null after having sent the error response. */
  function authenticate(req, res, body) {
    const id = req.headers['x-pg-device'];
    const timestamp = req.headers['x-pg-timestamp'];
    const nonce = req.headers['x-pg-nonce'];
    const signature = req.headers['x-pg-signature'];
    const device = state.devices[id];
    if (!device || !timestamp || !nonce || !signature) return void sendError(res, 'BAD_SIGNATURE');
    if (device.revoked) return void sendError(res, 'DEVICE_REVOKED');
    const expected = hmacSignature(fromB64u(device.secret), canonicalString({ method: req.method, path: req.url, timestamp, nonce, body }));
    if (!constantTimeEqual(expected, String(signature))) return void sendError(res, 'BAD_SIGNATURE');
    if (Math.abs(Math.floor(now() / 1000) - Number(timestamp)) > SKEW_SECONDS) return void sendError(res, 'CLOCK_SKEW');
    for (const [n, seenAt] of Object.entries(device.nonces)) if (now() - seenAt > NONCE_WINDOW_MS) delete device.nonces[n];
    if (device.nonces[nonce]) return void sendError(res, 'NONCE_REUSED');
    device.nonces[nonce] = now();
    return device;
  }

  const currentConfigBytes = () => Buffer.from(JSON.stringify(state.config), 'utf8');

  function compiledGates() {
    const compile = (p) => new RegExp(p.pattern, p.flags.includes('i') ? 'iu' : 'u');
    return { otp: state.config.otp_patterns.map(compile) };
  }

  function looksLikeOtp(text) {
    const n = normalise(text);
    return compiledGates().otp.some((re) => re.test(n));
  }

  // ---- routes --------------------------------------------------------------------------------

  function route(req, res, body) {
    const path = req.url.split('?')[0];
    const key = `${req.method} ${path}`;

    if (req.method === 'GET' && path.startsWith('/mock/files/')) return serveFile(res, decodeURIComponent(path.slice('/mock/files/'.length)));
    if (path.startsWith('/mock/')) return admin(req, res, body, key);
    if (key === 'GET /') return void res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' }).end('paygate mock server (development only)\n');
    if (injectedFailure(req, res)) return;

    switch (key) {
      case 'POST /v1/devices/pair': return pair(res, body);
      case 'GET /v1/devices/config': return withDevice(req, res, body, () => config(res));
      case 'POST /v1/devices/heartbeat': return withDevice(req, res, body, (d) => heartbeat(res, body, d));
      case 'POST /v1/ingest/android': return withDevice(req, res, body, (d) => ingestAndroid(res, body, d));
      case 'POST /v1/ingest/shortcut': return ingestShortcut(req, res, body);
      default: return void sendJson(res, 404, { ok: false, error: { code: 'NOT_FOUND', message: 'یافت نشد', server_time: serverTime() } });
    }
  }

  function withDevice(req, res, body, handler) {
    const device = authenticate(req, res, body);
    if (device) {
      handler(device);
      persist();
    }
  }

  function parseJson(res, body) {
    try {
      return JSON.parse(body.toString('utf8'));
    } catch {
      sendError(res, 'VALIDATION_ERROR', { fields: ['body'] });
      return null;
    }
  }

  function pair(res, body) {
    const req = parseJson(res, body);
    if (!req) return;
    const code = normaliseCode(req.code ?? '');
    const entry = code && state.pairingCodes[code];
    if (!entry || entry.used || now() > entry.expiresAt) return sendError(res, 'PAIRING_CODE_INVALID');
    entry.used = true;
    const deviceId = `dev_${b64u(randomBytes(9))}`;
    const secret = b64u(randomBytes(32));
    state.devices[deviceId] = {
      id: deviceId, secret, name: req.device_name ?? '', os_version: req.os_version ?? '', app_version_code: req.app_version_code ?? 0,
      platform: req.platform, revoked: false, nonces: {}, last_heartbeat_at: null, heartbeats: 0,
    };
    persist();
    sendJson(res, 201, { device_id: deviceId, device_secret: secret, server_public_key: b64u(keys.publicKeyRaw), heartbeat_interval_sec: state.config.heartbeat_interval_sec });
  }

  function config(res) {
    const bytes = currentConfigBytes();
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'x-pg-config-signature': ed25519Sign(keys.privateKey, bytes) });
    res.end(bytes);
  }

  function heartbeat(res, body, device) {
    const hb = parseJson(res, body);
    if (!hb) return;
    device.last_heartbeat_at = serverTime();
    device.heartbeats += 1;
    device.last_heartbeat = hb;
    for (const done of hb.rescans_done ?? []) {
      state.rescans = state.rescans.filter((r) => r.request_id !== done.request_id);
      state.rescansDone.push({ device_id: device.id, ...done });
    }
    sendJson(res, 200, { server_time: serverTime(), config_version: state.config.version, rescan: state.rescans });
  }

  function dedupeKey(device, item) {
    const subject = item.kind === 'deposit' ? normalise(item.body) : `${item.bank}|${item.amount_rial}|${item.balance_rial}`;
    const who = item.channel === 'notification' ? (item.package ?? item.sender) : item.sender;
    return createHash('sha256').update([device.id, item.channel, who, subject, Math.floor(Date.parse(item.sc_timestamp) / 1000)].join('|')).digest('hex');
  }

  function validateItem(item) {
    const bad = [];
    const isStr = (v) => typeof v === 'string' && v.length > 0;
    if (!isStr(item.local_id)) bad.push('local_id');
    if (!['deposit', 'balance_marker'].includes(item.kind)) bad.push('kind');
    if (!['sms', 'notification'].includes(item.channel)) bad.push('channel');
    if (!isStr(item.sc_timestamp) || Number.isNaN(Date.parse(item.sc_timestamp))) bad.push('sc_timestamp');
    if (!isStr(item.received_at) || Number.isNaN(Date.parse(item.received_at))) bad.push('received_at');
    if (typeof item.config_version !== 'number') bad.push('config_version');
    if (item.channel === 'sms' && !isStr(item.sender)) bad.push('sender');
    // A notification item (deposit or marker, CCR-D1) names its source in `package`.
    if (item.channel === 'notification' && !isStr(item.package)) bad.push('package');
    if (item.kind === 'deposit') {
      if (!isStr(item.body)) bad.push('body');
    } else if (item.kind === 'balance_marker') {
      if (!isStr(item.bank)) bad.push('bank');
      // CCR-1: the withdrawal template's account group, or null; absent from phones that predate it.
      if (item.account !== undefined && item.account !== null && !(typeof item.account === 'string' && item.account.length <= 100)) bad.push('account');
      if (!/^\d+$/.test(item.amount_rial ?? '')) bad.push('amount_rial');
      if (!/^\d+$/.test(item.balance_rial ?? '')) bad.push('balance_rial');
      if ('body' in item) bad.push('body'); // a withdrawal text must never arrive
    }
    return bad;
  }

  /**
   * Shapes follow the real server (docs from its reply, 2026-11-02): a batch that cannot be answered per item gets a
   * 422 whose `fields` is an object keyed by zod-style paths; anything wrong inside one item comes back as a
   * `rejected` result with a reason, which the phone treats as final.
   */
  function ingestAndroid(res, body, device) {
    const payload = parseJson(res, body);
    if (!payload) return;
    const items = payload.items;
    const max = state.config.upload.max_batch;
    if (!Array.isArray(items) || items.length === 0) return sendError(res, 'VALIDATION_ERROR', { fields: { items: 'Invalid input: expected a non-empty array' } });
    if (items.length > max) return sendError(res, 'VALIDATION_ERROR', { fields: { items: `Too big: expected array to have <=${max} items` } });

    const unusable = {};
    items.forEach((item, i) => {
      if (!item || typeof item.local_id !== 'string' || item.local_id === '') unusable[`items.${i}.local_id`] = 'Invalid input: expected string, received undefined';
    });
    if (Object.keys(unusable).length > 0) return sendError(res, 'VALIDATION_ERROR', { fields: unusable });

    const rejected = (item, reason) => ({ local_id: item.local_id, status: 'rejected', reason });
    const withdrawalRes = state.config.banks.flatMap((b) => b.withdrawal ?? []).map((t) => new RegExp(t.pattern, t.flags.includes('i') ? 'iu' : 'u'));

    const results = items.map((item) => {
      const bad = validateItem(item);
      if (bad.includes('sc_timestamp') || bad.includes('received_at')) return rejected(item, 'invalid_timestamp');
      if (bad.includes('sender')) return rejected(item, 'missing_sender');
      if (bad.length > 0) return rejected(item, 'invalid_item');
      if (item.kind === 'deposit') {
        if (looksLikeOtp(item.body)) {
          state.otpViolations.push({ device_id: device.id, local_id: item.local_id });
          return rejected(item, 'otp_detected');
        }
        const text = normalise(item.body);
        if (withdrawalRes.some((re) => re.test(text))) {
          state.otpViolations.push({ device_id: device.id, local_id: item.local_id, kind: 'withdrawal_text' });
          return rejected(item, 'withdrawal_text');
        }
      } else if (!state.config.banks.some((b) => b.bank === item.bank)) {
        return rejected(item, 'unknown_bank');
      }
      const hash = dedupeKey(device, item);
      if (state.dedupe[hash]) return { local_id: item.local_id, status: 'duplicate' };
      state.dedupe[hash] = true;
      state.ingested.push({ device_id: device.id, received_at: serverTime(), ...item });
      return { local_id: item.local_id, status: 'accepted' };
    });
    sendJson(res, 200, { results });
  }

  function ingestShortcut(req, res, body) {
    const auth = String(req.headers.authorization ?? '');
    const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
    if (!state.iosTokens[token]) return void sendJson(res, 401, { ok: false, error: { code: 'BAD_SIGNATURE', message: MESSAGES.BAD_SIGNATURE, server_time: serverTime() } });
    const payload = parseJson(res, body);
    if (!payload) return;
    if (typeof payload.body !== 'string' || !payload.body) return sendError(res, 'VALIDATION_ERROR', { fields: ['body'] });
    if (looksLikeOtp(payload.body)) {
      state.otpViolations.push({ device_id: `ios:${token.slice(0, 12)}`, local_id: null });
      return void sendJson(res, 200, { status: 'ignored' });
    }
    const hash = createHash('sha256').update([token, normalise(payload.body), String(payload.client_time ?? '').slice(0, 16)].join('|')).digest('hex');
    if (state.dedupe[hash]) return void sendJson(res, 200, { status: 'duplicate' });
    state.dedupe[hash] = true;
    state.shortcutIngested.push({ token_prefix: token.slice(0, 12), shortcut_version: payload.shortcut_version, body: payload.body });
    sendJson(res, 200, { status: 'accepted' });
  }

  function existingFile(name) {
    if (!filesDir) return null;
    const file = join(filesDir, basename(name)); // basename: no path traversal
    return existsSync(file) && statSync(file).isFile() ? file : null;
  }

  function serveFile(res, name) {
    const file = existingFile(name);
    if (!file) return void sendJson(res, 404, { ok: false });
    const bytes = readFileSync(file);
    const type = file.endsWith('.apk') ? 'application/vnd.android.package-archive' : 'application/octet-stream';
    res.writeHead(200, { 'content-type': type, 'content-length': bytes.length }).end(bytes);
  }

  // ---- development controls (no authentication) -----------------------------------------------

  function admin(req, res, body, key) {
    const input = body.length ? JSON.parse(body.toString('utf8')) : {};
    switch (key) {
      case 'GET /mock/state': return sendJson(res, 200, summary());
      case 'POST /mock/pairing-codes': {
        const code = [...randomBytes(8)].map((b) => CROCKFORD[b % 32]).join('');
        state.pairingCodes[code] = { used: false, expiresAt: now() + PAIRING_TTL_MS };
        return sendJson(res, 201, { code, link: pairingLink(code), expires_in_sec: PAIRING_TTL_MS / 1000 });
      }
      case 'POST /mock/ios-token': {
        const token = `pgd_ios_${b64u(randomBytes(24)).replace(/[-_]/g, 'x').slice(0, 32)}`;
        state.iosTokens[token] = true;
        return sendJson(res, 201, { token });
      }
      case 'POST /mock/config': {
        // Shallow merge of the given top-level keys, then a version bump, so the next heartbeat announces it.
        state.config = { ...state.config, ...input, version: state.config.version + 1, issued_at: serverTime() };
        return sendJson(res, 200, { version: state.config.version });
      }
      case 'POST /mock/app-update': {
        // Offers a file from filesDir as the update: computes its SHA-256 and link, bumps the config version.
        const file = input.file && existingFile(input.file);
        if (!file) return void sendJson(res, 404, { ok: false, error: 'no such file in the mock files folder' });
        const latest = input.latest_version_code;
        const app_update = {
          latest_version_code: latest,
          min_version_code: input.min_version_code ?? 1,
          url: input.url ?? `${publicUrl}/mock/files/${encodeURIComponent(basename(file))}`,
          sha256: input.sha256 ?? createHash('sha256').update(readFileSync(file)).digest('hex'),
        };
        state.config = { ...state.config, app_update, version: state.config.version + 1, issued_at: serverTime() };
        return sendJson(res, 200, { version: state.config.version, app_update });
      }
      case 'POST /mock/rescan': {
        const request = { request_id: `rs_${b64u(randomBytes(6))}`, from: input.from, to: input.to, sim_slot: input.sim_slot ?? 0 };
        state.rescans.push(request);
        return sendJson(res, 201, request);
      }
      case 'POST /mock/fail-next': {
        state.failNext.push({ path: input.path ?? null, status: input.status, code: input.code, retry_after: input.retry_after, times: input.times ?? 1 });
        return sendJson(res, 201, { queued: state.failNext.length });
      }
      case 'POST /mock/revoke': {
        if (state.devices[input.device_id]) state.devices[input.device_id].revoked = true;
        return sendJson(res, 200, { ok: true });
      }
      case 'POST /mock/reset': {
        Object.assign(state, freshState(baseConfig));
        return sendJson(res, 200, { ok: true });
      }
      default: return void sendJson(res, 404, { ok: false });
    }
  }

  const pairingLink = (code) =>
    `${state.brand.pairScheme}://${state.brand.pairHost}?v=1&base=${encodeURIComponent(publicUrl)}&code=${code}`;

  function summary() {
    return {
      config_version: state.config.version,
      public_key: b64u(keys.publicKeyRaw),
      devices: Object.values(state.devices).map((d) => ({
        id: d.id, name: d.name, platform: d.platform, revoked: d.revoked, heartbeats: d.heartbeats, last_heartbeat_at: d.last_heartbeat_at,
        queue_depth: d.last_heartbeat?.queue_depth ?? null, config_version: d.last_heartbeat?.config_version ?? null, last_heartbeat: d.last_heartbeat ?? null,
      })),
      ingested: state.ingested,
      shortcut_ingested: state.shortcutIngested,
      otp_violations: state.otpViolations,
      pending_rescans: state.rescans,
      rescans_done: state.rescansDone,
      fail_next: state.failNext,
    };
  }

  return {
    server,
    state,
    publicKeyRaw: keys.publicKeyRaw,
    newPairingCode() {
      const code = [...randomBytes(8)].map((b) => CROCKFORD[b % 32]).join('');
      state.pairingCodes[code] = { used: false, expiresAt: now() + PAIRING_TTL_MS };
      return { code, link: pairingLink(code) };
    },
    listen: (port = 0, host = '127.0.0.1') => new Promise((resolve) => server.listen(port, host, () => resolve(server.address()))),
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

function freshState(baseConfig) {
  return {
    brand: readBrand(),
    config: structuredClone(baseConfig),
    pairingCodes: {},
    devices: {},
    iosTokens: {},
    dedupe: {},
    ingested: [],
    shortcutIngested: [],
    otpViolations: [],
    rescans: [],
    rescansDone: [],
    failNext: [],
  };
}

function readBrand() {
  const text = readFileSync(new URL('../../../brand.properties', import.meta.url), 'utf8');
  const get = (k) => text.split(/\r?\n/).find((l) => l.startsWith(`${k}=`))?.slice(k.length + 1).trim();
  return { pairScheme: get('brand.pairScheme'), pairHost: get('brand.pairHost') };
}

const snapshot = (s) => ({ ...s, brand: undefined });
const restore = (s, saved) => Object.assign(s, saved, { brand: s.brand });

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let tooLarge = false;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) tooLarge = true; // keep reading (and discarding) so the 413 can be delivered cleanly
      else chunks.push(c);
    });
    req.on('end', () => (tooLarge ? reject(Object.assign(new Error('too large'), { code: 'TOO_LARGE' })) : resolve(Buffer.concat(chunks))));
    req.on('error', reject);
  });
}
