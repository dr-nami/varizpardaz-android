# Releasing the Android listener

This is the whole path from a commit to an APK on a merchant's phone: how a release is made, signed and published, how phones
update themselves, and how anyone can check that an APK was built from this source. Everything here lives under `apps/android`
so that the app can be split into its own public repository.

**Status (what has and has not been exercised):**

| Part | State |
| --- | --- |
| In-app update (download, SHA-256, archive check, system installer) | Run end to end on an emulator (Android 16 image): optional update, required update, user cancel, wrong checksum, wrong file, signing-key mismatch. Not run on a real phone, not on Android 14/15. |
| Signed release build (Gradle) | **First real release built 2026-11-17: 1.0.0 (versionCode 1), signed with the real key** (kept offline by the maintainers). `apksigner`: v2 and v3 verify, signer SHA-256 `af3ff57c58d37591568678cf65840dbd68fb141453f12f8a895f343ce53477f0`. Smoke-tested on the API 36 emulator (installs, first-run QR screen, no crash). Not tested on a real phone. |
| `android-release.yml` | **Never run.** There was no GitHub remote when it was written. Run it on a test tag in a private fork before it touches the real key. |
| Reproducible build | Two clean builds on one machine gave byte-identical unsigned APKs. Not tried on a second machine or another OS. |

## Release facts

1. **Application id:** `com.varizpardaz.listener`. Android treats another id as another app, so it can never change after the first release.
   Debug builds add `.debug`, so both can sit on one phone.
2. **License:** Apache-2.0 (`LICENSE`), with a `NOTICE` that lists the bundled font and the libraries. The release workflow refuses to run without `LICENSE`.
3. **Signing key:** one RSA 3072 key signs every release. Its certificate SHA-256 digest is published in `latest.json` and on the download page,
   so anyone can check that an APK they hold is signed by the same key.
4. **Distribution:** the app is distributed directly from the project website (`https://varizpardaz.com/download/android`), not through Google Play or any app store.
   The public manifest is `https://varizpardaz.com/download/android/latest.json`.

## The signing key

One key signs every release, for the life of the app: a phone only accepts an update signed with the same key as the installed app.
**If the key is lost, every merchant has to uninstall and pair again** (uninstalling also deletes the queue of messages not yet sent).

Create it once, on a machine you trust, outside the repository:

```bash
keytool -genkeypair -keystore varizpardaz-release.jks -alias release -keyalg RSA -keysize 3072 -validity 10000
```

- Keep at least two offline copies of the `.jks` file and of its passwords (not in the repository, not in chat).
- Releases are signed with APK Signature Scheme v2 and v3 (`enableV3Signing`), so the key can later be *rotated* with `apksigner rotate`
  (a lineage that lets phones accept a new key), instead of being stuck with this one forever.
- `*.jks` and `*.keystore` are git-ignored, and a unit test fails if a password is written into `build.gradle.kts`.

Four secrets, stored in a GitHub environment named `android-release` with required reviewers:

