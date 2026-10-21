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
GET /generate_204 HTTP/1.1
Host: connectivitycheck.gstatic.com
```

* `204 No Content` → authenticated.
* `200` + HTML containing a meta refresh → captive.
* `302` + `Location: http://1.254.254.254` → captive.

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

## 6. `logout.do`

```http
GET /userportal/logout.do HTTP/1.1
Host: 122.252.242.93
Cookie: JSESSIONID=<id>
```

| condition | result |
|---|---|
| with bound cookie | `200`, real deauth |
| without cookie | `302 http://1.254.254.254?N=<epoch-ms>`, no deauth |

To get a bound cookie while already online: `GET 1.254.254.254/` → portal URL
→ `GET` it for a `JSESSIONID` → `POST newlogin.do` (usually answers
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
9. Verify with `generate_204` (poll ~6 × 1.5 s).
10. Use `netloc`/`authority` (host **and** port), not just hostname.
