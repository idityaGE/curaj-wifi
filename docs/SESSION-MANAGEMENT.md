# Session management

The account is allowed **two simultaneous internet sessions**. When both slots
are used, `newlogin.do` returns:

```json
{"errorKey":"failureCase","errorMessage":"Maximum session allowed reached. your subscription allow a maximum of 2 sessiosn"}
```

The clients deliberately do **not** kick another session automatically. They
report the limit, list the sessions, and let the user choose which one to close.
After a successful close, the normal login is retried.

This document covers the portal flow that makes that possible.

---

## 1. List sessions

`GET /userportal/currentSessions.do?from=Home` returns an HTML table. Each row
contains a button like:

```html
<input type="button" value="Close" onclick="callClose(137706022);">
```

The number in `callClose(...)` is the session id (`sesno`). The table fields
are:

| field | example |
|---|---|
| name | `ADITYA MAURYA` |
| account id | `9935643687` |
| rate plan | `CURAJ users Rate plan` |
| net id | `9935643687` |
| start time | `2026-10-21 20:37:25` |
| duration | `00:40:49` |
| upload | `45.1MB` |
| download | `23.66MB` |
| total | `68.75MB` |

The Linux client parses the table with a small regex parser (no HTML
dependency). Android uses the same row/`callClose` shape.

---

## 2. Self-care login (`type=1`)

The normal internet login uses `type=2`. The account-management page uses a
different login mode:

```http
POST /userportal/newlogin.do HTTP/1.1
Host: 122.252.242.93
Cookie: JSESSIONID=<session>
Content-Type: application/x-www-form-urlencoded
Referer: http://122.252.242.93/userportal/pages/usermedia/curaj/app/campus/ui/login.jsp
Origin: http://122.252.242.93

generatePassword=true&phone=0&type=1&username=<mobile>&password=<password>
```

Important details:

* The form on `login.jsp` initially has `phone=1`, but its `start()` JavaScript
  changes it to `0` before submit. `phone=0` is correct for the POST.
* `type=1` opens a **management** session. It can list sessions even when the
  account already has two internet sessions, and it does **not** consume a
  third slot.
* The response is the self-care frameset (`Unify User Portal`), not a JSON
  success object.

A self-care cookie alone is enough to **list** sessions, but not to close one.

---

## 3. Why the direct self-care cookie cannot close a session

Reproduced live on 2026-10-21:

1. `GET /userportal/pages/usermedia/curaj/app/campus/ui/login.jsp`
   → `JSESSIONID=<self-care>`.
2. `POST newlogin.do` with `type=1` → list works.
3. `POST /userportal/logout.do?from=curses&sesno=<id>` with that cookie:

```http
HTTP/1.1 302 Found
Location: http://1.254.254.254?N=1792595291001
```

The session remained active. Re-listing showed the same `sesno`. The same
thing happened with GET, query/body variants, and following the
`1.254.254.254` redirect.

The browser's **Close** button does work. Capturing the browser request showed
the difference: its `JSESSIONID` came from the **portal splash URL**, not from
a direct self-care page load.

```http
GET /userportal/?requesturi=...&ip=10.50.8.56&mac=74:04:f1:de:65:52
    &nas=curaj&requestip=...&sc=<token>&interface=cpeth3&locationid=...
Host: 122.252.242.93
```

That response sets a `JSESSIONID` bound to the client's `ip`/`mac`/`nas`/`sc`
context. Using that cookie for the self-care login and then the close request
returns `200` and the session disappears.

### Working request

```http
POST /userportal/logout.do?from=curses&sesno=137706022 HTTP/1.1
Host: 122.252.242.93
Cookie: JSESSIONID=<splash-context>
Content-Type: application/x-www-form-urlencoded
Referer: http://122.252.242.93/userportal/currentSessions.do?from=Home
Origin: http://122.252.242.93
Content-Length: 0
```

```http
HTTP/1.1 200 OK
Server: Apache/2.2.15 (CentOS)
Content-Type: text/html;charset=ISO-8859-1
```

The response page contains:

```text
Current Session
Nothing Found to Display
Subscription List
Your Session Has Been Stopped
```

The browser sends an empty form body. The request must be a **POST**; a bare
GET is not the same operation.

---

## 4. Success must be verified

A `302` to `http://1.254.254.254?N=<epoch-ms>` is **not** success. Invalid,
expired, and unauthorized session ids all produce it. Both clients therefore:

1. close the chosen session;
2. re-fetch `currentSessions.do`;
3. report success only if the `sesno` is gone from the table.

The previous fallback that treated `302` as success was removed after it
reported a close that had not happened.

---

## 5. Linux CLI

```bash
# list active sessions
curaj-login sessions

# machine-readable
curaj-login sessions --json

# close one chosen session, then retry normal login if the device is blocked
curaj-login sessions --logout 137706022

# close one session but do not log back in
curaj-login sessions --logout 137706022 --no-login
```

If `newlogin.do` hits the two-session cap, `curaj-login once` prints the limit
and suggests `curaj-login sessions`. The `watch` service posts one desktop
notification (when `notify-send` is available), lists the ids in the journal,
and does not kick anything by itself.

The code path is:

```text
portal_url_any()
  -> GET splash URL                    (context JSESSIONID)
  -> POST newlogin.do  type=1          (self-care)
  -> GET currentSessions.do            (list / parse)
  -> POST logout.do?from=curses&sesno  (close one)
  -> GET currentSessions.do            (verify)
```

---

## 6. Android app

The main screen has a **Sessions** button. It requires saved credentials and a
Wi-Fi network, then:

1. opens the splash context cookie;
2. performs the `type=1` self-care login;
3. lists the active sessions in a scrollable dialog;
4. asks for confirmation before closing one;
5. verifies the session is gone;
6. if auto-login is enabled, starts a normal login attempt again.

When the background service sees the two-session limit it posts a **separate**
high-priority, one-shot notification:

```text
Session limit reached
2 sessions are active. Log out one to reconnect.
[Manage sessions]
```

The foreground notification remains low-priority and silent. Tapping **Manage
sessions** opens the same dialog. Only the session the user selects is closed.

---

## 7. Failure modes

| symptom | meaning | fix |
|---|---|---|
| `302 http://1.254.254.254?N=...`, id still listed | request lacked the splash/context cookie, or the id is invalid | obtain the splash `JSESSIONID` first; verify by re-listing |
| `self-care login issued no JSESSIONID` | portal not reachable, or NAS splash unavailable | check Wi-Fi/portal; retry |
| `session <id> is not active` | list changed between fetch and close | refresh and choose again |
| list shows `Session has been expired` | self-care cookie expired | client re-opens it automatically; if it persists, check the saved password |
| close succeeded but device is still offline | the closed session was this device, or the NAS needs a moment | client retries normal login; wait a few seconds |
| portal lists zero sessions but login still fails | portal/NAS inconsistency | retry once; if it persists, check the account |

---

## 8. Security notes

* The self-care password is the same campus password, sent over **plain HTTP**.
  Treat it as exposed to anyone on the network path.
* The `JSESSIONID` is a bearer token. The clients keep it in memory only and
  never write it to the config or logs.
* The context cookie is not a credential, but it can close sessions. Do not
  paste it into issue reports.
* The clients never close a session without an explicit user choice. The only
  automatic cleanup is the pre-existing `redirect_to_nas` stale-half-session
  recovery, which targets the current device's own session.
