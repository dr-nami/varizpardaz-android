# Field test plan — Android listener

A manual checklist to run on each test phone, with real bank messages. The app has been exercised only on emulators; this is the part that
needs real phones, real SIMs and real banks. **A9 is done when the required tests below have passed on at least one Xiaomi and one Samsung
phone and the results are reported** (see "Reporting").

Everything the app does is checked on two sides: what the **phone** shows, and what the **server** recorded. The server side is given as
SQL against the server's database, because the merchant panel (M10) is not built yet; the panel will show the same facts. Table and column
names are those of the server's schema at its milestone M6 and may change; the meaning of each query is stated next to it.

## 0. What you need

| Need | Notes |
| --- | --- |
| **Test phones** | At least one **Xiaomi/Redmi/POCO** and one **Samsung**. One of them should be dual SIM (T10). Note brand, model, Android version, and the maker's software version (MIUI/HyperOS, One UI) from Settings → About phone. **TODO: which phones you have (models, Android versions, SIMs): not answered yet.** |
| **Bank SIM(s) and card(s)** | A SIM that receives the bank's SMS, and a card you can pay into and pay out of with small amounts. Use your own money and your own card only. |
| **A test server** | The server session's API, reachable from the phone. Debug builds accept plain `http` on your Wi-Fi; release builds need `https`. The server needs: a merchant, a bank card bound to the phone's SIM slot, and the real bank's **sender id** and a template in the config (otherwise the phone drops the bank's SMS as "not a bank sender": that is the expected, safe behaviour, but it is not a test of anything). |
| **The pairing QR** | From the panel (M10) once it exists. Until then, make a QR of the pairing link the dev server or the mock prints, with a **local** tool (for example `npx qrcode-terminal "<link>"` or `qrencode -t ansiutf8 "<link>"`). Do not paste pairing links into online QR generators. |
| **The APK** | A **debug** build (side by side id ending `.debug`, accepts `http`). For the update test (T12) you need **two versions signed with the same key**: build both on the same computer from the same checkout (`./gradlew -PappVersionCode=1 -PappVersionName=0.1.0 :app:assembleDebug`, copy the APK, then the same with `2` and `0.2.0`). APKs built by different CI runs are signed with different throw-away debug keys and will refuse to update each other. |
| **A computer** | To run SQL, and optionally USB debugging (`adb`) for the privacy check P1. |

Install the APK on the phone by copying it over and opening it (allow "install unknown apps" for the file manager that opens it). If the phone offers
a Google Play Protect scan, either choice is fine for the test; remember which you chose.

Write the results into the sheet at the end. **Never put real message text, card numbers or one-time codes into the report** (see "Reporting").

### Things on screen you will read

The app is Persian only. The labels used in this plan:

| Where | Text | Meaning |
| --- | --- | --- |
| Status tab, top | «سرویس پیامک فعال است» | phone is talking to the server and every required setting is done |
| | «سرویس فعال است، اما چند مورد نیاز به اقدام دارد» | talking to the server, but a required row below is open |
| | «در حال برقراری ارتباط با سرور…» / «ارتباط با سرور قطع است» | connecting (first 90 s after the app starts) / no heartbeat for 2 minutes |
| | «آخرین ارتباط با سرور» | time since the last successful heartbeat (it should never grow past about a minute) |
| Status tab, counters | «در صف ارسال» | queue depth: messages waiting to be uploaded. 0 when all is sent |
| | «ارسال‌شده به سرور» | uploaded since pairing |
| | «نادیده‌گرفته‌شده (فرستندهٔ غیربانکی)» | dropped because the sender is not a configured bank |
| | «رمز یک‌بارمصرف (روی گوشی حذف شد)» | one-time passwords dropped on the phone |
| | «پیامک بانکی بی‌ربط (نگه‌داشته نشد)» | bank sender, but neither a deposit nor a withdrawal: dropped |
| | «ردشده توسط سرور» | uploaded but refused by the server |
| Status tab, checklist | one row per permission/setting with «رفع» (fix) | all required rows must read «فعال» |
| Bottom bar | وضعیت / راهنمای باتری / تنظیمات | status / battery guide / settings |

