# Reverse-engineering the CURAJ CAMPUS CONNECT captive portal

**Target:** `CURAJ CAMPUS CONNECT` Wi-Fi, Central University of Rajasthan
**Date:** 2026-10-21
**Client:** Omarchy Linux, kernel `7.2.5-3-omarchy`, interface `wlp0s20f3`
**Portal:** `http://122.252.242.93/userportal/` (Inventum / RailWire)
**Goal:** replace the manual “mobile number + password” web form with an
automatic login on Linux and Android.

> Scope: this was done on the author’s own device with the author’s own
> account, to automate their own login. Nothing here bypasses authentication,
> forges a session, or attacks another user. The `sc` token is *given* by the
> portal; it is never guessed.

---

## 0. TL;DR

The portal is a plain-HTTP, four-request flow. There is no JavaScript crypto,
no CSRF token, and no CAPTCHA:

```
GET  http://connectivitycheck.gstatic.com/generate_204      → 204 means online
GET  http://122.252.242.93/userportal/?...&sc=...           → JSESSIONID
POST http://122.252.242.93/userportal/login.do?requesturi=  → SPA hop
POST http://122.252.242.93/userportal/newlogin.do
     username=<mobile>&password=<pw>&phone=0&type=2&jsonresponse=1
     → {"errorKey":"success","errorMessage":"success_net"}
```

The only non-obvious parts are:

1. **Route binding.** A second default route (USB tether) silently stole every
   request. The client must bind sockets to the Wi-Fi device
   (`SO_BINDTODEVICE` / Android `Network.openConnection`), not just the source
   address.
2. **Session context.** `newlogin.do` only works when the request carries the
   `JSESSIONID` created by the `userportal` request, which is bound to the
   `ip`/`mac`/`sc` parameters of the pre-auth redirect.
3. **Logout.** `GET /logout` and `/logout.do` without that cookie are no-ops.
   A real logout requires the session cookie that was bound by `newlogin.do`.

---

## 1. Starting state: which network is actually in use?

```bash
ip -brief addr
ip route
nmcli -t -f ACTIVE,SSID,SIGNAL dev wifi
```

Observed:

```
wlp0s20f3     UP   10.50.11.134/20
enp0s20f0u9   UP   10.250.236.145/24     # USB-tethered phone

default via 10.250.236.65 dev enp0s20f0u9 metric 100
default via 10.50.0.1     dev wlp0s20f3   metric 600
```

Two default routes. The tether has the lower metric, so **all** traffic —
including traffic to the campus portal — goes out the phone unless the socket
is explicitly bound to the Wi-Fi device.

This was the single biggest source of confusing results during the
investigation and is documented in §3.

| item | value |
|---|---|
| SSID | `CURAJ CAMPUS CONNECT` |
| Wi-Fi IP | `10.50.11.134/20` |
| Wi-Fi MAC | `74:04:f1:de:65:52` |
| Gateway (NAS) | `10.50.0.1` |
| Gateway MAC | `00:0c:29:64:cf:f8` (VMware OUI) |
| DHCP DNS | `1.1.1.1`, `1.0.0.1` |

The gateway MAC being a VMware OUI suggests the NAS/controller is a virtual
machine. Not important for the automation, but useful context.

---

## 2. Finding the interception point

The gateway does **not** serve a login page on port 80:

```bash
nmap -Pn -n -p 22,53,80,443,3128,8080,8443 10.50.0.1
```

```
PORT     STATE  SERVICE
22/tcp   closed ssh
53/tcp   open   domain
80/tcp   closed http
443/tcp  closed https
3128/tcp open   squid-http
```

Port **3128** is a transparent Squid proxy. Asking it for any plain-HTTP URL
returns the captive-portal bootstrap page:

```bash
curl -sS -x http://10.50.0.1:3128 http://example.com/
```

