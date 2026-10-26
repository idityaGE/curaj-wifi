# Android app: background auto-login

The Android client is the same four-request flow as the Linux one, wrapped in a
foreground service that watches Wi-Fi and logs in automatically. For the full
architecture, runtime flow, and failure history, see
[ANDROID-ARCHITECTURE.md](ANDROID-ARCHITECTURE.md).

```
┌─────────────────────────────────────────────────────────────┐
│  CURAJ Auto Login                                            │
│                                                              │
│  MainActivity ──save──► EncryptedSharedPreferences           │
│       │                         │                            │
│       │ start                   │ read                       │
│       ▼                         ▼                            │
│  AutoLoginService ◄────── PortalClient ──► 122.252.242.93    │
│       │  ▲                                                   │
│       │  └── NetworkCallback (Wi-Fi available / captive)     │
│       └────── 60 s poll ─────────────────────────────────────│
│                                                              │
│  BootReceiver ──start──► AutoLoginService                    │
└─────────────────────────────────────────────────────────────┘
```

The service never sends credentials to a host other than
`122.252.242.93` (see “Credential pinning” below).

---

## 1. Permissions

### What the app declares

| permission | type | why | if the user refuses |
|---|---|---|---|
| `INTERNET` | normal, install-time | talk to the portal | app cannot work |
| `ACCESS_NETWORK_STATE` | normal, install-time | find the Wi-Fi `Network`, register the callback, bind requests to it | cannot choose the Wi-Fi interface |
| `ACCESS_WIFI_STATE` | normal, install-time | read link speed, frequency and signal for the dashboard | metrics show dashes |
| `FOREGROUND_SERVICE` | normal, install-time (API 28+) | keep the watcher alive | service start fails |
| `FOREGROUND_SERVICE_SPECIAL_USE` | normal, install-time (API 34+) | required for the `specialUse` foreground service type | install/start fails on Android 14+ |
| `POST_NOTIFICATIONS` | **runtime** (API 33+) | show the ongoing notification and its “Login now” / “Pause” actions | service still runs, but the notification is hidden from the shade and the actions are unavailable |
| `NEARBY_WIFI_DEVICES` | **runtime** (API 33+), `neverForLocation` | optional: show the SSID and signal strength | login still works; the dashboard shows “Campus Wi-Fi” and omits signal details |
| `ACCESS_FINE_LOCATION` | **runtime** (API 26–32 only) | optional: same dashboard details on older Android | login still works; the dashboard omits the SSID/signal |
| `ACCESS_COARSE_LOCATION` | **runtime** with FINE on API 26–32 | Android 12 requires both to be requested together | login still works; the dashboard omits the SSID/signal |
| `RECEIVE_BOOT_COMPLETED` | normal, install-time | restart the service after a reboot or app update | auto-login resumes only after the user opens the app |

### What the app deliberately does **not** ask for

| not requested | why not |
|---|---|
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | the app opens the battery-optimisation settings page from the three-dot menu instead; the user grants the exemption there. This avoids a Play-policy-restricted permission. |
| `ACCESS_BACKGROUND_LOCATION` | not needed; Wi-Fi details are optional and only read while the app is visible. |
| `QUERY_ALL_PACKAGES`, `READ_PHONE_STATE` | not used at all. |

**Short answer:** notifications on Android 13+ are the only runtime permission
needed for login. Nearby-Wi-Fi (or fine location on Android 12 and older) is
optional and only feeds the dashboard. The battery-optimisation exemption is a
user setting, not a manifest permission.

### Battery optimisation

Doze and OEM power managers can delay the network callback or kill the
service. Open the **three-dot menu → Battery optimisation**; pick the app and
choose **Don’t optimise** / **Unrestricted**.

This is optional in theory, but on Xiaomi/Oppo/Vivo/Realme/Samsung it is
usually the difference between “works” and “works until the screen turns off”.

---

## 2. Background execution model

| Android version | rule | how the app complies |
|---|---|---|
| 8.0+ (API 26) | background services are restricted | uses a foreground service |
| 9 (API 28) | FGS needs `FOREGROUND_SERVICE` | declared |
| 12 (API 31) | apps cannot start an FGS from the background, with exemptions | starts only from the Activity (foreground), `BOOT_COMPLETED` (exempt), a notification action (user-initiated), or a system restart (`START_STICKY`) |
| 13 (API 33) | `POST_NOTIFICATIONS` is runtime | requested on first launch; service still works if denied |
| 14 (API 34) | FGS must declare a type | `specialUse` + `FOREGROUND_SERVICE_SPECIAL_USE` + the subtype property |
| 15 (API 35) | `BOOT_COMPLETED` FGS restrictions for `dataSync`/`mediaProcessing` | `specialUse` is not one of the restricted types |
| Doze | network/CPU may be deferred | FGS + battery-optimisation exemption; the 60 s poll catches up |

