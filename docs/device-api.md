# Device ⇄ Server contract, v1 (frozen)

This file is the only agreement between the server session and the device session.
Neither side changes it on its own (it is versioned with the server).

- Transport: HTTPS only, JSON, UTF-8. Base URL is configured per install (from the pairing QR).
- All timestamps are ISO-8601 with offset (`2026-11-01T10:15:02+03:30`) unless stated otherwise.
- All money values are **decimal strings in rial** (`"1250000"`), never JSON numbers.
- IDs are opaque strings. `local_id` values are UUID v4 generated on the device.

---

## 1. Text normalisation (both sides, identical)

Applied to a *copy* of the text before any matching. The raw text is what gets uploaded.

1. Persian digits `۰-۹` and Arabic-Indic digits `٠-٩` → ASCII `0-9`.
2. `ي` → `ی`, `ك` → `ک`.
3. Remove bidi control characters: U+200E, U+200F, U+202A–U+202E, U+2066–U+2069. Keep ZWNJ (U+200C).
4. Number separators `٬` and `،` → `,`.
5. Collapse runs of whitespace (including newlines) to a single space; trim.

Shared test vectors live in `contracts/fixtures/normalise.json` (`[{ "in": "...", "out": "..." }]`).
Both test suites must pass all of them.

## 2. Regex dialect

Patterns delivered in config must work identically in ECMAScript (V8) and `java.util.regex`:

- Allowed: character classes, quantifiers, alternation, non-capturing groups, named groups `(?<name>...)`.
- Not allowed: lookbehind, atomic/possessive groups, inline flags like `(?i)`, backreferences by name.
- Flags are given separately: `""`, `"i"`. Device maps `"i"` to `CASE_INSENSITIVE | UNICODE_CASE`.
- Named groups with meaning: `amount`, `balance`, `account`, `origin_last4`.
- Patterns run against the **normalised** text.

## 3. Pairing (Android)

The merchant panel shows a QR code for a new Android device:

```
paygate://pair?v=1&base=<url-encoded base URL>&code=<8-char code>
```

The code is Crockford base32, single use, valid for 10 minutes.

`POST /v1/devices/pair` (unauthenticated, rate limited by IP)

```json
{ "code": "AB12CD34", "platform": "android", "device_name": "Galaxy A14",
  "os_version": "14", "app_version_code": 1 }
```

`201`

```json
{ "device_id": "dev_...", "device_secret": "<base64url, 32 bytes>",
  "server_public_key": "<base64url Ed25519 public key>",
  "heartbeat_interval_sec": 30 }
```

The device stores `device_secret` encrypted with an Android Keystore key and pins
`server_public_key` for config verification. A device may be paired to one merchant only.

**iOS** devices do not use this endpoint. The panel creates an `ios_shortcut` device and shows a
token once: `pgd_ios_<32 random base62 chars>`. The user pastes it into the Shortcut at install.

## 4. Request signing (Android)

Every Android request except `/pair` carries:

| Header | Value |
| --- | --- |
| `X-PG-Device` | `device_id` |
| `X-PG-Timestamp` | Unix seconds |
| `X-PG-Nonce` | 16 random bytes, base64url |
| `X-PG-Signature` | `base64url(HMAC-SHA256(device_secret, canonical))` |

```
canonical = "v1" + "\n" + METHOD + "\n" + PATH_WITH_QUERY + "\n" + timestamp + "\n" + nonce + "\n" + hex(sha256(raw_body_bytes))
```

For a `GET`, the body hash is the hash of the empty string. Server rejects: clock skew over
300 s (`CLOCK_SKEW`, response includes `server_time`), a nonce seen for this device in the last
10 minutes (`NONCE_REUSED`), a bad signature (`BAD_SIGNATURE`), a revoked device (`DEVICE_REVOKED`).

## 5. Config

`GET /v1/devices/config` (signed). Response body is the config JSON. Header
`X-PG-Config-Signature: base64url(Ed25519_sign(server_private_key, raw_body_bytes))`.
The device verifies the signature with the pinned key. On failure it keeps the last good config.

