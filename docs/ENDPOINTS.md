# Endpoint reference card

All values below were observed live on 2026-10-21. `<mobile>` / `<password>`
are placeholders.

```
NAS / captive gateway : 10.50.0.1
  DNS                 : 10.50.0.1:53
  Squid (transparent) : 10.50.0.1:3128
  splash / logout     : http://1.254.254.254/
portal                : http://122.252.242.93/userportal
vendor                : Inventum Technologies (RailWire)
server                : Apache/2.2.15 (CentOS) + Tomcat/5.5.23
```

---

## 1. Pre-auth probe

```http
GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1
Host: connectivitycheck.gstatic.com
```

* `204 No Content` → authenticated.
* `200` + HTML containing a meta refresh → captive.
* `302` + `Location: http://1.254.254.254` → captive.

> **Changed 2026-10-27 (post-auth):** with an *origin-form* request line
> (`GET /generate_204`), an authenticated device now gets
> `400 ERR_INVALID_URL` from the gateway Squid (`squid/7.3-VCS`). Use the
> *proxy-form* (absolute-URI) request line shown above: it returns the splash
> page pre-auth and `204` post-auth. Both clients (Linux + Android) send the
> probe this way since 1.6.1.

The client must connect through the Wi-Fi interface
(`SO_BINDTODEVICE` / Android `Network`), otherwise a tether/mobile default
route can hide the portal.

---

## 2. NAS splash

```http
GET / HTTP/1.1
Host: 1.254.254.254
```

`200` in **both** auth states. Body contains:

```html
<META HTTP-EQUIV="Refresh" CONTENT="1;URL=http://122.252.242.93/userportal/
 ?requesturi=...&ip=...&mac=...&nas=curaj&requestip=...&sc=...&interface=cpeth3
 &locationid=...">
```

Also:

| path | response | effect |
|---|---|---|
| `/login` | same splash | — |
| `/logout` | `200`, empty | **no deauth** |

---

## 3. Portal session

```http
GET /userportal/?requesturi=<url>&ip=<ip>&mac=<mac>&nas=curaj
    &requestip=<host>&sc=<32-hex>&interface=cpeth3&locationid=<ap>
Host: 122.252.242.93
```

```http
HTTP/1.1 200 OK
Server: Apache/2.2.15 (CentOS)
Set-Cookie: JSESSIONID=<id>; Path=/userportal
Content-Type: text/html;charset=ISO-8859-1
```

Body: auto-submitting form to `login.do?requesturi=...`.

Query parameters:

| name | example | notes |
|---|---|---|
| `requesturi` | `http://example.com/` | URL originally requested |
| `ip` | `10.50.11.134` | client IP |
| `mac` | `74:04:f1:de:65:52` | client MAC |
| `nas` | `curaj` | realm |
| `requestip` | `example.com` | host of original request |
| `sc` | `6d244db2d6b400d00d5f9fa1e00af476` | per-device token |
| `interface` | `cpeth3` | NAS captive interface |
| `locationid` | `B5-FF-Roomno.126:CURAJ CAMPUS CONNECT` | AP label |

`sc` does not need to be generated. It is stable for a given MAC/IP and was
not reproducible from a simple hash of the visible fields.

---

## 4. `login.do`

```http
POST /userportal/login.do?requesturi=http%3A%2F%2Fexample.com%2F HTTP/1.1
Host: 122.252.242.93
Cookie: JSESSIONID=<id>
Content-Length: 0
```

```html
<script>
window.location = "/userportal/pages/usermedia/curaj/app/campus/ui/index.html";
</script>
```

Browser-parity hop. Not strictly required, but cheap and harmless.

---

## 5. `newlogin.do` — the real login

```http
POST /userportal/newlogin.do HTTP/1.1
Host: 122.252.242.93
Cookie: JSESSIONID=<id>
Content-Type: application/x-www-form-urlencoded
Referer: http://122.252.242.93/userportal/pages/usermedia/curaj/app/campus/ui/login.html
Origin: http://122.252.242.93

username=<mobile>&password=<password>&phone=0&type=2&jsonresponse=1
```

Form fields (from `login.html`):

| field | value | notes |
|---|---|---|
| `username` | 10-digit mobile | `maxlength="10"` |
| `password` | portal password | plain HTTP |
| `phone` | `0` | |
| `type` | `2` | campus user |
| `jsonresponse` | `1` | return JSON instead of HTML |

Response (`Content-Type: text/json;charset=ISO-8859-1`):

| body | meaning |
|---|---|
| `{"errorKey":"success","errorMessage":"success_net"}` | success |
| `{"errorKey":"redirect_to_nas"}` | no bound session / wrong context |
| `{"errorKey":"failureCase","errorMessage":"Session already running"}` | NAS already holds this device |
| `500` Tomcat page | GET instead of POST |

