# CURAJ CAMPUS CONNECT — auto-login

Reverse-engineered the captive portal, then built:

* **Linux** — `curaj-login`, a dependency-free Python client + systemd user service.
* **Android** — a small Kotlin app with a foreground service that logs you in
  whenever the portal blocks the Wi-Fi.

Everything here was derived from live traffic on `CURAJ CAMPUS CONNECT`
(interface `wlp0s20f3`, IP `10.50.11.134`, gateway `10.50.0.1`).

## Documentation

| document | contents |
|---|---|
| [docs/REVERSE-ENGINEERING.md](docs/REVERSE-ENGINEERING.md) | full narrative: recon, mistakes, every request, `sc` analysis, security findings |
| [docs/ENDPOINTS.md](docs/ENDPOINTS.md) | endpoint card: methods, parameters, response schemas, checklist |
| [docs/EVIDENCE.md](docs/EVIDENCE.md) | raw headers, bodies, JSON, service logs (credentials redacted) |
| [docs/ANDROID.md](docs/ANDROID.md) | Android permissions, background model, OEM setup, testing, credential pinning |
| [docs/ANDROID-ARCHITECTURE.md](docs/ANDROID-ARCHITECTURE.md) | app architecture, runtime flows, concurrency, notification state machine, logout, and the failures fixed along the way |
| [docs/BUILD-ONLINE.md](docs/BUILD-ONLINE.md) | reference CI recipes (GitHub Actions is disabled for this repo) |
| [docs/LOCAL-BUILD.md](docs/LOCAL-BUILD.md) | build the APK locally with JDK 17 + Android command-line tools, no Android Studio |
| [docs/RELEASE-BUILD.md](docs/RELEASE-BUILD.md) | create/back up a release key, build a signed production APK, verify it, and distribute it |
| [docs/ANDROID-TEST.md](docs/ANDROID-TEST.md) | live test on Android 16: install, DNS probe failure/fix, successful login, Wi-Fi reconnect, duplicate-auth race, logout, session limit |
| [docs/SENTRY.md](docs/SENTRY.md) | error/tracing setup, credential scrubbing, local DSN/token configuration, and verification |
| [docs/RELEASE-NOTES-1.6.md](docs/RELEASE-NOTES-1.6.md) | 1.6 highlights, install/upgrade notes, checksum, and verification status |
| [docs/SESSION-MANAGEMENT.md](docs/SESSION-MANAGEMENT.md) | the two-session limit, self-care login, why the splash context cookie is required, and verified close semantics |

---

## 1. What the portal actually is

```
laptop ──Wi-Fi── 10.50.0.1  (NAS/gateway)
                    ├── :53   DNS
                    ├── :3128 Squid (transparent HTTP proxy)
                    └── :80   closed
                          │
                          └── pre-auth HTTP is answered by lighttpd/1.4.37
                              "Powered by Inventum Technologies"
                              meta-refresh →
                              http://122.252.242.93/userportal/?...
                                   Apache/2.2.15 (CentOS) + Tomcat/5.5.23
                                   RailWire / Inventum portal
```

There is no login page on the gateway itself. The gateway hijacks any
plain-HTTP request and returns a 1-second meta-refresh page:

```html
<META HTTP-EQUIV="Refresh" CONTENT="1;URL=http://122.252.242.93/userportal/
  ?requesturi=http%3a%2f%2fexample%2ecom%2f%3f
  &ip=10%2e50%2e11%2e134
  &mac=74%3a04%3af1%3ade%3a65%3a52
  &nas=curaj
  &requestip=example%2ecom
  &sc=6d244db2d6b400d00d5f9fa1e00af476
  &interface=cpeth3
  &locationid=B5-FF-Roomno.126:CURAJ%20CAMPUS%20CONNECT">
```

