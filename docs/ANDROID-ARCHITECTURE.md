# Android app: architecture, runtime, and engineering notes

This is the engineering document for the Android client: what it is made of,
how a login actually happens, why the service behaves the way it does, and the
problems found while taking it from “compiles” to “works on the real campus
network”.

Companion documents:

* [REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md) — how the portal was
  reverse-engineered.
* [ENDPOINTS.md](ENDPOINTS.md) — request/response reference card.
* [ANDROID.md](ANDROID.md) — permissions, setup, OEM survival, operational
  notes.
* [ANDROID-TEST.md](ANDROID-TEST.md) — live test log on the OnePlus device.
* [LOCAL-BUILD.md](LOCAL-BUILD.md) — build the APK without Android Studio.

---

## 1. Snapshot

| item | value |
|---|---|
| application id | `in.ac.curaj.autologin` |
| version | **1.5** (versionCode **6**) |
| language | Kotlin |
| minimum SDK | 26 (Android 8.0) |
| target / compile SDK | 35 (Android 15) |
| build | Gradle 8.9 wrapper, AGP 8.7.3, Kotlin 2.0.21, JDK 17 |
| debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| production APK | `dist/CURAJ-AutoLogin-1.5.apk` (release-signed) |
| production SHA-256 | `f13c37387bcca49602e066c6c7855907b25c0284234a8b64194967c5120914a9` |
| tested device | OnePlus CPH2793, Android 16 |
| campus Wi-Fi | `CURAJ CAMPUS CONNECT`, `wlan0`, `10.50.13.252/20` |
| portal | `http://122.252.242.93/userportal` |

The app has one job: **when the campus Wi-Fi is behind the captive portal,
restore the session without the user opening a browser**.

---

## 2. Goals and constraints

### Goals

1. Log in automatically whenever `CURAJ CAMPUS CONNECT` is connected.
2. Keep working with the screen off, the app swiped away, or after a reboot.
3. Never send the campus password to a host other than the real portal.
4. Do not add a delay to the normal path. The first login request goes out as
   soon as Wi-Fi appears.
5. Coexist with Android’s own captive-portal login screen, which may be trying
   to authenticate the same IP at the same time.
6. Make the notification quiet and truthful: connected, failed, or
   disconnected — not a stream of “signing in” / “waiting” messages.
7. Provide a real logout that turns auto-login off, so the service does not
   immediately sign back in.
8. When the account’s two-session limit is reached, list the active sessions
   and let the user close one; never kick another device automatically.
9. Show a clear, live dashboard: connection state, SSID (when permitted),
   signal percentage, IP, band, link speed, HTTP latency/loss, and app
   throughput. Refresh it automatically without a manual Save button.

### Non-goals

* Replacing the portal’s web UI or showing the portal inside a WebView.
* Gating login on the SSID. The dashboard reads it when the optional
  nearby-Wi-Fi permission is granted, but the portal host remains the
  security boundary.
* VPN, traffic forwarding, or bypassing the portal’s authentication.
* Google Play publishing. The production path is a private, release-signed APK
  distributed directly; see [RELEASE-BUILD.md](RELEASE-BUILD.md).

---