Success requires **both** `errorKey == "success"` and
`errorMessage == "success_net"` (the page checks both).

No CSRF token, no nonce, no CAPTCHA.

---

## 6. Session management

The account allows **two** simultaneous internet sessions. When both are in
use, `newlogin.do` returns the “Maximum session allowed reached” failure. The
clients list the sessions and close only the one the user chooses.

### 6.1 List sessions

```http
GET /userportal/currentSessions.do?from=Home HTTP/1.1
Host: 122.252.242.93
Cookie: JSESSIONID=<self-care>
```

Each row has `callClose(<sesno>)`. The table fields are name, account id, rate
plan, net id, start time, duration, upload, download, and total bytes.

### 6.2 Self-care login (`type=1`)

```http
POST /userportal/newlogin.do HTTP/1.1
Host: 122.252.242.93
Cookie: JSESSIONID=<splash-context>
Content-Type: application/x-www-form-urlencoded
Referer: http://122.252.242.93/userportal/pages/usermedia/curaj/app/campus/ui/login.jsp
Origin: http://122.252.242.93

generatePassword=true&phone=0&type=1&username=<mobile>&password=<password>
```

This is the account-management login, not the internet login (`type=2`). It
does not consume a third session. The `login.jsp` form starts with `phone=1`
but its `start()` JavaScript changes it to `0`; `phone=0` is correct for the
POST.

### 6.3 Close one chosen session

```http
POST /userportal/logout.do?from=curses&sesno=<sesno> HTTP/1.1
Host: 122.252.242.93
Cookie: JSESSIONID=<splash-context>
Content-Type: application/x-www-form-urlencoded
Referer: http://122.252.242.93/userportal/currentSessions.do?from=Home
Origin: http://122.252.242.93
Content-Length: 0
```

| condition | result |
|---|---|
| valid id + **splash-context** cookie | `200`, “Your Session Has Been Stopped”, session gone |
| valid id + direct self-care cookie | `302 http://1.254.254.254?N=<epoch-ms>`, **session survives** |
| invalid/expired id | same `302`, no close |

The splash-context cookie is obtained by `GET`ting the portal URL
(`/userportal/?...&ip=...&mac=...&sc=...`) before the self-care login. A direct
`GET login.jsp` cookie can list sessions but is not sufficient to close one.
Verify success by fetching `currentSessions.do` again and checking that the
`sesno` disappeared; never trust the `302` alone.

### 6.4 Log out this device

```http
GET /userportal/logout.do HTTP/1.1
Host: 122.252.242.93
Cookie: JSESSIONID=<id bound by newlogin.do>
```

| condition | result |
|---|---|
| with cookie bound by `newlogin.do` | `200`, real deauth |
| without a bound cookie | `302 http://1.254.254.254?N=<epoch-ms>`, no deauth |

To get a bound cookie while already online: `GET 1.254.254.254/login` → portal
URL → `GET` it for a `JSESSIONID` → `POST newlogin.do` (usually answers
“Session already running”) → `GET logout.do` with the cookie.

---

## 7. SPA paths

Base: `http://122.252.242.93/userportal/pages/usermedia/curaj/app/campus/ui/`

| path | purpose |
|---|---|
| `index.html` | landing page |
| `campus-flow.html` | New User / Existing User chooser |
| `login.html` | **existing user form** (target) |
| `logout.html` | “Your Internet session is started.” |
| `login.jsp` | self-care form (`type=1`, `phone=1`) |
| `voucher-login.html` | new user / voucher (not reversed) |
| `forgot.html` | password reset |
| `js/jquery-1.12.0.min.js`, `js/jquery.form.js` | page JS |

---

## 8. Client checklist

1. Bind every socket to the Wi-Fi device.
2. Probe `generate_204`; stop if `204`.
3. Parse the portal URL from `Location`, meta refresh, JS redirect, or the
   `/userportal/?...` regex.
4. Follow one `1.254.254.254` hop if present.
5. `GET` the portal URL; keep `JSESSIONID`.
6. `POST login.do` (optional).
7. `POST newlogin.do` with the five fields.
8. On `redirect_to_nas` / “Session already running”, logout with the cookie
   and retry once.
9. On “Maximum session allowed reached”, list `currentSessions.do`, let the
   user choose one, and close it with a **POST** carrying the splash-context
   cookie. Verify with a second list; do not trust a `302`.
10. Verify with `generate_204` (poll ~6 × 1.5 s).
11. Use `netloc`/`authority` (host **and** port), not just hostname.
