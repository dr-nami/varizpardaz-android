# Android listener

Kotlin app that runs on the merchant's phone: it receives bank SMS (and optionally bank-app notifications),
drops everything that is not from a configured bank sender, drops every one-time password, and uploads
deposit texts and withdrawal *markers* (amount and balance only) to the server. Contract:
[`docs/device-api.md`](docs/device-api.md).

| Module | What |
| --- | --- |
| `core/` | Pure Kotlin/JVM, no Android imports: normaliser, regex dialect, classifier, request signer, config model and Ed25519 verification, wire DTOs, pairing-link parser. Fully unit-tested. |
| `app/` | Compose UI and everything that needs Android. |
| `tools/mock-server/` | Node mock of the device API for development (`README.md` inside). |
| `third_party/` | Bundled font (Vazirmatn, OFL) with source and hashes. |

## Build and test

Needs JDK 17 or newer and the Android SDK (platform 36, build-tools 36). On Windows the JDK that ships with
Android Studio (`Android Studio\jbr`) is fine.

```bash
./gradlew :core:test                          # normaliser, classifier, signer, config, contract fixtures
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
npm test --prefix tools/mock-server           # mock server and shared normalise vectors
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk` (application id gets a `.debug` suffix, so it can sit
next to a release build).

If `dl.google.com` is not reachable from your network, give Gradle your proxy in `~/.gradle/gradle.properties`
(not in the repository):

```properties
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=12334
```

## Fixtures

`:core` tests read the contract fixtures in [`fixtures/`](fixtures) (the same files the server tests use). To use another
copy, pass `-PfixturesDir=/path/to/fixtures`.

Only `testbank` fixtures exist so far, and they are synthetic: no template may be marked `verified` until real,
anonymised samples are added (`:core` has a test that enforces this).

## Releases and updates

The app updates itself from the signed config's `app_update` (download, SHA-256 check, the system installer), and a phone below `min_version_code` stops uploading until it is updated.
Signing, the release workflow, publishing and how to rebuild and compare an APK are in [docs/release.md](docs/release.md). The version is in `version.properties`.
The application id (`com.varizpardaz.listener`) and the license (Apache-2.0) are decided; what is left for a first release is the signing key and a dry run of the workflow (see the "Before the first release" list there).

## Field testing

[docs/test-plan.md](docs/test-plan.md) is the checklist to run on each real phone (with the server-side checks and the results sheet). The app has been exercised on emulators only until a Xiaomi and a Samsung have passed it.

## License

Apache License 2.0: see [LICENSE](LICENSE) and [NOTICE](NOTICE) (the bundled Vazirmatn font and the libraries keep their own licenses).

## One place for the product name

The product name is **«واریز پرداز»** (Latin slug `varizpardaz`, domain `varizpardaz.com`). In the app it is in one place:

- `brand.properties`: application id (`com.varizpardaz.listener`, final, **cannot change after the first release**),
  pairing link scheme and host (fixed by the contract, section 3). Gradle feeds it into `BuildConfig`.
- `app/src/main/res/values/strings.xml`: `brand_name` (shown as the app label; a unit test pins its value).

The iOS Shortcut has its own copy (`BRAND_NAME` in `apps/ios-shortcut/build_shortcut.py`), kept equal to this one by a test. The application id is `com.varizpardaz.listener`, and the server
address is never compiled in: it comes from the pairing QR code.

Kotlin packages follow the application id: `com.varizpardaz.listener.core` and `.app`.

## Rules this code lives by

- No SMS text, OTP, card number or secret in logs, `toString()`, exceptions or crash reports.
- No analytics or diagnostics upload. Persian UI text only in `strings.xml` (a unit test fails on Persian
  literals in Kotlin).
- A one-time-password pattern that cannot be compiled rejects the whole config instead of silently weakening the gate.

## Trying it on an emulator

Needs an emulator image (Android Studio's Device Manager) and Node 22+ for the mock server.

```bash
# 1. the mock server, reachable from the emulator as 10.0.2.2; it prints a pairing link
MOCK_PUBLIC_URL=http://10.0.2.2:8787 node tools/mock-server/server.mjs

# 2. install, open the app, paste the link in the debug field, press "اتصال با لینک", allow SMS
./gradlew :app:installDebug

# 3. a simulated bank SMS (the sender must be one of the config's senders; the example config has +989990000000)
adb emu sms send +989990000000 "واریز: ۵,۰۰۰,۰۰۰ ریال حساب: ۱۲۳۴ مانده: ۹۸,۰۰۰,۰۰۰"
adb emu sms send +989990000000 "رمز پویا: 482913"   # must only move the "OTP" counter, never the queue

# 4. instrumented tests (Keystore, Room, the shared fixtures on Android's regex engine)
./gradlew :app:connectedDebugAndroidTest
```

To check a cold start, press Home, then `adb shell am kill <application id>.debug`, then send the SMS. (`am force-stop`
puts the app in Android's "stopped" state, where it gets no broadcasts until it is opened again.)

## About

This is the open-source Android listener of [واریز پرداز (Variz Pardaz)](https://varizpardaz.com), a card-to-card payment
confirmation service. It is published so that anyone can read exactly what the app does with the bank messages on a phone:

- it reads only messages from the bank senders the merchant configured;
- **one-time passwords and login codes never leave the phone**;
- **withdrawal texts never leave the phone**; only a redacted marker (amount and balance) is uploaded;
- everything uploaded is signed with a per-device secret issued when the phone is paired (HMAC, see [docs/device-api.md](docs/device-api.md)).

The signed release APK is distributed from [varizpardaz.com/download/android](https://varizpardaz.com/download/android) with its SHA-256;
the build can be reproduced and compared (see [docs/release.md](docs/release.md)). Report security problems through this repository's private vulnerability reporting (Security tab).
License: Apache-2.0.
