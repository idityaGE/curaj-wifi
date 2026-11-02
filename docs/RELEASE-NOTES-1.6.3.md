# CURAJ Auto Login 1.6.3

This is the final 1.6.3 release. It is a performance and hardening release on
top of 1.6.2: it cuts the portal-login tail latency, shrinks the APK from
9.7 MB to about 1.9 MB, and hardens the foreground-service start path. The
pre-release builds `1.6.3-alpha1`, `1.6.3-alpha3`, and `1.6.3-alpha4` were
test builds; the alpha4 code is the basis for this production release.

## Highlights

### Portal-login latency

Sentry reported `portal.login` p95 at **11,051.876 ms** (average
**1,326.8 ms**) over 190 events. The slowest trace spent 7.3 s + 6.0 s + 6.0 s
in three sequential `generate_204` checks before reaching a responsive IP.

- Portal discovery and the authoritative online check now race all four fixed
  Google `generate_204` IPs in parallel; the first response wins, and a
  blackholed endpoint no longer costs a full timeout at each step.
- Probe requests use a 1.5 s connect / 2.5 s read timeout; normal portal calls
  use 2 s / 5 s. The old 6–7 s timeouts are gone.
- Losing sockets and HTTP connections are cancelled, so verification polls do
  not queue behind parked workers.
- Post-login, in-progress, stale-session, and logout verification use bounded
  polling (8 s for login, 6 s for logout, 300 ms interval) instead of fixed
  `1 s + 2 s + 4 s` sleeps and a `4 × 1 s` logout loop.
- `isOnline()` uses the OS `VALIDATED` / `CAPTIVE_PORTAL` hint and a 10 s
  positive-only cache, invalidated when the network or its validated state
  changes. Logout deliberately bypasses both and uses a fresh HTTP check.
- A `204` from discovery is treated as “already online” instead of “no captive
  portal”.

### Smaller APK

R8 minification, resource shrinking, English-only resources, and
`sentry-android-core` reduce the APK from 9.7 MB to about 1.9 MB.

### Safer portal handling

- Transport, discovery, and session code are split into focused classes.
- Response bodies are bounded, URLs are redacted, and credentials remain pinned
  to the portal host.
- Login, logout, and session operations are serialized; the busy/cooldown
  lifecycle is race-safe and cancellation wins over late responses.
- Typed outcomes replace string matching, and logout is fail-closed: it is
  considered verified only when an endpoint actually answers with a non-`204`
  response.
- The Auto connect switch re-syncs from stored state, and network callbacks
  cannot republish a stopped network.

### Crash and background hardening

- The foreground-service denial guard now walks the cause chain and accepts
  `ServiceStartNotAllowedException` as well as
  `ForegroundServiceStartNotAllowedException`, so OEM wrappers are handled.
- When auto-connect is off, the service removes its foreground notification
  before stopping.
- Boot keeps credential decryption off the main thread and logs a failed
  foreground-service start instead of swallowing it.
- Corrupt encrypted preferences recover instead of crashing.

### Tracing and refresh cost

- `portal.online_check` and `portal.probe` parent spans group the four racing
  HTTP children; all children are finished or cancelled before the parent
  closes.
- Every portal HTTP/socket call remains an `http.client` child span with a
  sanitized URL, response status, and `portal.step` label.
- The session sheet refreshes every 30 s and network metrics use three probes.

## Install

- Package: `in.ac.curaj.autologin`
- Version: `1.6.3` (versionCode `14`)
- APK: `CURAJ-AutoLogin-1.6.3.apk`
- Size: 1,908,617 bytes (1.82 MiB)
- SHA-256: `1a3df320d3594d15ae148175531546ef7c7f5c1ff914e6dbbe0463e7bbe54e5e`
- Signature: APK Signature Scheme v2; same release certificate as 1.6.2 and
  the 1.6.3 alphas
  (`55da02f424dbf6a4dfd9521b236c1bba4f598fe23db477afceea82fa894ac092`)

Install over 1.6.2 or any 1.6.3 alpha with `adb install -r` or by opening the
APK. The saved account is retained. A debug-signed build must be uninstalled
first.

## Verification

`assembleDebug`, `lintDebug`, `assembleRelease`, and `lintRelease` pass. Lint
reports 34 warnings — one fewer than 1.6.2 — and no new warnings. The APK
reports version `1.6.3` / versionCode `14`, is signed with the expected release
certificate, is not debuggable, has no native libraries, and does not contain
the Sentry auth token.

No phone was connected during the build, so the manual checklist in
[ANDROID-TEST.md](ANDROID-TEST.md) is still pending. After installing this
build, check:

- `portal.login` p95 drops below 3 s on the campus network (previously
  `11,051.876 ms` p95 / `1,326.8 ms` average over 190 events).
- The slowest traces show parallel `portal.online_check` / `portal.probe`
  children, not sequential 6–7 s timeouts.
- A denied background start shows the **Auto-login paused** notification
  instead of a crash, and `CURAJ-WIFI-2` stops appearing.
- Reboot, logout, session close, and credential retention still work.

## Preserved

Everything from 1.6.2: the foreground-service crash fix, per-request Sentry
spans, Auto connect default-on, source context, credential/PII scrubbing,
update restart, live metrics, session sheet, explicit logout, `EPERM` retry,
and no mobile-data fallback for portal requests. Everything from 1.6.1 remains:
proxy-form online detection, accurate logout verification, and the gateway
Squid compatibility fix.
