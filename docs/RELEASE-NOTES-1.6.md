# CURAJ Auto Login 1.6

This release makes automatic reconnection the default and adds production
crash, error, and performance monitoring.

## Highlights

- **Auto connect is on by default** for fresh installs. It starts after the
  first valid mobile number and password are saved. Turning it off is still
  persisted and respected.
- **Sentry monitoring**: crash/error events, portal transactions
  (`portal.login`, `portal.logout`, `portal.sessions.list`,
  `portal.sessions.close`), and readable Kotlin source context for release
  builds.
- **Credential-safe telemetry**: request bodies, cookies, authorization
  headers, URLs with query strings, usernames, and passwords are excluded from
  events and breadcrumbs. Sentry debug logging is disabled.
- **Update restart**: the background service is restarted after an app update
  (`MY_PACKAGE_REPLACED`) as well as after boot, provided Auto connect is on
  and both credentials are present.
- **Preserved 1.5 behavior**: live network metrics, session sheet, explicit
  logout, fresh-network `EPERM` retry, and no mobile-data fallback for portal
  requests.

## Install

- Package: `in.ac.curaj.autologin`
- Version: `1.6` (versionCode `7`)
- APK: `CURAJ-AutoLogin-1.6.apk`
- SHA-256: `eb647a9254cf54eaa9fdef234662a047c2743388ceb284ce2a516d8b24faca44`
- Signature: APK Signature Scheme v2; same release certificate as 1.5

Install over 1.5 with `adb install -r` or by opening the APK. The saved account
is retained. The release APK is not debuggable and does not include the
debug-only **Send test event** menu.

## Verification

Automated checks passed on the release candidate:

- clean `lintDebug`, `lintRelease`, `assembleDebug`, and `assembleRelease`
- unit-test tasks (the project currently has no unit-test sources)
- version, package, permissions, manifest receiver, and Sentry auto-init
  manifest entry inspected in the packaged APK
- APK Signature Scheme v2 verification with the expected release certificate
- Sentry auth token absent from the packaged APK
- release source bundle uploaded to Sentry (bundle ID
  `5a532e85-7ae6-46b3-a84c-24dc1821b779`)

The physical-device checks in
[ANDROID-TEST.md](ANDROID-TEST.md#86-16-auto-connect-and-sentry-pending-on-device)
were not rerun because no phone was connected at release time. Run them before
wide distribution.

## Privacy

Portal credentials stay in Android `EncryptedSharedPreferences` and are sent
only to `122.252.242.93`. Sentry receives diagnostics, not credentials.