| parameter | meaning |
|---|---|
| `ip`, `mac` | client identity as seen by the NAS |
| `nas=curaj` | NAS/realm name |
| `sc` | 32-hex per-device token; the portal session is bound to it |
| `interface=cpeth3` | NAS-side captive interface |
| `locationid` | AP / room name |
| `requesturi` | the URL you originally asked for |

`sc` is **not** derived on the client and does not need to be forged — it is
handed to you in the redirect. The client parses it and keeps the matching
`JSESSIONID`.

## 2. The exact login flow

```
1. GET http://connectivitycheck.gstatic.com/generate_204
      → 200 + meta-refresh (captive)      [204 = already online]

2. GET http://122.252.242.93/userportal/?...&sc=...
      → 200, Set-Cookie: JSESSIONID=...
      → body auto-POSTs login.do

3. POST http://122.252.242.93/userportal/login.do?requesturi=...
      → 200, JS redirect to
        /userportal/pages/usermedia/curaj/app/campus/ui/index.html

4. POST http://122.252.242.93/userportal/newlogin.do     (same JSESSIONID)
      Content-Type: application/x-www-form-urlencoded

      username=<mobile>&password=<password>&phone=0&type=2&jsonresponse=1

      → {"errorKey":"success","errorMessage":"success_net"}

5. NAS opens the gate for that MAC/IP.
      GET generate_204 → 204
```

The form is nothing more than
`/userportal/pages/usermedia/curaj/app/campus/ui/login.html`:

```html
<form id="form1" method="POST" action="/userportal/newlogin.do">
  <input name="username" maxlength="10">
  <input type="password" name="password">
  <input type="hidden" name="phone" value="0">
  <input type="hidden" name="type" value="2">
  <input type="hidden" name="jsonresponse" value="1">
</form>
```

The page checks both fields before redirecting:

```js
if (data.errorMessage == "success_net" && data.errorKey == "success")
    window.location = "logout.html";
```

Other behaviour found while probing:

* `POST newlogin.do` **without** the `sc`/`JSESSIONID` context →
  `{"errorKey":"redirect_to_nas"}`.
* `POST newlogin.do` with valid credentials while the NAS already considers
  the device online → `{"errorMessage":"Session already running","errorKey":"failureCase"}`.
  The client clears that by calling `logout.do` with its session cookie, then
  the NAS `/logout`, and retrying once.
* `GET /userportal/logout.do` **with the login JSESSIONID** → real logout
  (the response is `200` and the SPA logout page).
* `GET /userportal/logout.do` without a session → `302 http://1.254.254.254?N=<epoch-ms>`.
* `GET http://1.254.254.254/logout` → `200` empty (NAS-side logout).
* `http://1.254.254.254/` is the splash hop that redirects back to the portal.
* `sc` was identical across separate logins for the same MAC/IP
  (`6d244db2d6b400d00d5f9fa1e00af476`), so it behaves like a per-device token,
  not a per-session nonce. The client still parses the current value.

### Verified live (2026-10-21)

```
before           online via wlp0s20f3
logout.do        blocked (probe HTTP 200, portal URL with sc)
curaj-login once portal says success_net / success in 2.7 s
after            generate_204 -> 204, https://example.com -> 200
watch service    logged out 04:51:51, detected 04:52:24, re-logged-in 04:52:51
```

## 3. The trap that breaks naive scripts

This laptop had **two default routes**: Wi-Fi (metric 600) and a USB-tethered
phone (metric 100). Binding the source address with `curl --interface <ip>`
is not enough — the kernel still picked the tether route and the portal looked
unreachable. Every request in the Linux client sets `SO_BINDTODEVICE` on the
socket; the Android client uses `Network.openConnection()` so the request is
pinned to Wi-Fi and mobile data cannot steal it.

If you debug by hand, prefer:

```bash
curl --interface wlp0s20f3 ...     # device, not address
ip route get 122.252.242.93       # see which route actually wins
```

## 4. Linux