The service is started in exactly five places:

1. `MainActivity` when the user turns **Auto connect** on with valid
   credentials.
2. `MainActivity` when the first valid credentials are saved while
   **Auto connect** is on. Fresh installs start with the switch on.
3. `MainActivity` on launch if it was already enabled and both credentials are
   present.
4. `BootReceiver` after boot / app update.
5. The “Login now” / “Pause” notification actions.

It is never started from a `NetworkCallback`. The callback only calls
`attempt()` on the already-running service, so Android 12’s
`ForegroundServiceStartNotAllowedException` cannot be triggered by a Wi-Fi
event.

### Triggers inside the service

```
NetworkCallback.onAvailable(wifi)                 → attempt
NetworkCallback.onCapabilitiesChanged(captive)    → attempt
NetworkCallback.onCapabilitiesChanged(!validated) → attempt
every 60 s                                        → attempt
notification “Login now”                          → attempt(force = true)
```

Guards:

* **20 s cooldown** per network, so a burst of callbacks does not hammer the
  portal.
* **one attempt at a time** (`AtomicBoolean`), so overlapping callbacks cannot
  race two logins.
* **backoff on failure**: 1, 2, 4, 8, then 15 minutes. A wrong password will
  not be retried every minute forever.
* **backoff resets** when the Wi-Fi network changes or a login succeeds;
  “Login now” bypasses the current backoff for one attempt.
* **`START_STICKY`** + `stopWithTask="false"`: swiping the app away does not
  stop the watcher; if the system kills it, it is recreated.

---

## 3. First-time setup

1. Install the APK (see §4).
2. Open **CURAJ Auto Login**. **Auto connect** starts on for fresh installs.
3. Enter the mobile number and portal password. Settings save automatically;
   there is no Save button. Once both fields are valid, the background service
   starts automatically.
4. Tap **Connect now** if you want a one-shot login or an immediate retry. It
   also updates the status card and **Last action**.
5. The main button changes to **Log out** once the portal session is connected.
6. Allow notifications when prompted (Android 13+).
7. Tap **Enable Wi-Fi details** in the Network card if you want the SSID and
   signal percentage. This is optional; login works without it.
8. Open the three-dot menu → **Battery optimisation**, then set the app to
   **Unrestricted**.
9. On Xiaomi/Oppo/Vivo/Realme/Samsung, also enable **Autostart** and allow
   background activity (see §6).
10. Connect to `CURAJ CAMPUS CONNECT`. The notification should change to
    **Connected - internet is working** within a few seconds.

The app does not need to be open. Once **Auto connect** is on, it starts on
boot.

### Notification actions

* **Login now** — force an attempt, bypassing the cooldown/backoff.
* **Pause** — sets **Auto connect** off, stops the service and removes the
  notification. Re-enable it in the app.
* Tapping the notification opens the app, which shows the last result and its
  timestamp.

The notification is state-only. It shows **Connected**, **Session limit
reached**, **Sign-in failed**, or **Disconnected from campus Wi-Fi**; it does
not post “signing in” or “waiting” messages, and identical states are not
repeated. A session-limit notification is posted separately at high priority
with a **Manage sessions** action; the foreground notification stays silent.

### Log out

The main button becomes **Log out** when the portal session is connected. It
asks for confirmation, then:

1. turns Auto connect off and stops the service (otherwise the next poll would
   log straight back in);
2. opens a fresh portal session via the NAS splash;
3. binds that `JSESSIONID` with `newlogin.do`;
4. calls `logout.do`, then the NAS logout as a fallback;
5. verifies that the probe no longer returns `204`.

A bare `/logout` would be a no-op; the cookie binding step is what makes the
portal actually tear down the session. The bind may briefly look like a login
to the portal — that is intentional, and the immediate `logout.do` removes it.
The three-dot menu also has a **Log out** item for the same action.

### Sessions

The account allows two simultaneous sessions. The **Sessions** button (and the
**Manage sessions** notification action) opens the portal’s
`currentSessions.do` table and lets you close one chosen session:

1. obtain a splash-context `JSESSIONID` (the cookie from the portal URL, not a
   direct `login.jsp` cookie);
2. log in with `type=1` (self-care, no third slot);
3. parse `callClose(<sesno>)` from the table;
4. `POST logout.do?from=curses&sesno=<id>` with an empty body and the context
   cookie;
5. re-list and report success only if the id is gone.

A direct self-care cookie produces `302 http://1.254.254.254?N=...` and does
not close the session. The app therefore never treats the `302` as success.
See [SESSION-MANAGEMENT.md](SESSION-MANAGEMENT.md) for the captured requests
and the live verification.