## 3. High-level architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│ Android app process: in.ac.curaj.autologin                           │
│                                                                      │
│  MainActivity                                                        │
│   ├─ Material 3 dashboard + auto-saving credentials + logout         │
│   ├─ reads AppState / NetworkMonitor / metrics                       │
│   └─ starts or stops the foreground service                          │
│             │                                                        │
│             ▼                                                        │
│  AutoLoginService  (specialUse foreground service)                   │
│   ├─ NetworkMonitor callback (fresh Wi-Fi Network)                   │
│   ├─ 60 s poll                                                       │
│   ├─ one-at-a-time + 20 s cooldown + failure backoff                │
│   ├─ notification state machine                                      │
│   └─ calls PortalClient on Dispatchers.IO                            │
│             │                                                        │
│             ▼                                                        │
│  PortalClient  (stateless HTTP flow)                                 │
│   ├─ probe fixed Google IPs through the Wi-Fi Network                │
│   ├─ discover portal URL from Location / meta / JS / body           │
│   ├─ host pinning: only 122.252.242.93 may receive credentials      │
│   ├─ login.do + newlogin.do + verify 204                             │
│   └─ logout.do with a freshly bound JSESSIONID                       │
│                                                                      │
│  Prefs  (EncryptedSharedPreferences + Android Keystore)              │
│  BootReceiver  (BOOT_COMPLETED / MY_PACKAGE_REPLACED)                │
└──────────────────────────────────────────────────────────────────────┘
              │                                    ▲
              │ HTTP (cleartext, pinned)           │
              ▼                                    │
   10.50.0.1 NAS / Squid               122.252.242.93 portal
   (intercepts HTTP)                  (Inventum / RailWire)
```

### Component responsibilities

| component | responsibility | deliberately not responsible for |
|---|---|---|
| `MainActivity` | Material 3 dashboard, auto-saving credentials, one-shot connect/logout, live Wi-Fi and latency/throughput metrics, session sheet, battery/Wi-Fi settings menu | protocol details, background scheduling |
| `AppState` | in-process live state shared by the activity and service | persistence |
| `NetworkMonitor` | one fresh Wi-Fi `Network` for request binding, plus optional SSID/IP/signal/link details | portal logic |
| `NetworkMetrics` | five HTTP probes for latency/loss, `TrafficStats` deltas for app throughput | ICMP (not available to normal apps) |
| `SessionsSheet` | load/close/verify sessions with per-row spinners and 15 s auto-refresh | choosing sessions for the user |
| `AutoLoginService` | stay alive, receive Wi-Fi callbacks, poll, serialize attempts, backoff, notifications | parsing the portal HTML |
| `PortalClient` | probe, portal discovery, host pinning, login/logout HTTP, success verification | Android lifecycle, UI, notifications |
| `Prefs` | encrypted credentials, enabled flag, last result/time | network or portal logic |
| `BootReceiver` | restart the service after boot/app update when enabled and configured | retrying failed logins |

The split is intentional: `PortalClient` has no Android UI or service state, so
the same flow can be exercised from the app, logcat, or a one-shot test without
starting the background watcher.

---

## 4. Runtime lifecycle

### 4.1 Where the service is started

The service is started from exactly four places:

1. `MainActivity` when the user turns **Auto connect** on.
2. `MainActivity.onCreate()` when the app opens and auto-login was already
   enabled.
3. `BootReceiver` after `BOOT_COMPLETED` or `MY_PACKAGE_REPLACED`.
4. The notification’s **Login now** action (a user-initiated service start).

It is **not** started from `NetworkCallback`. Android 12+ blocks foreground
service starts from the background; receiving a Wi-Fi callback is not a valid
exemption. The callback only calls `attempt()` inside the already-running
service. This is the reason the app does not use a “start service when Wi-Fi
appears” design.

### 4.2 Service startup

```
onCreate()
  ├─ create low-importance notification channel
  ├─ choose initial notification text
  ├─ startForeground(41, notification)
  ├─ register Wi-Fi NetworkCallback
  └─ launch 60 s poll loop
onStartCommand()
  ├─ ACTION_PAUSE  -> disable, remove notification, stopSelf
  ├─ ACTION_RESUME -> enable
  ├─ if disabled    -> stopSelf
  ├─ ACTION_LOGIN_NOW -> force = true
  └─ attempt(active Wi-Fi network)