```html
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html><head><title>Please wait while the login page is loaded...</title>
<META HTTP-EQUIV="Refresh" CONTENT="1;URL=http://122.252.242.93/userportal/
 ?requesturi=http%3a%2f%2fexample%2ecom%2f%3f
 &ip=10%2e50%2e11%2e134
 &mac=74%3a04%3af1%3ade%3a65%3a52
 &nas=curaj
 &requestip=example%2ecom
 &sc=6d244db2d6b400d00d5f9fa1e00af476
 &interface=cpeth3
 &locationid=B5-FF-Roomno.126:CURAJ%20CAMPUS%20CONNECT">
...
<p align="center">Powered by <b>Inventum Technologies</b></p>
```

So the architecture is:

```
client ──HTTP──> 10.50.0.1:3128 (Squid, transparent)
                       │
                       └─ meta-refresh / 302 ──> 122.252.242.93:80
                                                  Apache 2.2.15 (CentOS)
                                                  Tomcat 5.5.23
                                                  Inventum / RailWire portal
```

`lighttpd/1.4.37` serves the splash/proxy page; the portal itself is Apache +
Tomcat, and Tomcat 5.5.23 leaks its version in error pages.

### Why the first probes timed out

```bash
curl --interface 10.50.11.134 http://example.com/    # timed out
ip route get 122.252.242.93 from 10.50.11.134
#   via 10.250.236.65 dev enp0s20f0u9   ← tether won
```

`--interface <ip>` only sets the source address; the route lookup still chose
the tether. The same request with the *device* name works:

```bash
curl --interface wlp0s20f3 http://example.com/
ip route get 122.252.242.93
#   via 10.50.0.1 dev wlp0s20f3
```

This is why every socket in the final client uses `SO_BINDTODEVICE`, and why
the Android client uses `Network.openConnection()` rather than a plain
`URL.openConnection()`.

---

## 3. Following the redirect chain

### 3.1 The `userportal` splash

`GET` the meta-refresh URL (with a cookie jar) and the portal responds:

```
HTTP/1.1 200 OK
Server: Apache/2.2.15 (CentOS)
Set-Cookie: JSESSIONID=3AFAD92493FCBE5A5BC57E7FAE591365; Path=/userportal
Content-Type: text/html;charset=ISO-8859-1
Content-Length: 501
```

The body is a tiny auto-submitting form:

```html
<script>
function loadForm(){
    document.forms[0].action="login.do?requesturi=http%3A%2F%2Fexample.com%2F";
    document.forms[0].method="post";
    document.forms[0].submit();
}
</script>
<body onload="moveToLogin();">
```

So the `JSESSIONID` is created on the `userportal` request. That session is
what carries the `ip` / `mac` / `sc` context into later calls.

### 3.2 `login.do`

`POST /userportal/login.do?requesturi=...` (empty body, same cookie) returns:

```html
<script>
    window.location = "/userportal/pages/usermedia/curaj/app/campus/ui/index.html";
</script>
```

### 3.3 The SPA

The UI is a static jQuery site, not a modern SPA framework:

```
/userportal/pages/usermedia/curaj/app/campus/ui/
├── index.html            “Welcome to Central University Rajasthan Wi-Fi”
├── campus-flow.html      “New User Login” / “Existing User Login”
├── login.html            ← the form we care about
├── logout.html           “Your Internet session is started.”
├── login.jsp             self-care (type=1)
├── voucher-login.html    new-user/voucher path (not reversed)
├── forgot.html
└── js/{jquery-1.12.0.min.js,jquery.form.js,custom.js,...}
```

`campus-flow.html` links to `login.html` (“Existing User Login”) and
`voucher-login.html` (“New User Login”). Only `login.html` is needed for an
existing account.

---

## 4. The login form

`login.html` contains exactly this:

```html
<form name="form1" id="form1" method="POST" action="/userportal/newlogin.do">
    <input type="text" name="username"
           placeholder="Enter your mobile number / Username" maxlength="10" required>
    <input type="password" name="password" placeholder="Enter your password">
    <input type="hidden" name="phone" value="0">
    <input type="hidden" name="type" value="2">
    <input type="hidden" name="jsonresponse" value="1">
</form>
```

and this result handler:

```js
$('#form1').ajaxForm(function(data) {
    if (data.errorMessage == "success_net" && data.errorKey == "success") {
        window.location = "logout.html";
    } else {
        alert(data.errorMessage);
    }
});
```

So a successful login is signalled by **both** keys:

```json
{"errorKey":"success","errorMessage":"success_net"}
```

`maxlength="10"` confirms the username is the 10-digit mobile number.

The self-care page (`login.jsp`) is a different code path
(`type=1`, `phone=1`, `generatePassword=true`) and is not required for
network access.

---

## 5. Characterising `newlogin.do`

There is no CSRF token and no nonce in the form. The only state is the
`JSESSIONID` cookie.

Probe matrix:

| request | response |
|---|---|
| `GET /userportal/newlogin.do` | `500` Tomcat error page |
| `POST` with no session/`sc` | `{"errorKey":"redirect_to_nas"}` |
| `POST` with valid `sc` session, device already online | `{"errorMessage":"Session already running","errorKey":"failureCase"}` |
| `POST` with valid `sc` session, device offline | `{"errorMessage":"success_net","errorKey":"success"}` |

Response content type is `text/json;charset=ISO-8859-1`; the JSON key order
varies between responses, so parse by key, not position.

Wrong-password behaviour was **not tested** to avoid locking the account. The
client surfaces whatever `errorMessage` the portal returns.

### What “valid `sc` session” means

```
GET /userportal/?requesturi=...&ip=...&mac=...&nas=curaj&sc=...
    → Set-Cookie: JSESSIONID=<id>
POST /userportal/newlogin.do  with  Cookie: JSESSIONID=<id>
```

The `sc`, `ip` and `mac` are not sent in the POST body; the server has them in
the session created by the GET. That is why an isolated POST returns
`redirect_to_nas` even with correct credentials.

---

## 6. Logout semantics (and a trap)

| request | result |
|---|---|
| `GET /userportal/logout.do` with no cookie | `302 http://1.254.254.254?N=<epoch-ms>` — **no deauth** |
| `GET http://1.254.254.254/logout` | `200`, empty body — **no deauth** |
| `GET /userportal/logout.do` with the bound `JSESSIONID` | `200`, session torn down |

So a logout is only effective when it carries the cookie that `newlogin.do`
bound to the live NAS session. Because the browser normally has that cookie,
the web UI’s logout button works; a bare `curl` does not.

`http://1.254.254.254/` is the NAS splash. It answers in **both** auth states
and redirects to the portal with a fresh `sc`, which makes it the reliable
place to discover the portal when you are already online.

The final client therefore does, for `curaj-login logout`:

```
1. GET  http://1.254.254.254/            (works while online)
2. parse portal URL + sc
3. GET  portal URL                       → JSESSIONID
4. POST newlogin.do with credentials     → binds the cookie
      (answer is usually “Session already running”)
5. GET  logout.do with Cookie: JSESSIONID → real logout
6. GET  http://1.254.254.254/logout       (best-effort NAS ack)
7. verify generate_204 is no longer 204
```

Step 4 is counter-intuitive but necessary: it is the call that associates the
fresh `JSESSIONID` with the NAS-side session, so step 5 has something to tear
down.

### 6.1 Closing one of two sessions

`GET /userportal/currentSessions.do?from=Home` lists the active sessions and
puts `callClose(<sesno>)` on each row. The browser’s **Close** button submits an
empty **POST**:

```http
POST /userportal/logout.do?from=curses&sesno=<sesno>
Cookie: JSESSIONID=<splash-context>
Content-Type: application/x-www-form-urlencoded
Referer: http://122.252.242.93/userportal/currentSessions.do?from=Home
Origin: http://122.252.242.93
Content-Length: 0
```

The cookie matters. A self-care login started directly at `login.jsp` yields a
cookie that can list sessions, but the same close request returns:

```http
HTTP/1.1 302 Found
Location: http://1.254.254.254?N=<epoch-ms>
```

and the session survives. The browser works because its `JSESSIONID` came from
the portal splash URL, which binds `ip`/`mac`/`nas`/`sc`. With that context
cookie the close returns `200`, the page says “Your Session Has Been Stopped”,
and the next `currentSessions.do` no longer contains the id.

So the session-management flow is:

