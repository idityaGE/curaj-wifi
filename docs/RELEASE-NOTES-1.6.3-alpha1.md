# CURAJ Auto Login 1.6.3-alpha1

This is an **alpha test build**, not a stable release. It exists to test the
release-packaging changes from the `reduce-apk-size-phase-1` branch on a real
phone before they are promoted to a final 1.6.3 release.

The app UI and login/logout/session behavior are unchanged. This build changes
only how the release APK is packaged.

## Size

| | 1.6.2 | 1.6.3-alpha1 |
|---|---:|---:|
| APK size | 9,702,748 bytes (9.25 MiB) | 1,903,229 bytes (1.82 MiB) |
| DEX, compressed | 4.61 MiB | 1.02 MiB |
| Sentry native libraries | 3.18 MiB | none |
| `resources.arsc` | 0.97 MiB | 0.44 MiB |
| Other `res/` files | 0.28 MiB | 0.24 MiB |

That is about an **80% reduction** with no UI or feature rewrite.

## Packaging changes

- Switched from `io.sentry:sentry-android` to
  `io.sentry:sentry-android-core`. The full artifact included Sentry NDK
  native-crash libraries for four CPU architectures and Session Replay. The app
  has no native code, and Replay was already disabled.
- Enabled R8 minification and resource shrinking for release builds.
- Limited packaged library resources to English. The app UI is already
  English-only.
- Excluded `DebugProbesKt.bin` and `kotlin-tooling-metadata.json`.
- Added R8 `-dontwarn` rules for Tink's compile-time annotations.

Sentry crash/error reporting, ANR reporting, performance traces, and the
per-request `http.client` child spans remain enabled. Credential storage still
uses `EncryptedSharedPreferences` backed by Android Keystore and Tink.

## Install

- Package: `in.ac.curaj.autologin`
- Version: `1.6.3-alpha1` (versionCode `10`)
- APK: `CURAJ-AutoLogin-1.6.3-alpha1.apk`
- SHA-256: `bfa78d0ef7862384dee4fdac7866e83ee951de8fd08779f27bc791c8e029c7bc`
- Signature: APK Signature Scheme v2; same release certificate as 1.6.2
  (`55da02f424dbf6a4dfd9521b236c1bba4f598fe23db477afceea82fa894ac092`)

Install over 1.6.2 with `adb install -r` or by opening the APK. The saved
account should be retained.

```bash
adb install -r CURAJ-AutoLogin-1.6.3-alpha1.apk
```

## Verified in the build

- `assembleRelease` clean
- release-signed with the expected certificate; APK Signature Scheme v2
- version `1.6.3-alpha1` / versionCode `10`
- no `debuggable` flag in the packaged APK
- no Sentry auth token in the APK
- no native libraries in the APK
- main layouts retained
- English/default resources only
- Sentry source bundle uploaded (bundle ID
  `af7d570f-b551-441d-9e57-3e5bdba74c53`)

## What to test on a phone

No phone or emulator was connected while this alpha was built, so these still
need a real-device pass:

1. Install over 1.6.2 and confirm the saved account is still present.
2. Log in, log out, and confirm the portal state changes correctly.
3. Open the sessions sheet, list sessions, and close one.
4. Confirm the foreground service, notification actions, and **Pause** still
   work.
5. Trigger a manual login and confirm Sentry receives a trace with
   `http.client` child spans.
6. If possible, trigger a handled failure and confirm source context and the
   ProGuard mapping resolve in Sentry.
7. Confirm notifications, Wi-Fi metrics, and boot/update restart behavior.

If the alpha passes, the final 1.6.3 should use a higher versionCode (for
example `11`) so it can update over this alpha cleanly.

## Privacy

Unchanged from 1.6.2. Credentials stay in `EncryptedSharedPreferences` and are
sent only to the portal host. Sentry receives diagnostics, not credentials,
query strings, cookies, headers, or request bodies.