## 1. Quick smoke test (15 minutes, every phone, before anything long)

1. Install, open, pair (T1). 2. Status tab: all required rows «فعال», top reads «سرویس پیامک فعال است». 3. Send yourself a **small real deposit**
(T3). 4. It arrives on the server. If this fails, stop and report; do not start the long tests.

## 2. Tests

Mark each **R** (required for A9) or **I** (informative: record what happens, it feeds the battery guide and the known limits). Each test has
*Do*, *Phone shows*, *Server shows*. Use the queries in section 4. "Device id" is the `dev_…` shown in Settings → «شناسهٔ دستگاه».

### T0 — Install and permissions (R)

*Do.* Install the APK. Open the app. On Android 13 or newer, if the SMS permission cannot be granted ("restricted setting"), do what the app's hint says:
app settings → ⋮ → allow restricted settings, then grant again. Open the **battery guide** tab, follow the steps for the phone's maker (Xiaomi: Autostart,
battery saver "no restrictions", lock in recents; Samsung: never-sleeping apps, battery "unrestricted", keep open in recents), tap the battery exemption
button and allow it, and tap «مراحل را انجام دادم» when done.
*Phone shows.* Every required row of the status tab reads «فعال». Record which rows needed a second try, and any step of the guide whose button did not open
the maker's screen (the app then opens the app's own settings page and says so): **that is the main data we want from these two phones.** Note every menu
name in the guide that differs from what the phone really shows.
*Server shows.* Query D: `permissions` has `receive_sms`, `read_sms`, `post_notifications`, `ignore_battery_optimizations` all `true`.

### T1 — Pairing (R)

*Do.* (a) Scan the QR. (b) Try an already used or expired QR. (c) Try scanning something that is not a pairing QR. (d) Deny the camera permission once.
*Phone shows.* (a) the status tab. (b), (c), (d) a Persian message that says what to do, never a crash or a blank screen.
*Server shows.* Query D: a device row with the phone's model in `name`, `appVersionCode`, and `lastSeenAt` within the last minute.

### T2 — Re-pair and unpair (R)

*Do.* (a) Revoke the device on the server (set `"revokedAt" = now()` on its `devices` row, or use the panel). Wait up to a minute. (b) Pair again with a new QR.
(c) In Settings, tap «جدا کردن دستگاه» and confirm.
*Phone shows.* (a) the "re-pair needed" screen. (b) works. (c) back to the pairing screen; nothing left of the old connection.
*Server shows.* (a) after the revoke no heartbeats are accepted (`lastSeenAt` stops moving).

### T3 — A real deposit (R)

*Do.* Pay a small amount into the test card (from another card of yours). Note the exact time. Repeat three ways: (a) screen on, app in front; (b) screen off,
phone locked, app closed from view but not swiped away; (c) the phone has been idle with the screen off for at least 30 minutes (deep sleep).
*Phone shows.* «ارسال‌شده به سرور» goes up by 1 per deposit and «در صف ارسال» returns to 0.
*Server shows.* Query M: a message from the bank's sender with `parseStatus = parsed` (or `unparsed` if the template does not fit yet: report that, it is
exactly what the real SMS sample is for), `bank`, `simSlot`, `templateId`. Query P (deposits): `amountRial`, `balanceAfter`, and the latency
`"createdAt" - "occurredAt"`.
*Record.* Seconds from the bank's SMS to the server row for (a), (b), (c). Do not try to "pass" a number: we need the observed values.

### T4 — A withdrawal never leaves the phone (R)

*Do.* Make a small payment out of the test card (a purchase or a transfer) so the bank sends a withdrawal SMS.
*Phone shows.* If the bank's withdrawal template is in the config, «ارسال‌شده به سرور» goes up by 1: what was uploaded is a **balance marker** (the amount and the new balance, no text).
If the template is not in the config the message is dropped as «پیامک بانکی بی‌ربط» and nothing is uploaded (also correct, and the reason to collect a real withdrawal sample).
*Server shows.* Query B: at most one `marker` row with amount and balance numbers. Query M: **no** message row for the withdrawal. Query A: **no** `withdrawal_text` alert
(that alert would mean the phone uploaded a withdrawal text, which must never happen).

