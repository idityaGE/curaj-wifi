# Evidence log

Raw observations from the live investigation, 2026-10-21. Credentials are
redacted; the real ones live only in `~/.config/curaj-wifi/config.json`
(mode 600) and are not part of this repository.

---

## 1. Interfaces and routes

```text
$ ip -brief addr
lo               UNKNOWN  127.0.0.1/8 ::1/128
wlp0s20f3        UP       10.50.11.134/20 fe80::a6e5:40c6:f12f:55ee/64
enp0s20f0u9      UNKNOWN  10.250.236.145/24 2409:...:ef88:9b0c/64

$ ip route
default via 10.250.236.65 dev enp0s20f0u9 proto dhcp src 10.250.236.145 metric 100
default via 10.50.0.1     dev wlp0s20f3   proto dhcp src 10.50.11.134  metric 600
10.50.0.0/20 dev wlp0s20f3 proto kernel scope link src 10.50.11.134 metric 600
10.250.236.0/24 dev enp0s20f0u9 proto kernel scope link src 10.250.236.145 metric 100
```

The tether wins the default route. `ip route get` from the Wi-Fi source
address still selected the tether:

```text
$ ip route get 122.252.242.93 from 10.50.11.134
122.252.242.93 from 10.50.11.134 via 10.250.236.65 dev enp0s20f0u9
```

After binding the *device* (`--interface wlp0s20f3`) the Wi-Fi path was used.

---

## 2. Gateway scan

```text
$ nmap -Pn -n --host-timeout 30s \
       -p 22,53,80,443,8080,8081,8443,8000,8888,3128 10.50.0.1

PORT     STATE  SERVICE
22/tcp   closed ssh
53/tcp   open   domain
80/tcp   closed http
443/tcp  closed https
3128/tcp open   squid-http
8000/tcp closed http-alt
8080/tcp closed http-proxy
8081/tcp closed blackice-icecap
8443/tcp closed https-alt
8888/tcp closed sun-answerbook
```

`ip neigh`: `10.50.0.1 lladdr 00:0c:29:64:cf:f8 REACHABLE` (VMware OUI).

---

## 3. Squid interception page

```text
$ curl -sS -x http://10.50.0.1:3128 -D - http://example.com/

HTTP/1.1 200 OK
Content-Type: text/html
connection: close
Pragma: no-cache
Transfer-Encoding: chunked
Server: lighttpd/1.4.37
```

Body (798 bytes):

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
 &locationid=B5-FF-Roomno.126:CURAJ CAMPUS CONNECT"></head>
<body><p align="center">Please wait...</p>
<p align="center">Please wait while the login page is loaded...</p>
<p align="center">Powered by <b>Inventum Technologies</b></p></body></html>
```

---

## 4. Portal session (`userportal`)

Request (URL-encoded, spaces as `%20`):

```text
GET http://122.252.242.93/userportal/?requesturi=http%3a%2f%2fexample%2ecom%2f%3f
    &ip=10%2e50%2e11%2e134
    &mac=74%3a04%3af1%3ade%3a65%3a52
    &nas=curaj
    &requestip=example%2ecom
    &sc=6d244db2d6b400d00d5f9fa1e00af476
    &interface=cpeth3
    &locationid=B5-FF-Roomno.126:CURAJ%20CAMPUS%20CONNECT
```

Response:

```text
HTTP/1.1 200 OK
Date: Tue, 20 Oct 2026 23:06:45 GMT
Server: Apache/2.2.15 (CentOS)
Set-Cookie: JSESSIONID=3AFAD92493FCBE5A5BC57E7FAE591365; Path=/userportal
Pragma: No-cache
Cache-Control: no-store
Expires: Wed, 31 Dec 1969 23:59:59 GMT
Content-Length: 501
Connection: close
Content-Type: text/html;charset=ISO-8859-1
```

Body:

```html
<html><head><title>Powered by Inventum</title>
<SCRIPT>
    function moveToLogin() { setTimeout("loadForm()",500); }
    function loadForm(){
        document.forms[0].action="login.do?requesturi=http%3A%2F%2Fexample.com%2F";
        document.forms[0].method="post";
        document.forms[0].submit();
    }
</SCRIPT></head>
<body onload="moveToLogin();"><FORM>Loading the login page...</FORM></body></html>
```

---

## 5. `login.do`

```text
POST /userportal/login.do?requesturi=http%3A%2F%2Fexample.com%2F
Cookie: JSESSIONID=3AFAD92493FCBE5A5BC57E7FAE591365

HTTP/1.1 200 OK
Content-Length: 538
```

```html
<!doctype html>
<html class="no-js" lang="">
<head>...</head>
<body style=background-color:#f6f7f8>
<script>
    window.location = "/userportal/pages/usermedia/curaj/app/campus/ui/index.html";
</script>
</body>
</html>
```

---

## 6. `login.html` form

```html
<form name="form1" id="form1" method="POST" action="/userportal/newlogin.do">
  <div class="voucher-pin">
    <p>Login to Central University Rajasthan Wi-Fi</p>
    <hr>
    <input type="text" name="username"
           placeholder="Enter your mobile number / Username" maxlength="10" required>
    <hr>
    <input type="password" name="password" placeholder="Enter your password">
    <input type="hidden" name="phone" value="0">
    <input type="hidden" name="type" value="2">
    <input type="hidden" name="jsonresponse" value="1">
    <hr>
  </div>
  ...