```json
{
  "version": 7,
  "issued_at": "2026-11-01T10:00:00+03:30",
  "heartbeat_interval_sec": 30,
  "upload": { "max_batch": 50 },
  "otp_patterns": [
    { "pattern": "رمز\\s*(پویا|یکبار|دوم|عبور)", "flags": "" },
    { "pattern": "\\b(otp|password|passcode)\\b", "flags": "i" },
    { "pattern": "کد\\s*(تایید|تأیید|ورود|فعال\\s*سازی|امنیتی)", "flags": "" }
  ],
  "deposit_keywords": ["واریز", "بستانکار", "انتقال به حساب", "افزایش"],
  "banks": [
    {
      "bank": "mellat",
      "senders": ["+98...", "BankMellat"],
      "notification_packages": ["com.example.bank"],
      "deposit": [ { "id": "mellat-dep-v1", "pattern": "...", "flags": "", "unit": "rial", "status": "unverified" } ],
      "withdrawal": [ { "id": "mellat-wd-v1", "pattern": "...", "flags": "", "unit": "rial", "status": "unverified" } ]
    }
  ],
  "cards": [ { "card_id": "card_...", "bank": "mellat", "label": "6104 **** **** 1234", "sim_slot": 0 } ],
  "app_update": { "latest_version_code": 3, "min_version_code": 1,
                  "url": "https://.../listener-3.apk", "sha256": "<hex>" }
}
```

The values above are illustrative. Real sender IDs and patterns come only from real samples.
`unit` is `"rial"` or `"toman"`; the device converts to rial before upload.
`cards` is for display in the app only; the server does the card binding.

## 6. Device-side classification (normative)

For every incoming SMS or bank-app notification, in this order:

1. **Sender gate.** SMS: sender (spaces and a leading `+` removed) must equal one of `banks[].senders`
   (same normalisation). Notification: package must be in `banks[].notification_packages`.
   No match → drop, count `dropped_sender`. Nothing is stored.
2. **OTP gate.** Normalise the text. If any `otp_patterns` matches → drop, count `dropped_otp`.
   Nothing is stored or uploaded, not even a hash.
3. **Deposit.** If any `deposit` pattern of that bank matches → enqueue a `deposit` item with the
   raw body and the matching `template_id`.
4. **Withdrawal.** Else if any `withdrawal` pattern matches and yields `amount` and `balance` →
   enqueue a `balance_marker` item. The text is not uploaded.
5. **Keyword fallback.** Else if any `deposit_keywords` appears → enqueue a `deposit` item with
   `template_id: null` (lets the server learn new formats).
6. Else drop, count `dropped_unclassified`.

Multipart SMS are concatenated (same sender, same SC timestamp) before step 1.

## 7. Ingest (Android)

`POST /v1/ingest/android` (signed). At most `upload.max_batch` items, body at most 256 KB.

```json
{
  "items": [
    {
      "local_id": "4c0f…",
      "kind": "deposit",
      "channel": "sms",
      "sender": "BankMellat",
      "package": null,
      "sim_slot": 0,
      "subscription_id": 3,
      "sc_timestamp": "2026-11-01T10:15:02+03:30",
      "received_at": "2026-11-01T10:15:04+03:30",
      "body": "<raw text>",
      "template_id": "mellat-dep-v1",
      "config_version": 7,
      "rescan_request_id": null
    },
    {
      "local_id": "9a1e…",
      "kind": "balance_marker",
      "channel": "sms",
      "sender": "BankMellat",
      "sim_slot": 0,
      "subscription_id": 3,
      "sc_timestamp": "2026-11-01T11:02:40+03:30",
      "received_at": "2026-11-01T11:02:41+03:30",
      "bank": "mellat",
      "amount_rial": "1250000",
      "balance_rial": "98000000",
      "template_id": "mellat-wd-v1",
      "config_version": 7,
      "rescan_request_id": null
    }
  ]
}
```

- `sc_timestamp`: SMS = `SmsMessage.getTimestampMillis()` (service-centre time) for live messages and
  the inbox `date_sent` column for rescans. Notification = `StatusBarNotification.postTime`.
- Server dedupe key: `sha256(device_id | channel | sender_or_package | normalised_body_or_marker | sc_timestamp)`.

`200`

```json
{ "results": [ { "local_id": "4c0f…", "status": "accepted" },
               { "local_id": "9a1e…", "status": "duplicate" } ] }
```

`status` is `accepted`, `duplicate` or `rejected` (with `reason`). The device removes all three
from its queue (rejected items are counted and logged locally without the body).
Network errors and 5xx → retry with exponential backoff, max 5 minutes, order preserved.