### T5 — One-time passwords and other senders never leave the phone (R)

*Do.* (a) Cause the bank to send you a one-time password (for example log in to the bank's app or request a dynamic password). (b) Have someone send you an
ordinary SMS from a normal number. (c) Receive an SMS from another service that sends codes. **Do not read the code out loud in reports.**
*Phone shows.* (a) «رمز یک‌بارمصرف» +1. (b), (c) «نادیده‌گرفته‌شده (فرستندهٔ غیربانکی)» +1 each.
*Server shows.* Query A: **no** `otp_detected` alert after the time of (a). Query M: no message row at the time of (a), (b), (c). The server also re-checks every body it receives
for one-time passwords, so an `otp_detected` alert would mean the phone's own gate failed: that is a **stop-the-line failure**, report it at once.

### T6 — Losing the connection (R)

*Do (a), data only.* Switch off mobile data and Wi-Fi but keep the SIM registered. Make a real deposit. Wait 10 minutes. Switch data back on.
*Phone shows (a).* While offline: «ارتباط با سرور قطع است» after about two minutes and «در صف ارسال» 1 after the deposit. After recovery: the queue returns to 0 within a few minutes.
*Do (b), airplane mode.* Airplane mode on for 10 minutes. Make a real deposit during that time (the bank's SMS will reach the phone only after the radio is back, if your carrier holds it).
Airplane mode off.
*Server shows (a, b).* Query A: a `device_offline` alert opened and later resolved; Query M: the deposit arrives after recovery; Query C (rescans): if the server noticed a balance gap, a rescan request that is
delivered and completed. Record how long recovery took.

### T7 — Reboot (R)

*Do.* (a) Reboot; unlock the phone once; **do not open the app**; wait two minutes. (b) Reboot; **do not unlock**; have a deposit sent during that time; then unlock. (c) Make a deposit while the phone is
off or booting.
*Phone shows (a).* After unlocking, the status notification «در حال نظارت بر پیامک‌های بانکی» reappears.
*Server shows (a).* `lastSeenAt` resumes within about two minutes of the unlock. *(b), (c).* Record whether and when the deposit reached the server: Android may hold SMS for apps until the first unlock.
A deposit that arrives while the app is not running should still be recovered by a rescan; note whether the server asked for one (Query C).

### T8 — Killing the app (R)

*Do.* (a) Swipe the app away from the recent apps list. (b) On Xiaomi also use "clear all" from recents; on Samsung also use "Close all". Make a real deposit one minute later. Repeat once after locking the
app card in recents (Xiaomi: lock icon; Samsung: "Keep open") and once after force-stopping from the app's system settings page.
(c) Open the app's own battery page in system settings (App info → Battery) and choose the most restrictive option the phone offers (stock Android calls it **Restricted**; makers name it differently: write down the name).
*Phone shows.* Record whether «در حال نظارت بر پیامک‌های بانکی» is still in the notification shade. For (c): when the app is opened again the status tab must show «محدودیت اجرا در پس‌زمینه»
as an open row, and the heartbeat stops while Restricted (the emulator's service was stopped within seconds and did not come back by itself). Set it back to Unrestricted and record whether the next received SMS
brings the heartbeat back without opening the app.
*Server shows.* Whether the deposit arrived, and `lastSeenAt` kept moving (heartbeats continue).
*Expected.* After a swipe-away the app should keep working. After a **force stop** it does not run until the user opens it again: that is how Android works and is **not** a failure. What matters is
which of the other actions stop it on each maker; write that down for each.

### T9 — A day of idleness (R)

*Do.* Start from a state where all required rows are «فعال». Leave the phone with the screen off and Wi-Fi on for 24 hours, not touched and **not** on the charger for the first 12 hours.
Make a real deposit at the start, once at night and once at the end.
*Server shows.* Query A: how many `device_offline` alerts opened during the 24 hours and how long each lasted (`createdAt` to `resolvedAt`). All three deposits arrived. At the end `lastSeenAt` is recent.
*Pass.* All three deposits arrived. Each offline period, if any, is explained (record the cause: Wi-Fi sleep, the maker's cleaner, a restart). More than a few minutes of silence with no cause is a finding.

### T10 — Two SIMs (R on the dual SIM phone)

*Do.* With a bank SIM in each slot (or the same bank on both), receive a deposit on slot 1, then on slot 2. Then change the phone's "preferred SIM for SMS" and repeat.
*Server shows.* Query M: `simSlot` 0 and 1 respectively (slots count from 0); Query D: `sims` lists both slots after each has received a message. The card bound to each slot is credited. Record anything
different: some phones report the slot as 1 and 2, or give a wrong `subscriptionId`.

### T11 — Bank-app notifications (I)

Only if the bank has an app that notifies of deposits. Settings → switch on «خواندن اعلان اپ بانک (اختیاری)», allow notification access, add the bank app's package to the config (`notification_packages`).
Make a deposit. Record the notification's visible text layout (title vs body) with the amounts and card digits masked, so a real template can be written.

### T12 — App update (R)

*Do.* Pair the older version (version code 1). On the server publish the newer APK's `app_update` (`node tools/release/update-entry.mjs <apk> --url <https link>`; the debug build also accepts `http`).
(a) *Optional*: `latest_version_code` 2, `min_version_code` 1. The status tab shows a card; tap «دانلود و نصب»; allow "install unknown apps" for the app when asked; confirm the system dialog (a Play Protect prompt
may appear first). (b) *Cancel* in the system dialog once: a Persian message and a retry button. (c) *Required*: set `min_version_code` 3 with a version-3 APK. The phone now shows only the update screen. Make a deposit **before** updating: the server must show nothing new in
Query M and `queueDepth` 1 in Query D (the message waits on the phone), then update, and the deposit must appear in Query M once. (d) *After updating,
do not open the app*: make a deposit and see whether it arrives.
*Phone shows.* The app restarts by itself after the update; the notification «در حال نظارت…» is back; the old version number is gone from Settings.
*Server shows.* `appVersionCode` changes in Query D; the deposit queued during the block is uploaded once after the update.
*Record.* Whether the maker's own installer or "security" apps added extra prompts (Xiaomi often does), and whether the service restarted without opening the app.

### T13 — Clock and permissions changed under the app (I)

(a) Turn off automatic time and set the clock 10 minutes wrong; make a deposit; set it back. The upload should still be accepted (the phone corrects its signatures by the server's time); report if the status shows
trouble. (b) With the app running, revoke the SMS permission in system settings: the status tab shows the row open again within a minute, and Query D's `permissions.receive_sms` turns `false` at the next heartbeat.

## 3. Privacy check (R, needs a computer)

**P1 — no message text in the phone's logs.** With USB debugging on and the phone connected, run `adb logcat -c`, then do T3, T4 and T5 once, then `adb logcat -d > log.txt` and search `log.txt` for the amount you
paid, the last four digits of the card, the bank's name as it appears in its SMS, and any word from the text. **None of them may appear in lines from the app's process** (the app logs no message text by design).
**P2 — crash reports.** The app has no crash reporter and no analytics; confirm no extra network host is contacted: with the phone on your Wi-Fi, check the router or the server log: the phone talks to the test server
only (and, during an update, to the host in `app_update.url`).

## 4. Server queries

For the development database from the server's `docker-compose.yml`: `docker compose exec postgres psql -U paygate -d paygate`. Replace `<dev_id>`. Column names are quoted because they are camel case.

```sql
-- D: the phone as the server sees it
SELECT id, name, "appVersionCode", "lastSeenAt", now() - "lastSeenAt" AS silent_for, battery, charging, network,
       "queueDepth", "lastMessageAt", "configVersion", permissions, sims, counters, "revokedAt"
FROM devices WHERE id = '<dev_id>';

-- M: deposit-like messages from the phone (the bodies are encrypted and not shown)
SELECT id, sender, bank, "simSlot", "scTimestamp", "receivedAt", "parseStatus", "templateId", "rescanRequestId"
FROM sms_messages WHERE "deviceId" = '<dev_id>' ORDER BY "createdAt" DESC LIMIT 20;

-- P: deposits and detection latency
SELECT d.id, d."amountRial", d."balanceAfter", d."occurredAt", d."createdAt", d."createdAt" - d."occurredAt" AS latency, d."matchState"
FROM deposits d JOIN sms_messages s ON s.id = d."smsId" WHERE s."deviceId" = '<dev_id>' ORDER BY d."createdAt" DESC LIMIT 20;

-- B: balance markers (withdrawals reduced to numbers)
SELECT kind, amount, "balanceAfter", "occurredAt", "receivedAt", "chainStatus"
FROM balance_events WHERE "deviceId" = '<dev_id>' ORDER BY "occurredAt" DESC LIMIT 20;

-- A: alerts about the phone (device_offline, otp_detected, withdrawal_text, balance_gap, ...)
SELECT kind, severity, "createdAt", "lastSeenAt", count, "resolvedAt" FROM alerts WHERE "deviceId" = '<dev_id>' ORDER BY "createdAt" DESC;

-- C: rescan requests (the server asking the phone to re-read its inbox)
SELECT "fromAt", "toAt", status, "foundCount", "deliveredAt", "completedAt" FROM rescan_requests WHERE "deviceId" = '<dev_id>' ORDER BY "createdAt" DESC;
```

## 5. Already covered, so not repeated here

Checked on emulators (Android 16 for everything; see the release notes for the exact scope): classification and the shared fixtures on Android's regex engine, the encrypted queue, upload retries and backoff, the
signed requests and config, the heartbeat and rescans, the foreground service and its restart after reboot and update, the optional notification channel, the status and checklist screens, and the update flow including a
wrong checksum, a wrong file and a different signing key. None of that has been seen on a Xiaomi or a Samsung, which is why the tests above repeat the user-visible parts.

## 6. Known limits (read before calling something a failure)

- After a **force stop** (or "Stop app" in a maker's cleaner) Android delivers nothing to the app until it is opened again. The guide asks the user to avoid it; it cannot be prevented from inside the app.
- SMS received while the phone is off or in airplane mode reach the phone only when the carrier delivers them; the app can only recover what is in the inbox (rescans, needs the SMS-reading permission).
- Until the first unlock after a reboot the app's storage is locked; messages may be held until then.
- Google Play Protect and some makers' "security" apps may add prompts to installing an update. The app cannot answer them.
- The maker screens opened by the guide's buttons are best guesses (see the A7 notes); if a button opens the app's own settings page instead, that is the designed fallback.

## 7. Reporting

Per phone, one sheet (copy the table). Mark **P**ass, **F**ail or **N** (not done) and write what you saw. For a failure add: the time, what you did, what the phone and the server showed.

| Phone | brand / model / Android / maker software / SIMs | App version | Server version |
| --- | --- | --- | --- |
| Xiaomi | TODO: model / Android / MIUI or HyperOS / SIMs | | |
| Samsung | TODO: model / Android / One UI / SIMs | | |

| Test | R/I | Result | Observed (seconds, behaviour, notes) |
| --- | --- | --- | --- |
| T0 install and permissions | R | | |
| T1 pairing | R | | |
| T2 re-pair and unpair | R | | |
| T3 deposit (a / b / c) | R | | |
| T4 withdrawal | R | | |
| T5 one-time passwords and other senders | R | | |
| T6 connection loss (a / b) | R | | |
| T7 reboot (a / b / c) | R | | |
| T8 killing the app | R | | |
| T9 a day of idleness | R | | |
| T10 two SIMs | R (dual SIM phone) | | |
| T11 bank-app notifications | I | | |
| T12 app update (a / b / c / d) | R | | |
| T13 clock and permissions | I | | |
| P1 no text in logs | R | | |
| P2 no extra hosts | R | | |

**Send back:** the sheet, the answers to the T0 questions about the guide (which menu names or buttons differed), and **anonymised message samples** for each bank message type you received
(sender id exactly as shown, SIM slot, the text with card and account digits replaced by other digits of the same length and the amounts kept or changed consistently, the unit rial or toman, and the time).
Those samples are what lets a template be marked `verified`. **Never send a real one-time password, a full card number, or a text you have not anonymised.**

When both a Xiaomi and a Samsung have a sheet with every R test passed, A9 is done. Anything marked F becomes a fix in the app, the guide texts or the known limits above, and the failed tests are repeated.
