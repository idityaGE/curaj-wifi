# Android live test

Tested against the real campus network, not a mock.

| item | value |
|---|---|
| phone | OnePlus CPH2793 |
| Android | 16 |
| app | `in.ac.curaj.autologin` 1.5 (versionCode 6) |
| network | `CURAJ CAMPUS CONNECT` |
| Wi-Fi IP | `10.50.13.252/20` |
| Wi-Fi MAC | `da:bb:9b:8f:fd:16` |
| portal | `122.252.242.93` |
| result | 1.3: login succeeded (`204` through `wlan0`), logout succeeded. 1.4: release built and signature/metadata verified. 1.5: release installed over 1.4 without losing saved data; auto-login, dynamic **Log out**, and the live dashboard were verified on-device. The nearby-Wi-Fi prompt and session-sheet close still need a final pass with the cable connected. |

Credentials are not shown here.

---

## 1. Install and launch

```bash
adb install -r app-debug.apk
adb shell am start -W -n in.ac.curaj.autologin/.MainActivity
```

The app asks for the notification permission on first launch. On Android 13+
this is the only runtime permission it needs for login. The 1.5 main screen is
a Material 3 dashboard:

```
CURAJ Auto Login                    ⋮
┌──────────────────────────────────────┐
│ CURAJ CAMPUS CONNECT              ●  │
│ Connected                            │
│ Internet is working                  │
│ 10.50.13.252 · 2.4 GHz · -66 dBm    │
│ [        Log out        ] [Sessions] │
└──────────────────────────────────────┘
┌──────────────────────────────────────┐
│ Network                    just now ⟳│
│ Signal          Latency              │
│ ▂▄▆█ 75%        182 ms               │
│ -66 dBm                              │
│ Loss            Link speed           │
│ 0% (5 probes)   39 Mbps              │
│ Download        Upload               │
│ 12 KB/s         3 KB/s               │
└──────────────────────────────────────┘
┌──────────────────────────────────────┐
│ Account                              │
│ [ Mobile number / username ]         │
│ [ Password                 👁 ]      │
└──────────────────────────────────────┘
┌──────────────────────────────────────┐
│ Auto connect                   [on]  │
│ Runs in the background               │
└──────────────────────────────────────┘
Last action: Online · just now
```

Account edits save automatically after 500 ms. The main button is
**Connect now** until the portal session is connected, then **Log out**.
Wi-Fi name and signal percentage appear after **Enable Wi-Fi details** is
allowed; without it the card says **Campus Wi-Fi** and still shows the IP,
band, latency, loss, throughput, and link speed.

## 2. First failure: DNS before login

Immediately after connecting to the campus Wi-Fi, the foreground service
tried to log in and showed:

```
Sign-in failed: probe failed: Unable to resolve host
"connectivitycheck.gstatic.com": No address associated with hostname
```

The phone was still connected to 5G as well, so this was not “no internet”.
The app pins its sockets to the Wi-Fi `Network` (the Android equivalent of
Linux `SO_BINDTODEVICE`). That pins **DNS resolution** too, and the captive
Wi-Fi DNS returns NXDOMAIN until the portal login succeeds.

A shell `curl` worked only because it resolved the name through the default
mobile network first:

```bash
# resolves via mobile data, connects via wlan0:
adb shell 'curl --interface wlan0 http://connectivitycheck.gstatic.com/generate_204'
# HTTP/1.1 200 OK, Server: lighttpd/1.4.37   <- portal splash
```

The same request to an IP literal returned the portal splash without DNS:

```bash
adb shell 'curl --interface wlan0 http://142.251.150.120/generate_204'
# HTTP/1.1 200 OK, Server: lighttpd/1.4.37
# <meta http-equiv="Refresh"
#   CONTENT="1;URL=http://122.252.242.93/userportal/?...&sc=...">
```

## 3. The fix

`PortalClient` now probes four Google `generate_204` IPs in order:

```kotlin
private val CHECK_URLS = listOf(
    "http://142.251.150.120/generate_204",
    "http://142.250.183.36/generate_204",
    "http://142.250.72.196/generate_204",
    "http://142.251.32.14/generate_204"
)
```

* No DNS is involved.
* The request still goes through `network.openConnection`, so it is bound to
  the Wi-Fi network and cannot be stolen by mobile data.