```

`START_STICKY` means Android may recreate the service after a low-memory kill.
`android:stopWithTask="false"` means swiping the task away does not stop the
watcher. An explicit **Pause** action or turning the switch off does stop it.

### 4.3 Normal connection sequence

```
Wi-Fi connects
   │
   ├─ onAvailable(network) ───────────────┐
   ├─ onCapabilitiesChanged(captive) ─────┤
   └─ 60 s poll ───────────────────────────┤
                                          ▼
                                   attempt(network)
                                          │
                          cooldown? busy? backoff? ── yes ──► return
                                          │ no
                                          ▼
                             PortalClient.login(...)
                                          │
              probe fixed IP through the Wi-Fi Network
                                          │
                      ┌───────────────────┴───────────────────┐
                      │ 204                               non-204
                      ▼                                       ▼
              “already online”                     discover portal URL
                      │                                       │
                      │                              GET portal, JSESSIONID
                      │                                       │
                      │                              POST login.do (browser parity)
                      │                                       │
                      │                              POST newlogin.do
                      │                                       │
                      │                              verify probe == 204
                      └───────────────────┬───────────────────┘
                                          ▼
                               update notification to
                               “Connected - internet is working”
```

The normal path does **not** wait for the system captive-portal activity, does
not wait for a “captive portal” capability to settle, and does not sleep before
the first request. Verification only waits after the portal has already
accepted the credentials.

### 4.4 Wi-Fi loss and reconnect

`onLost(network)` changes the notification to **Disconnected from campus
Wi-Fi** when the lost network is the one the service last attempted. The 60 s
poll also notices when no Wi-Fi network exists and sets the same state. The
state is deduplicated, so repeated polls do not repost the notification.

When Wi-Fi returns, `onAvailable` triggers an attempt. If the NAS still has the
old session, the probe returns `204` and the app reports connected without
sending credentials again. If the session expired, the normal login flow runs.

### 4.5 Boot / app update

`BootReceiver` checks two conditions before starting the service:

* `Prefs.enabled` is true; and
* a username is saved.

If either is missing it does nothing. This avoids a foreground service with no
credentials and avoids restarting after the user explicitly paused auto-login.

### 4.6 Logout sequence

Logout is intentionally **not** just a portal URL. A bare
`GET /userportal/logout.do` is a no-op because the NAS only tears down a
session whose `JSESSIONID` was bound by `newlogin.do`. The app performs the
same sequence as the Linux client:

```
user taps “Log out and stop auto-login”
        │
        ├─ Prefs.enabled = false
        ├─ switch off
        └─ stopService(AutoLoginService)
        │
        ▼
PortalClient.logout(network, user, pass)
        │
        ├─ discover portal via http://1.254.254.254/ (works while online)
        ├─ GET portal URL                  -> fresh JSESSIONID
        ├─ POST newlogin.do                -> bind cookie to live NAS session
        ├─ GET logout.do with that cookie  -> tear down portal session
        ├─ GET http://1.254.254.254/logout -> NAS fallback
        └─ probe 4× at 1 s intervals
               204      -> failure: still online
               non-204  -> success: logged out
        │
        ▼
UI records “Logged out”
```

The service is stopped and the enabled flag is cleared **before** the logout
HTTP call. Without that, the next poll would log straight back in. The
`newlogin.do` bind may briefly look like a login to the portal; that is
intentional, and the immediate `logout.do` removes it. If logout cannot be
verified, the app still leaves auto-login off and records `Logout failed: …`;
the user can turn **Auto connect** back on.

---

## 5. The portal protocol as implemented

### 5.1 Probe without DNS

The probe uses four fixed Google `generate_204` IP addresses, in order:

```
http://142.251.150.120/generate_204
http://142.250.183.36/generate_204
http://142.250.72.196/generate_204
http://142.251.32.14/generate_204
```

Why IP literals: the captive Wi-Fi DNS returns `NXDOMAIN` for
`connectivitycheck.gstatic.com` **before** login, even though the HTTP request
is intercepted and answered by the portal. Resolving the name through the
Wi-Fi `Network` therefore failed before the app ever reached the portal.

The request is made with `network.openConnection(URL)`, which pins it to the
Wi-Fi `Network`. Mobile data cannot win the route.

### 5.2 Portal discovery

`findPortal()` extracts candidates from:

1. the `Location` header;
2. a meta refresh (`URL=...`);
3. JavaScript redirects (`location = ...`, `location.href = ...`);
4. any raw `http(s)://.../userportal/?...` URL in the body.