---

## 4. Build and install

The project uses AGP 8.7.3, Kotlin 2.0.21, `compileSdk 35`, `minSdk 26`, and
Java 17. The official Gradle 8.14.6 wrapper is included:

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you build from the command line you need JDK 17 and the Android SDK with
platform 35 and build-tools 35. If you do not have either locally, use the
included GitHub Actions workflow — see
[BUILD-ONLINE.md](BUILD-ONLINE.md) for GitHub Actions, Codemagic, Bitrise,
Appcircle, GitLab CI and Codespaces, or [LOCAL-BUILD.md](LOCAL-BUILD.md) for
the exact local JDK 17 + command-line SDK setup.

---

## 5. How the login is detected and performed

1. `ConnectivityManager` gives the service a `Network` that has
   `TRANSPORT_WIFI`.
2. `PortalClient` opens every connection with
   `network.openConnection(url)`. That pins the request to Wi-Fi even when
   mobile data is the default route — the Android equivalent of Linux
   `SO_BINDTODEVICE`.
3. Probe one of the fixed Google `generate_204` IP addresses
   (`http://142.251.150.120/generate_204`, then fallbacks).
   * `204` → already online; done.
   * anything else → parse the portal URL from `Location`, a meta refresh, a
     JS redirect, or a `/userportal/?...` URL in the body.
   IPs are used instead of the hostname because the captive Wi-Fi returns
   NXDOMAIN for `connectivitycheck.gstatic.com` before login.
4. `GET` the portal URL, keep `JSESSIONID`.
5. `POST login.do` (browser parity).
6. `POST newlogin.do` with
   `username`, `password`, `phone=0`, `type=2`, `jsonresponse=1`.
7. On `redirect_to_nas` or “Session already running”: call `logout.do` with the
   cookie, wait 1.5 s, retry once.
8. On “Maximum session allowed reached”: report the limit, post **Manage
   sessions**, and wait for the user to close one. Do not kick a session
   automatically.
9. On “Authentication already in progress for IP …”: do **not** send another
   `newlogin.do`. Poll the probe for up to 6 s; if it is still pending, return
   a transient result and retry after 3 s. This avoids racing Android’s own
   captive-portal login.
10. Poll `generate_204` for up to 9 s; success means the NAS has opened the
    gate, not merely that the portal said “success”.

---

## 6. OEM survival guide

Android’s own battery optimiser is only half the problem; several OEMs add a
second layer.

| OEM | what to enable |
|---|---|
| Xiaomi / Redmi / POCO | Settings → Apps → Manage apps → CURAJ → **Autostart** on; Battery saver → **No restrictions**; lock the app in Recents |
| Samsung | Battery → Background usage limits → remove from **Sleeping apps**; Battery → **Unrestricted** |
| OnePlus / Oppo / Realme | Battery → **Don’t optimise**; App launch → allow **Auto-launch** and **Secondary launch** |
| Vivo / iQOO | Background power consumption management → allow; Autostart → allow |
| Pixel / Nothing / stock | Battery → **Unrestricted** (usually enough) |

If the notification disappears after a while and never comes back, the OEM
killed the process; the autostart/battery settings above are the fix.

---

## 7. Credential pinning (important)

`PortalClient` accepts a portal URL only if its host is in:

```kotlin
private val PORTAL_HOSTS = setOf("122.252.242.93")
```

Without that check, a hostile Wi-Fi could serve a fake
`/userportal/?...` page; the app would follow it and POST the campus password
to the attacker. The check makes the app fail closed with
`refusing to send credentials to <host>`.

If the university moves the portal, update **both**:

* `PORTAL_HOSTS` in `PortalClient.kt`
* the `<domain>` entries in
  `app/src/main/res/xml/network_security_config.xml`

The Linux client has the same guard; its allowlist is the `portal_hosts` key in
`~/.config/curaj-wifi/config.json` (default `["122.252.242.93"]`).

### Other security properties

* Credentials are stored in `EncryptedSharedPreferences` (AES-256-GCM values,
  AES-256-SIV keys) backed by the Android Keystore, and `allowBackup="false"`.
* Cleartext HTTP is disabled by default in the network security config and
  allowed only for the portal, the NAS splash, the gateway and the
  connectivity-check host.
* The portal itself sends the password over plain HTTP. This is a portal
  limitation, not something the app can fix; use a password unique to the
  campus account.

### MAC randomisation

If Android uses a random MAC for this SSID, the portal sees a new device on
every connection. Login still works, but “Session already running” and
per-device `sc` behaviour may differ. If you see repeated stale-session
recovery, set **Privacy → Use device MAC** for `CURAJ CAMPUS CONNECT`.

