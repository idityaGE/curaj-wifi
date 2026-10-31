# CURAJ Auto Login 1.6.2

This is a patch release. It fixes the repeating crash loop reported as
CURAJ-WIFI-2 when Android denies a background foreground-service start, and
turns every portal HTTP/socket request into a child span in Sentry traces.

## Highlights

### Foreground-service crash fixed (CURAJ-WIFI-2)

Android 12+ rejects `Service.startForeground()` when the system did not record
the start as foreground-eligible. A sticky resurrection after the OEM killed
the process, or a plain `startService()` from a notification action, leaves
`mAllowStartForeground` false. `onCreate()` called `startForeground()`
unconditionally, so the platform turned it into "Unable to create service" and
killed the whole app process. The sticky restart then reused the same false
flag, producing a repeating crash loop.

- `onCreate()` now enters the foreground through `enterForeground()`, which
  catches `ForegroundServiceStartNotAllowedException`. On denial it sets
  `foregroundDenied`, posts a plain (non-FGS) **Auto-login paused**
  notification, and calls `stopSelf()` instead of crashing.
  `NetworkMonitor.start()` runs after the guard, so nothing leaks on the denial
  path.
- `onStartCommand()` returns `START_NOT_STICKY` when the foreground start was
  denied, so the system stops rescheduling a service that can never start;
  normal runs still return `START_STICKY`.
- `postResumeNotification()` / `cancelResumeNotification()` provide the
  recovery path: a normal low-priority notification (always allowed, no FGS
  involved) whose tap opens `MainActivity`. It is cancelled when the service
  starts successfully, when the user pauses, and on a normal `onDestroy()`; it
  is not cancelled on the denial path.
- The **Login now** and **Pause** notification actions use
  `PendingIntent.getForegroundService` instead of `getService`, so a tap is
  delivered as a foreground-service start rather than a plain `startService()`
  that leaves the allowance false.
- `onDestroy()` cancels the stale resume hint only if the service actually ran.
- The denial check matches the API-31 exception class by name behind an SDK
  check, so the guard is also safe on Android 8-11 where that class does not
  exist.

### Per-request Sentry tracing

- Every portal HTTP/socket call now produces an `http.client` child span inside
  the enclosing transaction. This includes the raw-socket proxy-form online
  check, which does not use `HttpURLConnection`.
- Spans carry `http.request.method`, a query-string-stripped `http.url`,
  `http.response.status_code` (when a response arrives), and `server.address`
  for the raw socket. `portal.step` labels the request (`online_check`,
  `probe`, `splash`, `login_page`, `newlogin`, `discovery`, `logout`,
  `nas_logout`, `session_close`, `sessions_page`, `verify_*`, and the self-care
  login steps). Retries and fallback endpoints are separate spans.
- Transactions now end with `portal.result` = `ok`, `failed`, `session_limit`,
  `bind_error`, or `exception`, plus a matching span status. `portal.trigger`
  is `auto` or `manual`; the session-list transaction also reports
  `portal.sessions.count`.
- `beforeSendTransaction` removes request/user data and re-sanitizes span data
  as a defense-in-depth pass. Query strings, fragments, request bodies,
  cookies, Authorization headers, usernames, and passwords are not recorded.
- Behavior is unchanged when Sentry is disabled or a transaction is not
  sampled: the span helpers return `null` and the blocking portal calls run as
  before.

### Preserved

Everything from 1.6.1: proxy-form online detection, accurate logout
verification, Auto connect default-on, source context, credential/PII
scrubbing, update restart, live metrics, session sheet, explicit logout,
`EPERM` retry, and no mobile-data fallback for portal requests.

## Install

- Package: `in.ac.curaj.autologin`
- Version: `1.6.2` (versionCode `9`)
- APK: `CURAJ-AutoLogin-1.6.2.apk`
- SHA-256: `1e192fa13e36247454728fe23fd12f13a1cb84dbddb220144b73752e224c1832`
- Signature: APK Signature Scheme v2; same release certificate as 1.6
  (`55da02f424dbf6a4dfd9521b236c1bba4f598fe23db477afceea82fa894ac092`)

Install over 1.6.1 (or 1.5/1.6) with `adb install -r` or by opening the APK.
The saved account is retained. A debug-signed build must be uninstalled first.

## Verification

Automated checks passed on the release build:

- `lintDebug`, `assembleDebug`, and `assembleRelease` clean
- APK Signature Scheme v2 verification with the expected release certificate
- version `1.6.2` / versionCode `9`, no `debuggable` in the packaged APK
- Sentry DSN embedded; auth token absent from the packaged APK
- release source bundle uploaded to Sentry (bundle ID
  `1c00ee76-7523-45b1-86fa-14007a37ca86`)

The foreground-service denial path and the new child spans still need a
physical-device pass; no phone was connected at release time. The checklist is
in [ANDROID-TEST.md](ANDROID-TEST.md#86-16-auto-connect-and-sentry-pending-on-device).

## Privacy

Unchanged from 1.6: credentials stay in `EncryptedSharedPreferences` and are
sent only to `122.252.242.93`. Sentry receives diagnostics, not credentials.
