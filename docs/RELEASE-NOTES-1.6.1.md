# CURAJ Auto Login 1.6.1

This is a patch release. The campus network changed how it treats plain HTTP,
and the app's online check stopped working on it: a logged-in device looked
"blocked" forever, and login verification could fail after a real success.

## Highlights

- **Authoritative online check (`isOnline`)**: the gateway Squid now answers
  browser-style origin-form requests (`GET /generate_204` + `Host:`) with
  `400 ERR_INVALID_URL` even for authenticated clients, so the old probe could
  never see `204` after login. `isOnline()` sends the same check as a
  proxy-form (absolute-URI) request — `GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1` —
  on a raw socket bound to the Wi-Fi network. The Squid proxies that form
  normally post-auth and still intercepts it with the splash page pre-auth, so
  online/blocked is detected correctly in both states.
- **All online checks use it**: "already online" on entry, the
  authentication-in-progress wait, post-login verification, and logout
  verification.
- **Logout verification no longer lies**: a 400 from the Squid is not mistaken
  for a successful logout.
- **Preserved everything from 1.6**: Auto connect default-on, Sentry
  monitoring with source context, credential/PII scrubbing, update restart,
  live metrics, session sheet, explicit logout, `EPERM` retry, and no
  mobile-data fallback for portal requests.

The same issue was diagnosed and fixed in the Linux client (`linux/curaj-login`)
at the same time; see that file's header for the network analysis.

## Install

- Package: `in.ac.curaj.autologin`
- Version: `1.6.1` (versionCode `8`)
- APK: `CURAJ-AutoLogin-1.6.1.apk`
- SHA-256: `906887ea15e30c153cff9cd3fd84528514f6215aa86be05448f2fa17ee835faf`
- Signature: APK Signature Scheme v2; same release certificate as 1.6
  (`55da02f424dbf6a4dfd9521b236c1bba4f598fe23db477afceea82fa894ac092`)

Install over 1.6 (or 1.5) with `adb install -r` or by opening the APK. The
saved account is retained.

## Verification

Automated checks passed on the release build:

- `lintDebug`, `assembleDebug`, and `assembleRelease` clean
- APK Signature Scheme v2 verification with the expected release certificate
- version `1.6.1` / versionCode `8`, no `debuggable` in the packaged APK
- Sentry DSN embedded; auth token absent from the packaged APK
- release source bundle uploaded to Sentry and confirmed present on the
  server for the APK's embedded debug id (bundle ID
  `85ae500b-52a3-4901-86be-45db4d178898`, checked with
  `sentry-cli debug-files find`)

The origin-form/proxy-form behaviour was verified live on the affected network
(pre-auth absolute-form probe → splash page; post-auth → `204`), and the
equivalent fix was verified end-to-end on Linux. The on-device checks in
[ANDROID-TEST.md](ANDROID-TEST.md#86-16-auto-connect-and-sentry-pending-on-device)
remain pending until a phone is connected.

## Privacy

Unchanged from 1.6: credentials stay in `EncryptedSharedPreferences` and are
sent only to `122.252.242.93`. Sentry receives diagnostics, not credentials.