```bash
cd linux
./install.sh
$EDITOR ~/.config/curaj-wifi/config.json     # chmod 600, mobile + password

curaj-login status        # online / blocked + portal URL
curaj-login once          # one attempt, prints the portal's JSON
curaj-login watch         # stay running
curaj-login logout        # real logout of this device
curaj-login sessions      # list active portal sessions
curaj-login sessions --json
curaj-login sessions --logout <ID>   # close one chosen session, then reconnect
```

The account allows two simultaneous sessions. If login fails with
`Maximum session allowed reached`, run `curaj-login sessions`, choose the
session you are not using, and close it:

```bash
curaj-login sessions --logout 137706022
```

The client never closes a session automatically on the limit; it only lists
them and lets you choose. See
[docs/SESSION-MANAGEMENT.md](docs/SESSION-MANAGEMENT.md) for the full flow and
the splash-context cookie requirement.

Logout notes: `logout` takes ~8 s because a bare `GET /logout` is a no-op.
It discovers the portal via `1.254.254.254`, opens a session, binds it with
`newlogin.do`, then calls `logout.do` with that `JSESSIONID`. To close one of
two sessions instead, use `sessions --logout <ID>`; that path needs a
**splash-context** cookie, not a direct self-care cookie (a direct cookie gets
a misleading `302` and leaves the session alive). Because the background
service re-logs in within 60 s, stop it first if you want to stay offline:

```bash
systemctl --user stop curaj-login.service
curaj-login logout
```

Enable the background service:

```bash
systemctl --user enable --now curaj-login.service
journalctl --user -u curaj-login -f
sudo loginctl enable-linger "$USER"   # only if it must run while logged out
```

`watch` probes every 60 s (`poll_online`) and retries failures with backoff.
On `redirect_to_nas` it calls the NAS logout endpoint once and retries — that
clears a stale half-session.

Optional: log in instantly when NetworkManager brings the interface up
(otherwise the worst case is a 60 s wait):

```bash
sudo install -m755 linux/99-curaj-autologin /etc/NetworkManager/dispatcher.d/
```

Offline test of the whole state machine (mock portal on localhost):

```bash
cd linux && python3 test_flow.py
```

Config keys:

| key | default | |
|---|---|---|
| `username` | — | mobile number |
| `password` | — | portal password |
| `ssid` | `CURAJ CAMPUS CONNECT` | used to pick the interface |
| `portal_hosts` | `["122.252.242.93"]` | hosts allowed to receive the password |
| `interface` | auto | override, e.g. `wlp0s20f3` |
| `poll_online` | `60` | seconds between checks when online |
| `poll_retry` | `20` | base backoff when blocked |

## 5. Android

Source: `android/` (Kotlin, minSdk 26, targetSdk 35). Current APK is
**1.6** (versionCode 7). The 1.6 release keeps the Material 3 dashboard,
live network monitor, session sheet, and one-tap log out, and adds Auto
connect on by default plus Sentry crash, error, and performance reporting.
Source context is uploaded for release builds.

```
app/src/main/java/in/ac/curaj/autologin/
  MainActivity.kt      dashboard, auto-saving account, metrics, menu, logout
  AppState.kt          live state shared by the activity and service
  NetworkMonitor.kt    one fresh Wi-Fi Network for every portal request
  NetworkMetrics.kt    HTTP latency/loss + app throughput sampler
  SessionsSheet.kt     bottom sheet: list, close, verify, auto-refresh
  Prefs.kt             EncryptedSharedPreferences (Keystore-backed)
  PortalClient.kt      probe, discovery, host pinning, login/logout, sessions
  AutoLoginService.kt  foreground service + NetworkCallback + 60 s poll
  BootReceiver.kt      restarts after boot / app update
```

See [docs/ANDROID-ARCHITECTURE.md](docs/ANDROID-ARCHITECTURE.md) for the full
runtime and design walkthrough.

