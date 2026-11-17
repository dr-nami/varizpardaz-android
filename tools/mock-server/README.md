# Device API mock server

An in-memory stand-in for the device-facing endpoints of `docs/device-api.md`, so the Android app
can be built and tested before the real server exists. Development tool only: no TLS, no rate limits,
state in memory. Zero dependencies; Node 22 or newer.

```bash
node tools/mock-server/server.mjs
```

It prints a pairing code and the full `…://pair?v=1&base=…&code=…` link (scheme from `brand.properties`).
For a phone on the same Wi-Fi, give it the address the phone can reach:

```bash
MOCK_PUBLIC_URL=http://192.168.1.20:8787 node tools/mock-server/server.mjs
```

The emulator reaches the host at `http://10.0.2.2:8787`. Debug builds of the app allow plain `http`; release
builds do not.

| Variable | Default | |
| --- | --- | --- |
| `MOCK_PORT` | `8787` | |
| `MOCK_HOST` | `0.0.0.0` | |
| `MOCK_PUBLIC_URL` | `http://localhost:<port>` | base URL put into pairing links |
| `MOCK_STATE_FILE` | none | keep devices across restarts (`.state/` is git-ignored) |
| `MOCK_CONFIG` | `fixtures/config.example.json` | config served to devices |
| `MOCK_MERCHANT_NAME` | `فروشگاه آزمایشی` | `merchant_name` added to the served config (CCR-D2); set it empty to leave it out, like a server that does not send it yet |
| `MOCK_FILES_DIR` | none | folder served at `/mock/files/<name>` (see "Trying the in-app update") |

## Trying the in-app update

`MOCK_FILES_DIR` names a folder of files that the mock serves at `/mock/files/<name>` (an APK gets the Android package type and its length).
`POST /mock/app-update` offers one of them as the update: it computes the real SHA-256, points the link at the mock itself and bumps the config version, so the next heartbeat tells the phone.

```bash
MOCK_FILES_DIR=./apks MOCK_PUBLIC_URL=http://10.0.2.2:8787 node tools/mock-server/server.mjs
curl -X POST http://127.0.0.1:8787/mock/app-update -d '{"file":"listener-2.apk","latest_version_code":2,"min_version_code":1}'
```

Build the other versions with `./gradlew -PappVersionCode=2 -PappVersionName=0.2.0 :app:assembleDebug` (same debug key, so Android accepts the update). A `min_version_code` above the installed version stops uploads on the phone.

## What it implements

| Endpoint | Notes |
| --- | --- |
| `POST /v1/devices/pair` | single-use code, 10 minute expiry, Crockford forgiveness (`I`/`L`→1, `O`→0) |
| `GET /v1/devices/config` | signed request; body signed with Ed25519, header `X-PG-Config-Signature` |
| `POST /v1/devices/heartbeat` | returns `config_version` and pending `rescan` requests until reported in `rescans_done` |
| `POST /v1/ingest/android` | HMAC check, batch limit, dedupe, **OTP gate and "no text in balance markers" checks** |
| `POST /v1/ingest/shortcut` | bearer token from `/mock/ios-token`, for trying the Shortcut body by hand |

Request signing follows section 4 exactly (skew 300 s → `CLOCK_SKEW`, nonce window 10 min → `NONCE_REUSED`,
`BAD_SIGNATURE`, `DEVICE_REVOKED`). Errors have the contract shape with Persian messages.

## Development controls (no authentication, never expose these)

| Call | Effect |
| --- | --- |
| `GET /mock/state` | devices (with their last heartbeat payload), everything ingested, OTP violations, pending rescans |
| `POST /mock/pairing-codes` | new pairing code and link |
| `POST /mock/ios-token` | new `pgd_ios_…` token |
| `POST /mock/config` `{…}` | shallow-merge keys into the config and bump `version` |
| `POST /mock/rescan` `{"from","to","sim_slot"}` | queue a rescan for the next heartbeat |
| `POST /mock/fail-next` `{"path","status"\|"code","retry_after","times"}` | make the next requests fail (uploader tests) |
| `POST /mock/revoke` `{"device_id"}` | next signed request gets `DEVICE_REVOKED` |
| `POST /mock/reset` | forget everything |

`otp_violations` in `/mock/state` must stay empty during a field test: it records any OTP text that reached
the server (it is rejected, but it should never have left the phone).

## Tests

```bash
npm test --prefix tools/mock-server     # also runs the shared normalise vectors
node tools/mock-server/gen-vectors.mjs  # regenerates core/src/test/resources/vectors/cross-language.json
```

`cross-language.json` holds HMAC and Ed25519 known-answer vectors made with Node's `crypto`; the Kotlin tests
in `:core` reproduce and verify them, so the signer and verifier are checked against a second implementation.

## Differences from the real server

- Ingest answers like the real server (its reply, 2026-11-02): a batch-level problem is a 422 whose `fields` is an object keyed by zod-style paths (`items.3.local_id`; more than `max_batch` items is key `items`), a body over 256 KB is a 413, and anything wrong inside one item is a final `rejected` result with a reason (`invalid_item`, `invalid_timestamp`, `missing_sender`, `otp_detected`, `withdrawal_text`, `unknown_bank`). Dedupe hashes the parsed whole-second instant.
- Only the rules in the contract are implemented. There is no matching engine, no card binding, no real rate limit.