Relative URLs are resolved against the response URL. A `1.254.254.254` splash
is followed once to obtain the real portal URL. The selected URL must contain
`/userportal`.

### 5.3 Session and login

```
GET  http://122.252.242.93/userportal/?...&sc=...
     -> Set-Cookie: JSESSIONID=...

POST http://122.252.242.93/userportal/login.do?requesturi=...
     Cookie: JSESSIONID=...
     (browser-parity hop; failures here are ignored)

POST http://122.252.242.93/userportal/newlogin.do
     Cookie: JSESSIONID=...
     Content-Type: application/x-www-form-urlencoded

     username=<mobile>&password=<password>&phone=0&type=2&jsonresponse=1
```

Success is `errorKey == "success"` or `errorMessage == "success_net"`. The app
then probes until it gets `204`, so a portal-side “success” that did not
actually open the NAS gate is not reported as connected.

The `sc` token is parsed from the portal URL but not generated; it is supplied
by the NAS redirect and binds the portal session to the device identity.

### 5.4 Host pinning

`PORTAL_HOSTS = {"122.252.242.93"}`. Before any credential POST, the app
checks the portal URL’s host. A hostile Wi-Fi serving a fake `/userportal` page
gets:

```
refusing to send credentials to <host>
```

Cleartext HTTP is disabled by default and allowed only for the portal, NAS,
gateway, and the four probe IPs in
`res/xml/network_security_config.xml`.

---

## 6. Concurrency, retries, and timing

### 6.1 Serialization

* `PortalClient.login()` is `@Synchronized`, so two callers cannot interleave
  the probe/session/login steps.
* `AutoLoginService` uses an `AtomicBoolean` (`busy`) so overlapping
  `onAvailable`, `onCapabilitiesChanged`, and poll events produce one attempt.
* `attempt()` uses a 20-second cooldown per network to absorb callback bursts.
* The notification text is guarded by a lock and compared before posting.

### 6.2 Failure backoff

Hard failures use exponential backoff:

| consecutive failure | next attempt |
|---|---|
| 1 | 1 minute |
| 2 | 2 minutes |
| 3 | 4 minutes |
| 4 | 8 minutes |
| 5+ | 15 minutes |

The backoff resets on success or on a new Wi-Fi network. **Login now** bypasses
the current backoff for one attempt (and a success resets it). This prevents a
wrong password from hammering the portal.

### 6.3 “Authentication already in progress” is not a hard failure

If the portal says another authentication is in flight, the app does **not**
send a second `newlogin.do`. It polls the probe once per second for up to six
seconds. If the gate opens, the attempt is reported as a normal login. If it
is still pending, `PortalClient` returns a transient result with
`retryMs = 3000`; the service schedules a short retry and does not post a
failure notification. The transient loop is bounded to 10 retries, after which
it becomes a normal failure with backoff.

This matters because Android’s own captive-portal activity can race the app for
the same IP. Sending another login while the first is still being processed
produced the old, confusing failure message and a one-minute delay.

### 6.4 Stale session recovery

The portal can answer:

```
{"errorMessage":"Session already running","errorKey":"failureCase"}
```

or

```
{"errorKey":"redirect_to_nas"}
```

In that case the app:

1. calls `logout.do` with the current `JSESSIONID`;
2. calls the NAS logout (`http://1.254.254.254/logout`);
3. waits 1.5 s;
4. checks for `204`; if not, retries the login once.

This clears the half-session without an unbounded retry loop.

### 6.5 Two-session limit and session management

The account allows two simultaneous internet sessions. When both are in use,
`newlogin.do` returns:

```json
{"errorKey":"failureCase","errorMessage":"Maximum session allowed reached. your subscription allow a maximum of 2 sessiosn"}
```

`PortalClient.login()` surfaces this as `Result(sessionLimit = true)`. The
service records `Session limit reached`, updates the foreground notification,
and posts one separate high-priority notification with a **Manage sessions**
action. The foreground notification remains low-priority and silent.

The **Sessions** button (and the notification action) runs:

```text
findPortalForContext()
  -> GET splash URL                    (context JSESSIONID)
  -> POST newlogin.do  type=1          (self-care, no third slot)
  -> GET currentSessions.do            (parse callClose ids)
  -> POST logout.do?from=curses&sesno  (context cookie, empty body)
  -> GET currentSessions.do            (verify the id is gone)
```

The context cookie is essential. A cookie from a direct `login.jsp` load can
list sessions, but `logout.do` returns `302 http://1.254.254.254?N=...` and the
session survives. Only the splash-context cookie gets the real `200` and
“Your Session Has Been Stopped” page. The app therefore verifies against a
second list and reports failure rather than trusting the `302`.

The dialog shows each session’s start time, duration, total bytes, name, and
account id. It asks for confirmation, closes only the selected id, then starts
a normal login attempt if auto-login is enabled. It never selects a session on
its own.

---

## 7. Notification state machine

The foreground notification is a **state display**, not a progress log.

### States

| state | text | when |
|---|---|---|
| baseline | `Auto-login is on` | service is running and is not in a terminal state |
| no credentials | `Set your mobile number and password in the app` | service started without saved credentials |
| connected | `Connected - internet is working` | login verified with `204` |
| session limit | `Session limit reached - log out one session` | portal reports two active sessions |
| failed | `Sign-in failed: <reason>` | a hard failure, after transient retries are exhausted |
| disconnected | `Disconnected from campus Wi-Fi` | Wi-Fi lost or no Wi-Fi at a poll |

The baseline is required by Android for a foreground service. It is not an
intermediate “waiting” message. The app does **not** post:

* “Signing in…”
* “Waiting for Wi-Fi…”
* “Watching the portal…”
* repeated identical updates.

`update()` compares the new text with `lastNotificationText`; identical text is
dropped. The notification is ongoing, silent, low-priority, `onlyAlertOnce`,
and uses `BigTextStyle` so a long failure reason is readable when expanded.

### Actions

* **Login now** — one forced attempt, bypassing cooldown/backoff.
* **Manage sessions** — appears in the session-limit notification and the
  foreground notification while the limit is active; opens the session chooser.
* **Pause** — sets the enabled flag off, removes the notification, stops the
  service. Use the app to re-enable.
* Tapping the notification opens `MainActivity`, which shows the last result
  and timestamp.

---

## 8. Storage and security

### Encrypted preferences

`Prefs` uses `EncryptedSharedPreferences`:

* AES-256-GCM for values;
* AES-256-SIV for keys;
* master key in the Android Keystore;
* `android:allowBackup="false"` so the credential file is not cloud-backed.

Stored values: username, password, enabled flag, last result, last result
timestamp.

### Permissions

Declared permissions are deliberately minimal:

```
INTERNET
ACCESS_NETWORK_STATE
ACCESS_WIFI_STATE
FOREGROUND_SERVICE
FOREGROUND_SERVICE_SPECIAL_USE
POST_NOTIFICATIONS
RECEIVE_BOOT_COMPLETED
NEARBY_WIFI_DEVICES          (runtime, API 33+, neverForLocation)
ACCESS_FINE_LOCATION         (runtime, API 26–32 only)
```