Build with Android Studio (open `android/`) or, with the included Gradle
8.14.6 wrapper:

```bash
cd android

# development build (debug key)
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk

# signed build for sharing with friends
./gradlew assembleRelease
```

The signed release APK is at
`android/app/build/outputs/apk/release/app-release.apk`. A prepared copy is in
`dist/CURAJ-AutoLogin-1.6.apk`
(SHA-256 `eb647a9254cf54eaa9fdef234662a047c2743388ceb284ce2a516d8b24faca44`);
see [docs/RELEASE-BUILD.md](docs/RELEASE-BUILD.md) for the keystore,
verification, and distribution steps. Users upgrading from 1.5 can install
1.6 over it (`adb install -r`) without losing the saved account.

GitHub Actions is intentionally disabled for this repository, so builds are
made locally with the included wrapper. See
[docs/LOCAL-BUILD.md](docs/LOCAL-BUILD.md) for the JDK 17 + command-line SDK
setup and [docs/RELEASE-BUILD.md](docs/RELEASE-BUILD.md) for the signed
production build. The workflow files are retained only as a reference.

Permissions: **notifications** (Android 13+) are requested at first launch.
**Nearby Wi-Fi devices** (Android 13+) is optional and only used to show the
SSID and signal strength on the dashboard; Android 12 and older use the
fine-location permission for the same optional dashboard details. Login works
without either permission and still shows the AP's IP, band, latency, loss,
throughput, and link speed. `INTERNET`, `ACCESS_NETWORK_STATE`,
`ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`
and `RECEIVE_BOOT_COMPLETED` are normal install-time permissions.

How it behaves:

* Runs as a `specialUse` foreground service with a low-priority notification
  and **Login now** / **Pause** actions.
* `ConnectivityManager` callback fires when Wi-Fi appears and when Android
  sets `NET_CAPABILITY_CAPTIVE_PORTAL` (or drops `VALIDATED`); a 60 s poll is
  the fallback for OEMs that delay the callback. The callback keeps one fresh
  Wi-Fi `Network`; portal sockets are bound to it so a mobile-data connection
  cannot steal credentials and a stale network cannot cause the intermittent
  `EPERM` bind failure.
* It identifies the campus portal by the redirect host (`122.252.242.93`), and
  `PORTAL_HOSTS` refuses to send credentials to any other host — so a hostile
  Wi-Fi cannot harvest the password with a fake `/userportal` page.
* The main screen shows live status, the Wi-Fi SSID (when optional Wi-Fi
  details are allowed), IP, band, signal percentage/RSSI, HTTP latency and
  packet loss from five probes, app download/upload throughput, and the
  negotiated link speed. It auto-refreshes every five seconds.
* Account fields save automatically after a short debounce. **Auto connect**
  starts on for fresh installs and starts the foreground service as soon as
  both credentials are valid; the switch can still pause it. There is no Save
  button.
* **Connect now** runs a one-shot login from the app even when auto connect is
  off. When the portal is connected, the same button becomes **Log out**.
* If Android’s own captive-portal login is already authenticating the same IP,
  the app does not send a second login. It polls the probe, retries briefly,
  and reports success as soon as the gate opens.
* The notification is state-only: **Connected**, **Sign-in failed**, or
  **Disconnected**. There are no “signing in” or “waiting” updates, and
  identical states are not re-posted.
* **Log out** performs a real portal logout (fresh `JSESSIONID` bound with
  `newlogin.do`, then `logout.do`), stops the service, and turns Auto connect
  off so it does not sign straight back in.
* **Sessions** opens a bottom sheet that loads with a spinner, lists the two
  active portal sessions, shows a spinner on the row being closed, and
  auto-refreshes after a close. When login hits the two-session limit, a
  separate high-priority notification offers **Manage sessions**; the
  foreground notification stays low-priority and silent. Only the session you
  select is closed, then auto-login retries.
