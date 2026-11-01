# CURAJ Auto Login 1.6.3-alpha3

Alpha test build. This is the first 1.6.3 alpha that includes the behavioral
changes; `alpha1` changed packaging only and `alpha2` was never published.

## Changes and improvements

- **Much smaller APK:** R8 minification, resource shrinking, English-only
  resources, and `sentry-android-core` reduce the APK from 9.7 MB to 1.9 MB.
- **Faster online detection:** all four `generate_204` probe IPs are checked in
  parallel and the first `204` wins, so one blackholed endpoint no longer costs
  four socket timeouts. Repeated checks and trace noise were also reduced.
- **Safer portal handling:** transport, discovery, and session code are split
  into focused classes; response bodies are bounded, URLs are redacted, and
  credentials remain pinned to the portal host.
- **More reliable attempts:** login/logout/session operations are serialized,
  the busy/cooldown lifecycle is race-safe, cancellation wins over late
  responses, and manual login is rate-limited.
- **Fail-closed logout:** logout is only considered verified when an endpoint
  actually answers with a non-`204` response; a network where every endpoint
  errors is reported as unverified.
- **Consistent UI/network state:** the Auto connect switch re-syncs from stored
  state, and network monitor callbacks can no longer republish a stopped
  network.
- **Crash and credential hardening:** corrupt encrypted preferences recover
  instead of crashing, the foreground notification is posted before reading
  credentials, and credential decryption runs off the main thread.
- **Cleaner state and tracing:** typed login outcomes replace string matching,
  stored state keys are R8-safe, and parallel online-check spans close cleanly.
- **Smaller refresh cost:** the session sheet refreshes every 30 seconds and
  network metrics use three probes instead of five.

## Install

- Package: `in.ac.curaj.autologin`
- Version: `1.6.3-alpha3` (versionCode `12`)
- APK: `CURAJ-AutoLogin-1.6.3-alpha3.apk`
- Size: 1,906,477 bytes (1.82 MiB)
- SHA-256: `b531ac78e1a3134a17229360a477616a876306072c634c1079339fa2c8982731`
- Signature: APK Signature Scheme v2; same release certificate as 1.6.2
  (`55da02f424dbf6a4dfd9521b236c1bba4f598fe23db477afceea82fa894ac092`)

Install over 1.6.2 with `adb install -r` or by opening the APK. The saved
account is retained. A debug-signed build must be uninstalled first.

## Verification

`assembleDebug`, `lintDebug`, `assembleRelease`, and `lintRelease` passed. The
APK reports version `1.6.3-alpha3` / versionCode `12`, is signed with the
expected release certificate, has no native libraries, is not debuggable, and
does not contain the Sentry auth token. Lint reports the same 35 pre-existing
warnings as 1.6.2 and no new warnings.

No phone was connected during this build, so the manual-device checklist in
[ANDROID-TEST.md](ANDROID-TEST.md) is still pending.