| Secret | Content |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the `.jks` file, `base64 -w0 varizpardaz-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | the store password |
| `ANDROID_KEY_ALIAS` | `release` (the alias used above) |
| `ANDROID_KEY_PASSWORD` | the key password |

Gradle reads them as `PAYGATE_KEYSTORE_FILE`, `PAYGATE_KEYSTORE_PASSWORD`, `PAYGATE_KEY_ALIAS`, `PAYGATE_KEY_PASSWORD` (environment or
`-P` properties). With any of the four missing the release build is simply unsigned, which is what pull requests and local builds produce.

## Making a release

1. Edit `apps/android/version.properties`: raise `versionCode` (it must go up with every release; Android refuses an update that does not)
   and set `versionName`. Merge it.
2. Tag that commit `android-v<versionName>` and push the tag, for example `git tag android-v0.2.0 && git push origin android-v0.2.0`.
3. `android-release.yml` checks that the tag matches `versionName`, that `LICENSE` exists and the application id is final; runs the tests;
   builds and signs the APK; verifies the signature with `apksigner`; and **drafts** a GitHub release with
   `listener-<versionName>-<versionCode>.apk`, `SHA256SUMS.txt` and notes. The notes carry the APK's SHA-256, the signing certificate's
   SHA-256 and the `app_update` block for the server.
4. A person reads the draft and publishes it. The workflow never publishes by itself.

## Telling phones about it

The server's signed config carries `app_update` (contracts/device-api.md section 5):

```json
{ "latest_version_code": 3, "min_version_code": 1, "url": "https://.../listener-0.3.0-3.apk", "sha256": "<hex>" }
```

Print the block for a built APK, so the number comes from the file and not from a person:

```bash
node tools/release/update-entry.mjs listener-0.3.0-3.apk --url https://example.com/listener-0.3.0-3.apk --min-version-code 1
```

What the phone does with it:

- `latest_version_code` above the installed version: the status tab offers the update; the app keeps working meanwhile.
- **`min_version_code` above the installed version: the phone stops uploading and shows only an "update required" screen.** Messages keep being
  received and queued (nothing is lost) and go out after the update. Raise the minimum only after the APK is published and reachable, and
  ideally after most phones have the new version. A minimum above `latest_version_code` is treated as a server mistake and ignored, so a typo
  cannot stop every phone for good.
- The link must be `https` (plain `http` is accepted only by the debug build, for the local mock). Redirects from https to https are followed
  (a CDN or GitHub release asset works); a redirect to http is not. The server should send `Content-Length` and no compression: the phone
  counts bytes to notice a cut-off download. The phone refuses a file above 150 MB.
- The phone fetches the file **only after the config's Ed25519 signature checked out**, then verifies in this order: the SHA-256 of the bytes
  equals the config's; the file is this app (`applicationId`) at exactly `latest_version_code`; then the system installer, which only accepts a
  file signed with the installed app's key. The user always confirms in the system's own dialog.
- Not under our control: Android asks the user to allow "install unknown apps" for this app once, and **Google Play Protect** may offer an
  "App scan recommended" prompt for a sideloaded APK it has not seen (it offers *Install without scanning*). While the system dialog is
  up, the update card says that such a prompt may appear and that it comes from the phone; the app cannot press those buttons, and does not
  advise for or against scanning.
- When a download or install fails, the screen says why (no connection, server error, checksum mismatch, wrong file, no space, cancelled,
  blocked by the phone, different signing key) and offers a retry. A different signing key means the user would have to uninstall first, and
  the message says that this deletes the pairing and unsent messages.

## Verifying a download

```bash
sha256sum listener-0.3.0-3.apk                       # must equal the number in the release notes
apksigner verify --print-certs listener-0.3.0-3.apk   # the certificate SHA-256 must equal the one in the release notes
```

## Reproducing a release

Anyone can rebuild an APK from a tag and check that it holds the same files as the published one. The build has no timestamps, no git
information and no dependency blob in it (`dependenciesInfo` and `vcsInfo` are switched off), and the Gradle wrapper, plugin and dependency
versions are pinned in the repository.

```bash
git checkout android-v0.3.0
cd apps/android
./gradlew :app:assembleRelease            # without signing secrets this builds app-release-unsigned.apk
node tools/release/compare-apks.mjs path/to/published.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

`compare-apks.mjs` compares every file of the two APKs by name, CRC-32 and size and **ignores the signature** (which cannot be equal, you do not
have our key). `SAME` means the published APK holds exactly the files this source builds. Use JDK 17 (Temurin, as in the workflow) and the
repository's Gradle wrapper.

**Honest limits.** Reproducibility was checked only by building twice on one machine (identical bytes). Another operating system, JDK vendor or
Android SDK build-tools version might produce differences; if `compare-apks.mjs` reports any, that is a bug in this document's claim, not
necessarily in the APK, and should be reported. The Android SDK build-tools and platform used by the CI runner are not pinned by the repository.