* Failed attempts back off 1 → 2 → 4 → 8 → 15 minutes, so a wrong password
  does not hammer the portal. **Login now** bypasses the backoff.
* Credentials are encrypted with a Keystore master key. Crash and performance
  reports go to Sentry; request bodies, usernames, passwords, cookies, and
  authorization headers are scrubbed before events leave the device. See
  [docs/SENTRY.md](docs/SENTRY.md).
* Cleartext HTTP is permitted only for the portal IPs and the connectivity
  check hosts (`res/xml/network_security_config.xml`).
* On Android 12+ you may need to exempt the app from battery optimisation
  (**three-dot menu → Battery optimisation**). Some OEMs (Xiaomi/Oppo/Vivo)
  additionally require "Autostart".

## 6. Security findings (worth knowing)

* **The password is sent over plain HTTP.** `newlogin.do` is
  `http://122.252.242.93/...`, not HTTPS. Anyone on the path can read the
  credentials. Use a password you do not reuse anywhere else, and consider
  reporting this to the university IT/network team.
* The portal stack is very old: Apache **2.2.15** (2010), Tomcat **5.5.23**
  (2007), lighttpd **1.4.37**, all long past end-of-life.
* `sc` is a long-lived per-device token, not a CSRF defence; it is visible in
  every redirect and is bound to IP/MAC. See
  [docs/REVERSE-ENGINEERING.md](docs/REVERSE-ENGINEERING.md#7-the-sc-token).
* `logout.do` is state-changing; the per-session Close button submits a
  **POST**. The session-close path also requires the splash-context cookie,
  which is why a direct self-care cookie gets a misleading `302`.

## 7. Limits / unknowns

* The derivation of `sc` was not reversed — it does not need to be, it is
  parsed from the redirect each time.
* The Android APK is built locally with JDK 17 + the Android command-line SDK
  and has been tested on a OnePlus CPH2793 (Android 16). The debug APK is not
  Play-Store signed.
* If the university changes the portal vendor or the redirect shape, update
  `PORTAL_URL_RE` in `linux/curaj-login` and the regexes in `PortalClient.kt`.
* The account is limited to **two simultaneous internet sessions**. Both
  clients list and close one chosen session; they never auto-kick. Session
  lifetime is still not measured; the service handles expiry by re-running the
  flow.

## 8. Reproducing the recon

```bash
# 1. Is the Wi-Fi actually the path? (tether can hide the portal)
ip route get 122.252.242.93

# 2. What does pre-auth HTTP return?
curl -sS --interface wlp0s20f3 --noproxy '*' -D - http://example.com/

# 3. The gateway's proxy (also shows the portal redirect)
curl -sS -x http://10.50.0.1:3128 http://example.com/

# 4. What is exposed on the gateway?
nmap -Pn -p 53,80,443,3128 10.50.0.1
```

---

**Files**

```
curaj-wifi/
├── README.md
├── .github/workflows/
│   ├── android.yml                 # debug APK on GitHub
│   └── android-release.yml         # signed release on a tag
├── docs/
│   ├── REVERSE-ENGINEERING.md
│   ├── ENDPOINTS.md
│   ├── EVIDENCE.md
│   ├── ANDROID.md
│   ├── ANDROID-ARCHITECTURE.md
│   ├── ANDROID-TEST.md
│   ├── SESSION-MANAGEMENT.md
│   ├── BUILD-ONLINE.md
│   ├── LOCAL-BUILD.md
│   └── RELEASE-BUILD.md
├── linux/
│   ├── curaj-login          # client (status/once/watch/logout/sessions)
│   ├── curaj-login.service  # systemd --user unit
│   ├── 99-curaj-autologin   # optional NetworkManager dispatcher hook
│   ├── config.example.json
│   ├── install.sh
│   └── test_flow.py         # offline mock-portal test
└── android/                 # Gradle project
```