* When online, Google returns `204`. When captive, the portal intercepts the
  request and returns the lighttpd meta-refresh page.
* The four IPs are allowed in
  `res/xml/network_security_config.xml`; otherwise Android rejects cleartext
  HTTP to an IP literal.

## 4. Successful login

After rebuilding and installing 1.2 (the first version that reached the live
portal; 1.3 keeps the same login path):

```
10-21 07:57:19.030 I/PortalClient: portal: http://122.252.242.93/userportal/?requesturi=...&sc=...
10-21 07:57:21.473 I/AutoLoginService: login ok=true: logged in
```

The UI then showed:

```
Auto-login is ON. Wi-Fi connected; watching the portal.
Online
21 Oct 2026 7:57:21 am
```

Verification that the session is actually usable through Wi-Fi, not mobile
data:

```bash
adb shell 'curl -sS -o /dev/null -w "HTTP %{http_code} via %{local_ip}\n" \
  --max-time 10 --interface wlan0 https://example.com/'
# HTTP 200 via 10.50.13.252
```

`dumpsys connectivity` also changed the Wi-Fi network from
`CAPTIVE_PORTAL` to `VALIDATED`.

## 5. Wi-Fi reconnect test

Wi-Fi was disabled and re-enabled from `adb`:

```bash
adb logcat -c
adb shell svc wifi disable
adb shell svc wifi enable
adb shell 'cmd wifi connect-network "CURAJ CAMPUS CONNECT" open'
```

The phone reconnected with the same IP/MAC, and the portal session was still
valid, so no new login was needed. The probe through `wlan0` returned `204`:

```bash
adb shell 'curl -sS -o /dev/null -w "HTTP %{http_code} via %{local_ip}\n" \
  --interface wlan0 http://142.251.150.120/generate_204'
# HTTP 204 via 10.50.13.252
```

The foreground service remained alive (`isForeground=true`), so when the NAS
does expire the session, the next probe returns the portal page and triggers
the login path. The login path itself was already exercised in section 4.

## 6. Duplicate-auth race (1.3)

Version 1.2 could fail with:

```
Sign-in failed: rejected: Authentication already in progress for IP <ip>
```

That message means the portal was already authenticating the same IP/MAC — often
because Android’s own captive-portal activity had started a login. The old
client treated it as a hard failure and waited a full backoff interval.

Version 1.3 detects the phrase and does **not** POST a second `newlogin.do`.
It polls the probe for up to six seconds and reports success as soon as the
gate opens. If the portal is still working, the service retries after three
seconds (bounded to 10 transient retries) without posting a false failure
notification.

On the live network the old failure no longer appears, and the connection is
reported as soon as the portal finishes.

## 7. Logout

With the portal connected, tap **Log out** and confirm. The app:

1. turns Auto connect off;
2. stops the foreground service;
3. opens a fresh portal session through `1.254.254.254`;
4. binds the fresh `JSESSIONID` with `newlogin.do`;
5. calls `logout.do`, then the NAS logout;
6. verifies that the probe no longer returns `204`.

The UI recorded:

```
Logged out
21 Oct 2026 9:27:34 am
```

`Logged out` is only written after the verification probe sees a non-`204`
response, so it is not just “the request was sent”. Auto connect remains off
and the foreground notification is removed. Turn **Auto connect** back on or
tap **Connect now** to sign in again.

## 8. Two-session limit (1.4)

This is the new release flow. Use two devices on the same account (or leave
one stale session from another device) so both slots are occupied.

1. Start auto-login on the phone. The portal answers
   `Maximum session allowed reached`.
2. The app records **Session limit reached** and posts a separate
   high-priority notification with **Manage sessions**.
3. Tap **Manage sessions** (or the **Sessions** button). The dialog should list
   two sessions with start time, duration, total bytes, name, and account id.
4. Tap **Log out this session** on the session that is **not** this phone, then
   confirm. The app should report success only after re-listing the sessions
   and seeing the id disappear.
5. If auto-login is enabled, the app immediately retries the normal login and
   should reach **Online**.
6. Repeat using the phone’s own session: the Wi-Fi drops, the app should
   reconnect on the next attempt, which is expected.