```
1. GET  splash URL (portal URL from 1.254.254.254/login if online)
2. keep the context JSESSIONID
3. POST newlogin.do  type=1  (self-care; lists but does not consume a slot)
4. GET  currentSessions.do   (parse callClose ids)
5. POST logout.do?from=curses&sesno=<id>   (empty body, context cookie)
6. GET  currentSessions.do   (verify the id is gone)
```

Do not treat the `302` as success; both an invalid id and an unauthorised
cookie produce it.

---

## 7. The `sc` token

Observed value, stable across the whole investigation:

```
sc=6d244db2d6b400d00d5f9fa1e00af476
```

Properties:

* 32 lowercase hex characters — MD5-shaped, but **not** a simple hash of the
  obvious fields. A brute-force over permutations of IP, MAC (with/without
  colons, upper/lower), `nas`, `interface`, portal IP, AP names, and separators
  found no match.
* It stayed identical across separate logins, separate `JSESSIONID`s, and at
  least 33 minutes of wall time, for the same MAC/IP.
* It is therefore best modelled as a **stored per-device token with a long
  TTL**, not a per-session nonce.
* Different APs change `locationid` (e.g. `B5-FF-Roomno.126:...` vs
  `B5-SF-RM-226-AP-:...`) but did not change `sc`.
* The client never derives it: it is parsed from the redirect each time.
  That also means a portal change that renames the parameter only requires
  updating the parser.

Not established: whether `sc` changes after a long idle period, after a MAC
change, or after an administrator resets the device. The client’s “parse what
the NAS gives you” design is immune to all three.

---

## 8. Building the client

### 8.1 The algorithm

```
if not associated with SSID:
    sleep

r = GET generate_204 bound to the Wi-Fi device
if r.status == 204:
    online; done

portal = parse(Location | meta-refresh | JS redirect | /userportal/?... regex)
if portal is 1.254.254.254:
    portal = parse(GET 1.254.254.254)

splash  = GET portal                      # sets JSESSIONID
cookie  = JSESSIONID from Set-Cookie
POST login.do?requesturi=<original>       # browser parity; optional but harmless
POST newlogin.do
      Cookie: JSESSIONID
      Content-Type: application/x-www-form-urlencoded
      username, password, phone=0, type=2, jsonresponse=1

if errorKey == success or errorMessage == success_net:
    poll generate_204 until 204
elif redirect_to_nas or “Session already running”:
    logout with the cookie, retry once
else:
    report the portal’s errorMessage
```

### 8.2 Why bind the device at the socket level

Linux:

```python
sock.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, b"wlp0s20f3")
```

Android:

```kotlin
val conn = network.openConnection(URL(url)) as HttpURLConnection
```

Both make the kernel/OS choose the Wi-Fi interface for that connection,
regardless of the default route or an active mobile-data connection.

### 8.3 Edge cases handled

| case | handling |
|---|---|
| no Wi-Fi / wrong SSID | auto-detect interface by SSID; skip otherwise |
| already online | `generate_204` = 204 → no-op |
| portal at `1.254.254.254` | follow the splash hop once |
| portal URL has a port | use `netloc`/`authority`, not just hostname |
| stale half-session | `redirect_to_nas` / “Session already running” → logout + one retry |
| transient NAS blackhole after logout | probe retries once; daemon backs off |
| portal success but no internet yet | poll `generate_204` up to 6 × 1.5 s |
| service restarts | systemd `Restart=always`; `watch` loop never exits on error |
| logout while service runs | service re-logs in within 60 s (by design) |

---

## 9. Live verification

All timestamps IST, 2026-10-21.

```
04:35  first pre-auth redirect captured (sc=6d24…f476)
04:49  real credentials stored in ~/.config/curaj-wifi/config.json (0600)
04:50  logout.do with a bound cookie → Wi-Fi blocked
04:50  curaj-login once → success_net / success in 2.7 s
       generate_204 → 204, https://example.com → 200
04:51  systemd user service enabled and started
04:51:51  forced logout
04:52:24  daemon: captive portal detected
04:52:30  first probe timed out (NAS transition) → backoff
04:52:50  retry: portal → newlogin.do
04:52:51  portal says success_net / success
04:52:52  daemon: OK: logged in
05:07  logout re-tested with the cookie-aware implementation
       logged out: session: JSESSIONID; logout.do -> 200; nas/logout -> 200
05:08:06  daemon re-logged in automatically after the logout
```