## 8. Ingest (iOS Shortcut)

`POST /v1/ingest/shortcut`, header `Authorization: Bearer pgd_ios_…`, rate limit 30/min per token.

```json
{ "body": "<raw message text>", "client_time": "2026-11-01T10:15:05+03:30", "shortcut_version": 1 }
```

The Shortcut itself must stop before sending if the text contains any of:
`رمز`، `پویا`، `OTP`، `کد تایید`، `کد تأیید`، `کد ورود`. It sends only if the text contains one of
`واریز`، `بستانکار`، `انتقال به حساب`، `افزایش`.

The server re-runs the OTP gate. If OTP text arrives anyway it is discarded unread, never stored,
and the merchant is alerted. Withdrawal texts arriving here are discarded after extracting a balance
marker. `200 { "status": "accepted" | "duplicate" | "ignored" }`.
Dedupe key: `sha256(device_id | normalised_body | client_time truncated to the minute)`.

## 9. Heartbeat and rescans

`POST /v1/devices/heartbeat` (signed), every `heartbeat_interval_sec`.

```json
{
  "sent_at": "2026-11-01T10:15:30+03:30",
  "app_version_code": 1,
  "battery_pct": 82, "charging": false,
  "network": "wifi",
  "permissions": { "receive_sms": true, "read_sms": true, "notification_listener": false,
                   "post_notifications": true, "ignore_battery_optimizations": true },
  "queue_depth": 0,
  "last_message_at": "2026-11-01T10:15:04+03:30",
  "counters_since_last": { "uploaded": 1, "dropped_sender": 4, "dropped_otp": 1, "dropped_unclassified": 0 },
  "config_version": 7,
  "sims": [ { "slot": 0, "subscription_id": 3, "carrier": "MCI" } ],
  "rescans_done": [ { "request_id": "rs_...", "found": 2 } ]
}
```

`200`

```json
{ "server_time": "2026-11-01T10:15:30+03:30", "config_version": 8,
  "rescan": [ { "request_id": "rs_...", "from": "2026-11-01T09:00:00+03:30",
                "to": "2026-11-01T10:00:00+03:30", "sim_slot": 0 } ] }
```

- If `config_version` is newer than the device's, fetch config.
- `rescan` is sent when the server sees a gap in a card's balance chain. The device reads the SMS
  inbox for that window, runs section 6 on each message, uploads with `rescan_request_id`, and
  reports `rescans_done` in the next heartbeat.
- The server marks a device offline after 2 minutes without a heartbeat.

## 10. Errors

`{ "ok": false, "error": { "code": "...", "message": "<Persian, safe to show>", "server_time": "..." } }`

| Code | HTTP | Device action |
| --- | --- | --- |
| `BAD_SIGNATURE` | 401 | Log, retry once, then show "re-pair needed" |
| `CLOCK_SKEW` | 401 | Store offset from `server_time`, retry immediately |
| `NONCE_REUSED` | 401 | New nonce, retry |
| `DEVICE_REVOKED` | 403 | Stop all work, wipe secret, show re-pair screen |
| `PAIRING_CODE_INVALID` | 400 | Show message, allow rescan of QR |
| `VALIDATION_ERROR` | 422 | Drop the batch item(s) named in `error.fields`, keep the rest |
| `PAYLOAD_TOO_LARGE` | 413 | Halve batch size and retry |
| `RATE_LIMITED` | 429 | Wait `Retry-After` seconds |

## 11. Shared fixtures

```
contracts/fixtures/normalise.json
contracts/fixtures/config.example.json
contracts/fixtures/sms/<bank>/<case>.json
```

Each SMS fixture:

```json
{
  "bank": "mellat",
  "sender": "BankMellat",
  "body": "<anonymised text with original layout>",
  "expected": { "class": "deposit", "amount_rial": "5000000", "balance_rial": "98000000", "account": "1234" },
  "source": "real-anonymised",
  "notes": ""
}
```

`class` is `deposit`, `withdrawal`, `otp` or `other`. `source` is `real-anonymised` or `synthetic`.
Synthetic fixtures test plumbing only and never mark a template `verified`.
Anonymise by replacing names and digits with fake values of the same length; keep layout,
punctuation and line breaks exactly. Both test suites (TS and Kotlin) run every fixture.