---

## 8. Testing

Watch the service:

```bash
adb logcat -s AutoLoginService PortalClient
```

Force an immediate attempt (bypasses cooldown/backoff):

```bash
adb shell am start-foreground-service \
  -n in.ac.curaj.autologin/.AutoLoginService \
  -a in.ac.curaj.autologin.LOGIN_NOW
```

Toggle Wi-Fi:

```bash
adb shell svc wifi disable
sleep 3
adb shell svc wifi enable
```

Check the battery whitelist:

```bash
adb shell dumpsys deviceidle whitelist | grep curaj
```

Expected sequence after connecting:

```
AutoLoginService: login ok=true: logged in
PortalClient: portal: http://122.252.242.93/userportal/?...&sc=...
```

If you see `refusing to send credentials to ...`, the portal host changed or
you are on a different captive portal — that is the pinning working.

---

## 9. Limits

* **Portal IP changes** → update `PORTAL_HOSTS` and the network security
  config. The host check is deliberate; do not replace it with “any
  `/userportal` URL”.
* **Wrong password** → the service backs off up to 15 minutes, but it will
  keep trying. Fix the password in the app; repeated failures can still
  trigger an account lockout on the portal side.
* **Android’s own “Sign in to Wi-Fi” notification** may appear briefly. Once
  the app validates the network, the system notification clears. There can be
  a short race where both are visible; if the system starts the authentication
  first, the app detects `Authentication already in progress`, waits for the
  gate to open, and does not send a duplicate login.
* **Aggressive OEMs** can still kill the service; see §6.
* **Force-stop** (Settings → Apps → CURAJ → Force stop) puts the app in a
  stopped state; Android will not deliver `BOOT_COMPLETED` or restart the
  service until you open the app again.
* **Portal HTML changes** → the parser (`findPortal`) handles `Location`, meta
  refresh, JS redirect and raw `/userportal/?...` URLs. A JS-only login page
  that never puts the URL in the HTML would need a WebView fallback.
* **Logout depends on the portal’s cookie binding.** If the university changes
  `logout.do` semantics, the app’s logout verification will fail closed: it
  keeps auto-login off and records `Logout failed: …` rather than claiming a
  logout that did not happen.
* **Session close depends on the splash context.** A direct self-care cookie
  can list sessions but gets `302` and does not close one. If the portal ever
  stops issuing the context cookie from the splash URL, the session chooser
  will report `portal did not close session …` and leave the session alone.

---

## 10. Why Wi-Fi details are optional, not a gate

The app still identifies the campus by the portal host, not by the Wi-Fi name.
The SSID is shown for reassurance and the signal/link numbers come from the
same `WifiInfo`. It is deliberately **not** a connection gate:

* Reading `WifiInfo.getSSID()` is location-gated on Android 8.1–12. The app
  declares `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` with
  `maxSdkVersion="32"`, requests them together on Android 12, and asks only
  when the user taps **Enable Wi-Fi details**.
* On Android 13+ the app uses `NEARBY_WIFI_DEVICES` with `neverForLocation`,
  so it does not request background location.
* Login must work on a phone where the user refuses the prompt. The portal
  host check remains the security boundary: another network with a captive
  portal will not serve `122.252.242.93`, so the app does nothing, and
  credential pinning prevents a fake portal from harvesting the password.

If the user refuses, the card shows **Campus Wi-Fi** and the signal metric
falls back to a dash; the rest of the dashboard (IP, band, latency, loss,
throughput, link speed) still works.

---

## 11. File map

```
android/app/src/main/java/in/ac/curaj/autologin/
  MainActivity.kt      Material 3 dashboard, auto-save, metrics, menu, logout
  AppState.kt          live state shared with the service
  NetworkMonitor.kt    fresh Wi-Fi Network, SSID/IP/signal/link details
  NetworkMetrics.kt    five HTTP probes + TrafficStats throughput deltas
  SessionsSheet.kt     session bottom sheet with spinner/auto-refresh
  AutoLoginService.kt  FGS, callback, poll, backoff, notification actions
  PortalClient.kt      HTTP flow, host pinning, duplicate-auth handling, login/logout
  Prefs.kt             Keystore-backed credentials + last result
  BootReceiver.kt      BOOT_COMPLETED / MY_PACKAGE_REPLACED

android/app/src/main/
  AndroidManifest.xml
  res/xml/network_security_config.xml
  res/layout/activity_main.xml
```

See also: [ANDROID-ARCHITECTURE.md](ANDROID-ARCHITECTURE.md),
[REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md),
[ENDPOINTS.md](ENDPOINTS.md), [EVIDENCE.md](EVIDENCE.md).