The 04:52:30 timeout is a real-world condition (the NAS blackholes traffic
briefly while tearing down/creating a session). It is why the client retries
instead of treating one failed probe as fatal.

---

## 10. Endpoint reference

### 10.1 Discovery / interception

| method | URL | notes |
|---|---|---|
| `GET` | `http://connectivitycheck.gstatic.com/generate_204` | `204` = online; `200` + HTML = captive |
| `GET` | `http://10.50.0.1:3128` | Squid; transparent proxy for HTTP |
| `GET` | `http://1.254.254.254/` | NAS splash, works online and offline |
| `GET` | `http://1.254.254.254/login` | same splash |
| `GET` | `http://1.254.254.254/logout` | `200` empty; **does not deauth** |

### 10.2 Portal

Base: `http://122.252.242.93/userportal`

| method | path | purpose |
|---|---|---|
| `GET` | `/?requesturi=...&ip=...&mac=...&nas=curaj&sc=...&interface=cpeth3&locationid=...` | create session, return auto-post page + `JSESSIONID` |
| `POST` | `/login.do?requesturi=<urlencoded>` | browser parity; returns JS redirect to the SPA |
| `POST` | `/newlogin.do` | credential check; the real login |
| `GET` | `/logout.do` | real logout of this device **with** the cookie bound by `newlogin.do` |
| `GET` | `/currentSessions.do?from=Home` | list active sessions; each row has `callClose(<sesno>)` |
| `POST` | `/logout.do?from=curses&sesno=<id>` | close one chosen session; needs the **splash-context** cookie, empty body |
| `GET` | `/pages/usermedia/curaj/app/campus/ui/index.html` | SPA home |
| `GET` | `/pages/usermedia/curaj/app/campus/ui/campus-flow.html` | login-type chooser |
| `GET` | `/pages/usermedia/curaj/app/campus/ui/login.html` | the form |
| `GET` | `/pages/usermedia/curaj/app/campus/ui/logout.html` | “session started” page |
| `GET` | `/pages/usermedia/curaj/app/campus/ui/login.jsp` | self-care (`type=1`) |
| `GET` | `/pages/usermedia/curaj/app/campus/ui/voucher-login.html` | new-user/voucher (not reversed) |

### 10.3 `newlogin.do` contract

Request:

```http
POST /userportal/newlogin.do HTTP/1.1
Host: 122.252.242.93
Cookie: JSESSIONID=<from userportal GET>
Content-Type: application/x-www-form-urlencoded
Referer: http://122.252.242.93/userportal/pages/usermedia/curaj/app/campus/ui/login.html
Origin: http://122.252.242.93

username=<mobile>&password=<password>&phone=0&type=2&jsonresponse=1
```

Responses:

```json
{"errorKey":"success","errorMessage":"success_net"}
{"errorKey":"redirect_to_nas"}
{"errorKey":"failureCase","errorMessage":"Session already running"}
{"errorKey":"failureCase","errorMessage":"Maximum session allowed reached. your subscription allow a maximum of 2 sessiosn"}
```

### 10.4 Redirect parameters

| param | example | meaning |
|---|---|---|
| `requesturi` | `http://example.com/` | originally requested URL |
| `ip` | `10.50.11.134` | client IP seen by the NAS |
| `mac` | `74:04:f1:de:65:52` | client MAC |
| `nas` | `curaj` | NAS / realm name |
| `requestip` | `example.com` | host of the original request |
| `sc` | `6d24…f476` | per-device session token |
| `interface` | `cpeth3` | NAS captive interface |
| `locationid` | `B5-FF-Roomno.126:CURAJ CAMPUS CONNECT` | AP / room label |

### 10.5 State machine