Notifications are the only runtime permission needed for login. Nearby Wi-Fi
(Android 13+) or fine location (Android 12 and older) is requested only when
the user taps **Enable Wi-Fi details**, and is used only for the dashboard’s
SSID/signal display. The app does not request background location, phone
state, or battery-optimisation permission. The menu opens the battery settings
page instead of requesting `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

### The unavoidable plaintext caveat

`newlogin.do` is HTTP. Android can enforce a cleartext allowlist and pin the
host, but it cannot make the portal encrypt the password. This is a portal
limitation. Use a password that is not reused elsewhere.

---

## 9. Problems encountered and fixes

These are the issues that actually appeared while building and testing the
app, not hypothetical concerns.

### 9.1 DNS failed before login

**Symptom**

```
Sign-in failed: probe failed: Unable to resolve host
"connectivitycheck.gstatic.com": No address associated with hostname
```

**Cause**

`network.openConnection()` pins DNS resolution to the captive Wi-Fi too. The
campus DNS returns NXDOMAIN until the portal login succeeds. A shell `curl`
appeared to work only because it resolved the name through mobile data first.

**Fix**

Probe four fixed Google IPs instead of the hostname, and allow those IPs in the
cleartext network-security config. No DNS is involved.

**Lesson**

“Internet is up” and “DNS through this interface works” are different
questions on a captive network.

### 9.2 “Authentication already in progress for IP …”

**Symptom**

```
Sign-in failed: rejected: Authentication already in progress for IP <ip>
```

The app then waited a full backoff interval and looked slow.

**Cause**

The portal had an authentication in flight for the same IP/MAC. Android’s
system captive-portal activity can be the other actor, or a previous request
may still be settling. Sending another `newlogin.do` is both unnecessary and
counterproductive.

**Fix**

* Detect “already in progress” / “authentication already” case-insensitively.
* Do not POST another login.
* Poll the probe for up to six seconds; return success as soon as it is `204`.
* If still pending, return a transient result with a 3-second retry.
* Bound transient retries to 10 and suppress failure notifications during
  them.

**Result**

The duplicate-authentication failure no longer produces a false failure, and
the successful login is reported as soon as the gate opens.

### 9.3 “Session already running” / stale half-session

**Cause**

The NAS considered the device online, but the portal session was not usable
for the new request.

**Fix**

Logout with the current cookie, NAS logout, wait 1.5 s, verify, retry once.
The retry is depth-limited so it cannot recurse forever.

### 9.4 “Portal said success but internet is still blocked”

**Cause**

The portal JSON is not the final authority; the NAS gate is. A success response
can arrive before traffic is actually allowed.

**Fix**

Always verify with the probe. `login()` polls up to 9 seconds after a portal
success and only returns success on `204`.

### 9.5 Notification spam

**Cause**

The first version updated the foreground notification for every transition:
“Signing in…”, “Waiting for Wi-Fi”, repeated failures, etc.

**Fix**

A state machine with five meaningful states and a deduplicating `update()`.
No progress messages, no repeated identical text, silent and `onlyAlertOnce`.
The UI still refreshes every two seconds, but the notification does not.

### 9.6 Logout was a no-op

**Cause**

`GET /logout` and an unbound `logout.do` do not tear down a NAS session. The
portal requires the `JSESSIONID` that `newlogin.do` bound to the live session.

**Fix**

Discover the portal through the NAS splash even while online, open a fresh
session, bind it with `newlogin.do`, then call `logout.do` with that cookie.
Use the NAS logout as a fallback and verify with the probe.

### 9.7 Logout immediately logged back in

**Cause**

The background service was still enabled and would run the next poll within
60 seconds.

**Fix**

The logout path clears `Prefs.enabled`, turns the switch off, and stops the
service before sending the logout requests. The UI asks for confirmation first.

### 9.8 Foreground-service and OEM restrictions

**Cause**

Android 12+ restricts background FGS starts; Android 14 requires an FGS type;
OEM power managers kill long-running services.

**Fix**

* Start the service only from the Activity, boot/package-update receiver, or a
  notification action.
* Use `specialUse` with `FOREGROUND_SERVICE_SPECIAL_USE` and the subtype
  property.
* `START_STICKY`, `stopWithTask="false"`.
* Provide a battery-optimisation settings shortcut; document OEM autostart
  settings.

### 9.9 Build-time issues

* **Kotlin keyword:** `in.ac.curaj.autologin` is valid Java but not valid
  Kotlin. All Kotlin sources use ``package `in`.ac.curaj.autologin``.
* **Hidden framework icons:** the first notification used private
  `android.R.drawable` resources that are not in the public SDK. The app now
  ships `ic_stat_wifi`, `ic_sync`, and `ic_pause` vector drawables.
* **Cleartext IP literals:** Android blocks cleartext HTTP to IP addresses
  unless they are listed in `network_security_config.xml`; all probe IPs and
  portal/NAS hosts are listed.

### 9.10 Android’s captive-portal UI race

Android may show **Sign in to Wi-Fi** and start its own authentication at the
same time as the app. The app cannot disable that system UI without device-owner
privileges. The mitigation is the transient-auth handling in §6.3, plus the
fact that the app reports success only after the probe reaches `204`. A brief
system notification can still appear; it normally clears once the network is
validated.

### 9.11 Intermittent `EPERM` when binding to Wi-Fi (1.5)

**Symptom**

Auto-login sometimes failed with `Permission denied` / `EPERM` while opening a
socket, especially when mobile data was also active or the phone had just
roamed between APs.

**Cause**

The old request path could fall back to a network from `allNetworks` when
mobile data was the active network. That list can contain a stale Wi-Fi
network object; `Network.openConnection()` on it throws `EPERM` on some Android
builds.

**Fix**

* `NetworkMonitor` registers a `NetworkCallback` for Wi-Fi and exposes exactly
  one fresh `Network`.
* `PortalClient` pins every portal socket to that network and never falls back
  to mobile data.
* `login()` maps `EPERM`/`EACCES`/`Binding socket` failures to
  `Result(bindError = true)`. The service asks `NetworkMonitor` for a fresh
  network and retries with a short delay instead of reporting a hard failure.
* The retry loop is bounded, so a genuinely broken Wi-Fi stack still ends in a
  normal failure and backoff.

**Verified**

The 1.5 release installed over 1.4, kept the saved account, launched, and
auto-logged in (`login ok=true: already online`) with mobile data and Wi-Fi
both enabled.

---

## 10. Build, install, and test

### Build

```bash
export JAVA_HOME="$(mise where java@temurin-17.0.20+101)"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$PATH"