</form>
<script>
$(document).ready(function() {
    $('#form1').ajaxForm(function(data) {
        if (data.errorMessage == "success_net" && data.errorKey == "success") {
            window.location = "logout.html";
        } else {
            alert(data.errorMessage);
        }
    });
});
</script>
```

---

## 7. `newlogin.do` responses

### 7.1 No session context

```text
POST /userportal/newlogin.do
username=<mobile>&password=<password>&phone=0&type=2&jsonresponse=1

HTTP/1.1 200 OK
Content-Type: text/json;charset=ISO-8859-1
Content-Length: 30

{"errorKey":"redirect_to_nas"}
```

### 7.2 Valid session, device already online

```text
POST /userportal/newlogin.do
Cookie: JSESSIONID=<bound session>
...

HTTP/1.1 200 OK
Content-Length: 67

{"errorMessage":"Session already running","errorKey":"failureCase"}
```

### 7.3 Valid session, offline → success

```text
POST /userportal/newlogin.do
Cookie: JSESSIONID=<bound session>
...

HTTP/1.1 200 OK

{"errorMessage":"success_net","errorKey":"success"}
```

The client log:

```text
[curaj-login] portal: http://122.252.242.93/userportal/?...&sc=6d244...f476...
[curaj-login] POST http://122.252.242.93/userportal/newlogin.do (user 99356xxxxx)
[curaj-login] portal says: {'errorMessage': 'success_net', 'errorKey': 'success'}
OK: logged in
real 0m2.703s
```

Verification:

```text
generate_204 -> 204
https example.com -> 200
online via wlp0s20f3
```

---

## 8. Logout

### 8.1 No cookie

```text
GET /userportal/logout.do

HTTP/1.1 302 Moved Temporarily
Set-Cookie: JSESSIONID=4FE86EC0AFBCFB1685860B8A0976418D; Path=/userportal
Location: http://1.254.254.254?N=1792537647655
```

No deauth. The client still reports “online”.

```text
GET http://1.254.254.254/logout

HTTP/1.1 200 OK
Content-Length: 0
```

No deauth.

### 8.2 With bound cookie

```text
GET /userportal/logout.do
Cookie: JSESSIONID=<bound>

HTTP/1.1 200 OK
Content-Length: 538
```

Result: `generate_204` stops returning 204; the Wi-Fi is blocked.

Client output:

```text
$ curaj-login logout
logged out: session: JSESSIONID; logout.do -> 200; nas/logout -> 200
real 0m8.256s
```

---

## 9. `sc` stability

| time | context | `sc` |
|---|---|---|
| 04:35 | first pre-auth redirect | `6d244db2d6b400d00d5f9fa1e00af476` |
| 04:51 | after logout, pre-auth redirect | `6d244db2d6b400d00d5f9fa1e00af476` |
| 05:08 | daemon re-login | `6d244db2d6b400d00d5f9fa1e00af476` |

`locationid` changed with the AP (`B5-FF-Roomno.126:...` vs
`B5-SF-RM-226-AP-:...`) while `sc` stayed the same.

A brute-force over MD5/SHA-1/SHA-256 of permutations of IP, MAC (with/without
colons), `nas`, `interface`, portal IP, AP labels and separators produced no
match. Modelled as a stored per-device token.

---

## 10. Service recovery

```text
Oct 21 04:51:51  forced logout
Oct 21 04:52:24  [curaj-login] captive portal detected on wlp0s20f3
Oct 21 04:52:30  [curaj-login] FAIL: connectivity probe failed ... timed out
Oct 21 04:52:50  [curaj-login] captive portal detected on wlp0s20f3
Oct 21 04:52:50  [curaj-login] portal: ...&sc=6d244...f476...
Oct 21 04:52:50  [curaj-login] POST .../newlogin.do (user 99356xxxxx)
Oct 21 04:52:51  [curaj-login] portal says: success_net / success
Oct 21 04:52:52  [curaj-login] OK: logged in
```

The 04:52:30 timeout is the NAS blackhole while the session is torn down; the
loop backs off and retries.

---

## 11. Offline mock-portal test

`linux/test_flow.py` emulates the four-hop flow, including a
`redirect_to_nas` first response, a logout, and a successful retry:

```text
PASS  refused untrusted portal
PASS  login succeeded
PASS  used sc token
PASS  kept JSESSIONID
PASS  visited login.do
PASS  encoded credentials
PASS  recovered from redirect_to_nas

result: logged in
form: username=9000000000&password=p%40ss+word%26x&phone=0&type=2&jsonresponse=1
```

This test caught a portability bug: the client was dropping the port from the
portal host (`127.0.0.1:8765` → `127.0.0.1`). Both clients now use
`netloc` / `authority`.

---

## 12. Tooling notes

* `browser-harness` was inspected (`--help`, `skill`) but **not needed**:
  the portal is plain HTTP and fully scriptable with `curl` / `http.client`.
  No JS challenge, CAPTCHA or WebSocket is involved.
* REA (reverse-engineering skill) was loaded; there was no binary or JavaScript
  bundle to decompile. The target is a live HTTP service, so the investigation
  was protocol-level.
* The decisive tool was `curl --interface <device>` plus careful reading of
  `ip route get`; `nmap` found the Squid port.