```
                 generate_204 == 204
        ┌──────────────────────────────┐
        │                              ▼
    ┌───────┐   pre-auth HTTP      ┌────────┐
    │ BLOCK │ ───────────────────► │ ONLINE │
    └───────┘   meta/302 + sc      └────────┘
        │                              ▲
        │ GET userportal (JSESSIONID)  │ newlogin.do success
        ▼                              │ + NAS gate opens
    ┌──────────┐   newlogin.do    ┌──────────┐
    │ PORTAL   │ ───────────────► │ VERIFY   │
    │ SESSION  │                  │ 204 poll │
    └──────────┘                  └──────────┘
```

---

## 11. Security findings

1. **Credentials are sent over plain HTTP.**
   `POST http://122.252.242.93/userportal/newlogin.do` contains the password
   in the body, unencrypted. Anyone on the path can read it. Use a password
   unique to this portal and report this to the university network team.
2. **The server stack is EOL.**
   Apache `2.2.15` (2010), Tomcat `5.5.23` (2007), lighttpd `1.4.37`, jQuery
   `1.12.0`. Tomcat leaks its version in error pages.
3. **No CSRF token / no nonce.**
   The form relies solely on the `JSESSIONID`; `sc` is a device token, not a
   CSRF defence.
4. **`logout.do` is state-changing.** Logging out this device uses a GET with
   the bound cookie; closing one chosen session uses a POST with the
   splash-context cookie.
5. **`sc` is stable and long-lived.**
   It is not a one-time nonce; it identifies the device for an extended
   period. It is not sufficient on its own to log in (credentials are still
   required), but it is a stable device identifier.
6. **The portal is internet-facing.**
   `122.252.242.93` was reachable over a phone tether, not only from the
   campus subnet.
7. **Both clients pin the portal host.**
   A `/userportal` URL whose host is not `122.252.242.93` is rejected before
   any credential is sent. Without that guard, a hostile Wi-Fi could serve a
   fake portal page and harvest the password.

Not assessed: rate limiting, account lockout policy, whether the portal
enforces HTTPS elsewhere, or whether session tokens are rotated.

---

## 12. Unknowns / what to re-check if it breaks

| unknown | how to re-derive |
|---|---|
| `sc` derivation | not needed; parse it from the redirect |
| session TTL | watch for `generate_204` returning `200`; the daemon re-logs in |
| concurrent logins per account | log in on two devices and observe `Session already running` |
| wrong-password response | test once with a deliberately wrong password (risk of lockout) |
| MAC randomisation | if the phone uses a random MAC, the `sc`/session identity changes; disable random MAC for this SSID |
| portal vendor change | re-run §2; update `PORTAL_URL_RE` and the endpoint table |

If the portal changes, the first three commands to run are:

```bash
ip route get 122.252.242.93
curl -sS --interface wlp0s20f3 --noproxy '*' -D - http://example.com/
curl -sS -x http://10.50.0.1:3128 -D - http://example.com/
```

---

## 13. Reproduction cookbook

```bash
# 0. confirm which route actually carries traffic
ip route get 122.252.242.93

# 1. pre-auth probe, bound to the Wi-Fi device (not the source IP)
curl -sS --interface wlp0s20f3 --noproxy '*' -D - \
     http://connectivitycheck.gstatic.com/generate_204

# 2. see the interception page through Squid
curl -sS -x http://10.50.0.1:3128 -D - http://example.com/

# 3. open the portal session (use the URL from step 1/2)
curl -sS --interface wlp0s20f3 --noproxy '*' -c /tmp/jar -b /tmp/jar \
     -D - 'http://122.252.242.93/userportal/?...&sc=...'

# 4. log in
curl -sS --interface wlp0s20f3 --noproxy '*' -c /tmp/jar -b /tmp/jar \
     -X POST 'http://122.252.242.93/userportal/newlogin.do' \
     --data-urlencode 'username=<mobile>' \
     --data-urlencode 'password=<password>' \
     --data 'phone=0&type=2&jsonresponse=1'

# 5. real logout (needs the cookie from step 3)
curl -sS --interface wlp0s20f3 --noproxy '*' -b /tmp/jar \
     'http://122.252.242.93/userportal/logout.do'
```

---

## 14. Appendix: raw evidence

See [EVIDENCE.md](EVIDENCE.md) for the unedited headers, bodies, JSON and
service logs (credentials redacted).

Quick reference card: [ENDPOINTS.md](ENDPOINTS.md).