What the live Linux verification established (same portal, same cookie rules):

```
before:  two active sessions
close:   POST /userportal/logout.do?from=curses&sesno=137706181
         with the splash-context JSESSIONID -> HTTP 200
after:   currentSessions.do -> no active sessions
relogin: newlogin.do -> success_net / success
```

A direct self-care cookie produced `302 http://1.254.254.254?N=...` and did
**not** close the session. If the phone ever shows
`portal did not close session ... (HTTP 302)`, that is the verification
correctly refusing a false success; refresh the list and try again.

## 8.5 1.5 dashboard and EPERM retest

The 1.5 release was installed over the existing 1.4 install:

```bash
adb install -r dist/CURAJ-AutoLogin-1.5.apk
```

The version stayed at the saved account (no re-entry was needed). On campus
Wi-Fi with mobile data enabled, the service logged:

```
AutoLoginService: attempt network=144
AutoLoginService: login ok=true: already online
```

and the dashboard showed live values:

| field | observed |
|---|---|
| Wi-Fi IP | `10.50.13.252` |
| band | `2.4 GHz` |
| signal | `75%  -66 dBm` (after permission; test run also showed `-77 dBm`) |
| latency | `182–760 ms` (five HTTP probes; varies on the campus AP) |
| loss | `0%  (5 probes)` |
| link speed | `39 Mbps` |
| download / upload | updated every 5 s from `TrafficStats` |

The main button changed from **Connect now** to a red **Log out** while
connected. Tapping **Log out** cleared Auto connect and the session; the
button returned to **Connect now**. The nearby-Wi-Fi permission could not be
granted over `adb` (`SecurityException` for the shell UID), so the final SSID
prompt and the session-sheet close pass must be done through the app UI.

## 9. What to test manually

1. Open the app while on campus Wi-Fi. Tap **Connect now**.
   * Fresh session → “Online”.
   * Already online → “Online (already signed in)”.
   * Connected → the button says **Log out**.
2. Turn Wi-Fi off and on; the service should recover without opening the app.
3. Reboot the phone; `BootReceiver` restarts the service.
4. Tap **Login now** on the notification to bypass the failure backoff.
5. Tap **Pause** to stop automatic login; turn **Auto connect** back on in the
   app to re-enable it.
6. Tap **Log out**, confirm, and check that the app records **Logged out**, the
   switch is off, and the service notification disappears. The portal page
   should be back on the next Wi-Fi request.
7. Tap **Enable Wi-Fi details** in the Network card, allow the prompt, and
   confirm the SSID and signal percentage appear. Deny/reinstall to confirm
   login still works without it.
8. Tap **Sessions** and confirm the sheet shows a spinner while loading and
   the list matches the portal’s two-session table. Close the session you are
   not using; the row spinner should run, the sheet should refresh itself, and
   auto-connect should retry. Do not close the session you are using unless you
   want to reconnect.
9. Allow battery optimisation exemption if the OEM kills the service:
   **three-dot menu → Battery optimisation**.
10. Test with mobile data on and off, and toggle Wi-Fi, to confirm there is no
    `EPERM` in logcat and no credentials go out over mobile data.

## 10. Notes

* The tested 1.3 APK is versionCode 4, SHA-256
  `879d23fb068e2a9c5d401974f9460cb169ab2a4bec3c583cc32313ce3ae5a6dd`.
* The 1.4 release APK was versionCode 5, SHA-256
  `58883579c95cb55b15c5fd10a09209e1c607125f4ce78b1bcdef0c3eba4b7eb8`.
* The 1.5 release APK is versionCode 6, SHA-256
  `30ec1785b17482e66fce9ed78dda92afd695633759b6a6ea187b7b62eb367e1b`.
  The distributed APK is release-signed; the debug variant is only for local
  development.
* Android may show “unknown app” warnings when installing a release APK by
  `adb`; that is expected for a direct-distribution build.
* The portal is plain HTTP. The credentials are sent over the campus LAN in
  cleartext, which is a property of the portal, not the app. Use a password
  that is not reused elsewhere.
* The app pins the portal to `122.252.242.93`. If the university changes the
  portal IP, update `PORTAL_HOSTS` in `PortalClient.kt` and the cleartext
  allowlist.
