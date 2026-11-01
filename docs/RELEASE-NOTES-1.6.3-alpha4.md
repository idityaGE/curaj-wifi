# CURAJ Auto Login 1.6.3-alpha4

Alpha test build. This follows `1.6.3-alpha3`; `alpha3` was never installed on
the test phone, so Sentry has no `1.6.3-alpha3` release or events to compare
against.

## Changes and improvements

- **Parallel portal discovery:** `PortalDiscovery.probe()` now races all four
  `generate_204` IPs instead of trying them in order. The first HTTP response
  wins, so a blackholed endpoint costs one short timeout rather than four.
- **Short, split timeouts:** probe requests use a 1.5 s connect / 2.5 s read
  timeout; normal portal calls use 2 s / 5 s. The old 7 s connect/read timeouts
  are gone.
- **Bounded verification:** post-login verification, the in-progress wait,
  stale-session recovery, and logout verification poll the authoritative
  online check with hard deadlines (8 s login, 6 s logout) and a 300 ms
  interval. The first check runs immediately, so a fast gate no longer waits
  for fixed `1 s + 2 s + 4 s` sleeps.
- **OS fast path and short cache:** `isOnline()` uses
  `NET_CAPABILITY_VALIDATED` when the OS has an opinion and caches only positive
  results for 10 s. The cache is invalidated when the network or its validated
  state changes. Logout deliberately bypasses both and uses a fresh HTTP check.
- **Clearer traces:** `portal.online_check` and `portal.probe` parent spans
  group the four racing HTTP children, and all children are finished or
  cancelled before the parent closes.
- **Correct race handling:** a `204` from the discovery probe is reported as
  “already online” instead of “no captive portal”.
- **Crash hardening:** the foreground-service denial guard now walks the cause
  chain and accepts `ServiceStartNotAllowedException` as well as
  `ForegroundServiceStartNotAllowedException`. Boot keeps credential decryption
  off the main thread and logs a failed service start instead of failing
  silently.
- **Paused-service cleanup:** when auto-connect is off, the service removes its
  foreground notification before stopping.

## Install

- Package: `in.ac.curaj.autologin`
- Version: `1.6.3-alpha4` (versionCode `13`)
- APK: `CURAJ-AutoLogin-1.6.3-alpha4.apk`
- Size: 1,908,633 bytes (1.82 MiB)
- SHA-256: `9b1830b12d3ba15068e4cf42ec6734709c5b3a80be4daea3a474ad998533c2a1`
- Signature: APK Signature Scheme v2; same release certificate as 1.6.2 and
  `1.6.3-alpha3` (`55da02f424dbf6a4dfd9521b236c1bba4f598fe23db477afceea82fa894ac092`)

Install over `1.6.3-alpha3` with `adb install -r` or by opening the APK. The
saved account is retained. A debug-signed build must be uninstalled first.

## Verification

`assembleDebug`, `lintDebug`, `assembleRelease`, and `lintRelease` pass. Lint
reports 34 warnings — one fewer than the 35 in 1.6.2 and `1.6.3-alpha3` — with
no new warnings.

Sentry has no `1.6.3-alpha3` telemetry, so the latency improvement must be
confirmed on-device. After installing this build, check:

- `CURAJ-WIFI-2` stops appearing.
- `portal.login` p95 drops below 3 s on the same network (previously
  `11,051.876 ms` p95 / `1,326.8 ms` average over 190 events).
- The slowest traces show parallel `portal.online_check` / `portal.probe`
  children, not sequential 6–7 s timeouts.
- The manual checklist in [ANDROID-TEST.md](ANDROID-TEST.md) passes, especially
  the foreground-service denial path and a reboot.