cd ~/curaj-wifi/android
./gradlew assembleDebug
```

The APK is at:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

### Install

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### Observe

```bash
adb logcat -s AutoLoginService PortalClient
adb shell dumpsys notification --noredact | grep -A40 in.ac.curaj.autologin
adb shell dumpsys activity services in.ac.curaj.autologin
```

### Force an attempt

```bash
adb shell am start-foreground-service \
  -n in.ac.curaj.autologin/.AutoLoginService \
  -a in.ac.curaj.autologin.LOGIN_NOW
```

### Live test matrix

| case | expected result | status |
|---|---|---|
| fresh login on campus Wi-Fi | `login ok=true: logged in`, probe `204` | passed |
| already authenticated | `login ok=true: already online` | passed |
| Wi-Fi off/on with live portal session | `204`, no unnecessary credential POST | passed |
| duplicate-auth race | no second `newlogin.do`; transient retry; success when gate opens | passed |
| logout | UI records `Logged out`; auto-login off; service stopped | passed |
| notification on success | `Connected - internet is working` | passed |
| notification on disconnect | `Disconnected from campus Wi-Fi` | code path checked; not separately captured in the log |
| reboot | service restarts only if enabled + credentials exist | code path reviewed |
| battery whitelist | `deviceidle whitelist` contains the app | passed |

The detailed device evidence is in [ANDROID-TEST.md](ANDROID-TEST.md).

---

## 11. File map

```
android/app/src/main/java/in/ac/curaj/autologin/
  MainActivity.kt        credentials, test, logout, status, settings shortcuts
  AutoLoginService.kt    FGS, network callback, poll, backoff, notifications
  PortalClient.kt        probe, discovery, host pinning, login/logout
  Prefs.kt               Keystore-backed encrypted preferences
  BootReceiver.kt        boot / package-replaced restart

android/app/src/main/
  AndroidManifest.xml
  res/layout/activity_main.xml
  res/xml/network_security_config.xml
  res/drawable/ic_stat_wifi.xml
  res/drawable/ic_sync.xml
  res/drawable/ic_pause.xml
```

---

## 12. Version history

Approximate evolution from the build/test notes:

| version | code | changes |
|---|---|---|
| 1.0 | 1 | initial Android client: foreground service, encrypted prefs, portal flow |
| 1.1 | 2 | host pinning, stale-session recovery, notification actions |
| 1.2 | 3 | fixed pre-login DNS failure with fixed probe IPs; cleartext allowlist; live-test fixes |
| 1.3 | 4 | duplicate-auth handling, transient retries, state-only notifications, logout button and real logout |
| 1.4 | 5 | two-session limit detection, session list/logout with splash-context cookie, Manage sessions notification and retry |
| 1.5 | 6 | Material 3 redesign, live network dashboard (SSID/signal/IP/band/latency/loss/throughput/link), auto-saving account fields, dynamic Connect now → Log out, session sheet with spinner/auto-refresh, shared `NetworkMonitor` and fresh-network `EPERM` retry |

---

## 13. Limits and things to re-check if it breaks

* **Portal IP change** — update `PORTAL_HOSTS` in `PortalClient.kt` and the
  network-security config. Do not replace the pin with “any `/userportal`
  URL”.
* **Portal HTML change** — `findPortal` handles `Location`, meta refresh, JS
  redirect, and raw URLs. A JS-only page that never exposes the URL would need
  a WebView fallback.
* **Portal vendor/protocol change** — re-check `newlogin.do`, the success
  fields, the `currentSessions.do` table, and the logout cookie binding against
  live traffic. The close path needs the splash-context cookie, not just a
  self-care cookie.
* **MAC randomisation** — the portal sees a new device per random MAC. Login
  still works, but stale-session behaviour can differ. Set “Use device MAC” for
  this SSID if it becomes noisy.
* **Aggressive OEMs** — battery optimisation and autostart settings still
  matter; a force-stopped app will not receive boot broadcasts.
* **Plain HTTP** — credentials are only as safe as the campus LAN. The app
  cannot fix the portal’s transport.

---

## 14. Quick troubleshooting

| log / symptom | meaning | action |
|---|---|---|
| `refusing to send credentials to …` | portal host is not the pinned one | verify the campus portal IP; do not disable the pin casually |
| `probe … failed` for all four IPs | no route through Wi-Fi, or Wi-Fi not ready | check Wi-Fi; the next poll retries |
| `portal is already authenticating this IP; waiting` | system/app race | wait; app polls and retries without a second login |
| `portal is still authenticating; retrying` | transient state | bounded retries follow; no failure notification yet |
| `rejected: …` | credentials or portal state | fix credentials; check portal manually |
| `session limit reached (2 active)` | both account slots are in use | tap **Manage sessions**, close one, let auto-login retry |
| `portal did not close session … (HTTP 302)` | close used the wrong context cookie, or the id changed | refresh the list; the client will never report a `302` as success |
| `could not verify logout` | probe never observed a non-204 response | auto-login stays off; check Wi-Fi/portal manually |
| notification says `Connected` but browser fails | stale success or route issue | check with `curl --interface wlan0`; next poll re-verifies |

If `adb logcat` shows nothing, confirm the service is running:

```bash
adb shell dumpsys activity services in.ac.curaj.autologin
```

If it is not running, open the app once and turn **Auto connect** on; a
force-stopped app is not restarted by Android until it is opened again.
