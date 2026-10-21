#!/usr/bin/env python3
"""
Offline test for curaj-login: emulates the Inventum portal on localhost,
including the redirect_to_nas recovery path, the two-session limit, the
self-care session list, and closing one chosen session.

    python3 test_flow.py
"""
from __future__ import annotations

import importlib.machinery
import importlib.util
import sys
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = __import__("pathlib").Path(__file__).resolve().parent
STATE = {
    "auth": False,
    "first_post": True,
    "saw_sc": False,
    "saw_login_do": False,
    "saw_cookie": False,
    "form": "",
    "self_care": False,
    "context_self_care": False,
    "max_sessions": False,
    "sessions": [
        {
            "id": "137700001",
            "start": "2026-10-21 19:13:15",
            "duration": "00:10:19",
            "upload": "663.74KB",
            "download": "2.76MB",
            "total": "3.41MB",
        },
        {
            "id": "137700002",
            "start": "2026-10-21 15:09:00",
            "duration": "04:14:04",
            "upload": "4.84MB",
            "download": "28.32MB",
            "total": "33.16MB",
        },
    ],
    "logout_calls": [],
}
PORT = 8765
BASE = f"http://127.0.0.1:{PORT}"


def session_table() -> bytes:
    rows = []
    for i, session in enumerate(STATE["sessions"]):
        css = "odd" if i % 2 == 0 else "even"
        rows.append(
            f'<tr class="{css}">'
            "<td>TEST USER</td><td>9000000000</td>"
            "<td>CURAJ users Rate plan</td><td>9000000000</td>"
            f'<td>{session["start"]}</td><td>{session["duration"]}</td>'
            f'<td>{session["upload"]}</td><td>{session["download"]}</td>'
            f'<td>{session["total"]}</td>'
            f'<td><input type="button" value="Close" '
            f'onclick="callClose({session["id"]});"></td>'
            "</tr>"
        )
    body = (
        "<html><body>"
        '<div align="center">Current Session</div>'
        f'<span class="pagebanner">{len(STATE["sessions"])} items found, '
        "displaying all items.</span>"
        '<table id="row">'
        "<thead><tr><th>Name</th><th>Account ID</th><th>Rate Plan</th>"
        "<th>Net Id</th><th>Start Time</th><th>Duration</th><th>Upload</th>"
        "<th>Download</th><th>Total Bytes</th><th>Status</th></tr></thead>"
        f"<tbody>{''.join(rows)}</tbody></table></body></html>"
    )
    return body.encode()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, code, body=b"", headers=None):
        self.send_response(code)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _cookie(self) -> str:
        return self.headers.get("Cookie") or ""

    def do_GET(self):
        if self.path.startswith("/generate_204"):
            if STATE["auth"]:
                return self._send(204)
            body = (
                '<html><head><META HTTP-EQUIV="Refresh" CONTENT="1;URL='
                f'{BASE}/userportal/?requesturi=http%3a%2f%2fexample%2ecom%2f'
                '&ip=10.50.11.134&mac=74%3a04%3af1%3ade%3a65%3a52&nas=curaj'
                '&requestip=example%2ecom&sc=deadbeefcafe'
                '&interface=cpeth3&locationid=B5-FF-Roomno.126:CURAJ%20CAMPUS%20CONNECT'
                '"></head><body>Please wait</body></html>'
            ).encode()
            return self._send(200, body, {"Content-Type": "text/html"})

        if self.path.startswith("/userportal/?"):
            STATE["saw_sc"] = "sc=deadbeefcafe" in self.path
            body = b"<html><body>loading</body></html>"
            return self._send(200, body, {
                "Content-Type": "text/html",
                "Set-Cookie": "JSESSIONID=TEST123; Path=/userportal",
            })

        if self.path.startswith(
                "/userportal/pages/usermedia/curaj/app/campus/ui/login.jsp"):
            return self._send(200, b"<html><body>self care login</body></html>", {
                "Content-Type": "text/html",
                "Set-Cookie": "JSESSIONID=SELFCARE; Path=/userportal",
            })

        if self.path.startswith("/userportal/currentSessions.do"):
            # Both the context cookie and the direct self-care cookie can
            # list sessions; only the context cookie may close one.
            if (not STATE["self_care"]
                    or not any(c in self._cookie() for c in
                               ("JSESSIONID=TEST123", "JSESSIONID=SELFCARE"))):
                return self._send(200, (
                    b"<html><body>Your Session has been expired "
                    b"Please Click here to Login</body></html>"
                ))
            return self._send(200, session_table(),
                              {"Content-Type": "text/html"})

        if self.path.startswith("/userportal/login.do"):
            STATE["saw_login_do"] = True
            return self._send(200, b"<html></html>")

        if self.path.startswith("/logout"):
            STATE["auth"] = False
            STATE["first_post"] = False
            return self._send(200)

        return self._send(404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode()

        if self.path.startswith("/userportal/newlogin.do"):
            params = urllib.parse.parse_qs(body)

            # Self-care login: list sessions, never create an internet session.
            if params.get("type") == ["1"]:
                if "JSESSIONID=TEST123" in self._cookie():
                    STATE["context_self_care"] = True
                elif "JSESSIONID=SELFCARE" not in self._cookie():
                    return self._send(403, b"missing self-care cookie")
                STATE["self_care"] = True
                return self._send(200, b"<html><frameset></frameset></html>")

            STATE["saw_cookie"] = "JSESSIONID=TEST123" in self._cookie()
            STATE["form"] = body

            if STATE["max_sessions"]:
                return self._send(
                    200,
                    b'{"errorKey":"failureCase","errorMessage":'
                    b'"Maximum session allowed reached. your subscription '
                    b'allow a maximum of 2 sessiosn"}',
                    {"Content-Type": "text/json"})

            if STATE["first_post"]:
                return self._send(
                    200, b'{"errorKey":"redirect_to_nas"}',
                    {"Content-Type": "text/json"})

            STATE["auth"] = True
            return self._send(
                200, b'{"errorKey":"success","errorMessage":"success_net"}',
                {"Content-Type": "text/json"})

        if self.path.startswith("/userportal/logout.do"):
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            sesno = (query.get("sesno") or [""])[0]
            STATE["logout_calls"].append(sesno)

            # The real portal only closes a session when the request also
            # carries the splash/context cookie.  A direct self-care cookie
            # gets the misleading 302-to-NAS response and leaves it alive.
            if "JSESSIONID=TEST123" not in self._cookie():
                return self._send(
                    302, b"",
                    {"Location": f"http://1.254.254.254?N={sesno}"})

            before = len(STATE["sessions"])
            STATE["sessions"] = [
                s for s in STATE["sessions"] if s["id"] != sesno
            ]
            if len(STATE["sessions"]) == before:
                # The real portal redirects to the NAS even for an invalid id.
                return self._send(302, b"", {"Location": f"{BASE}/logout"})
            return self._send(
                200,
                b"<html><body>Your Session Has Been Stopped</body></html>")

        if self.path.startswith("/userportal/login.do"):
            STATE["saw_login_do"] = True
            return self._send(200, b"<html></html>")

        return self._send(404)


def load_client():
    loader = importlib.machinery.SourceFileLoader("cl", str(HERE / "curaj-login"))
    spec = importlib.util.spec_from_loader("cl", loader)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["cl"] = mod
    loader.exec_module(mod)
    return mod


def main() -> int:
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    cl = load_client()
    # point the client at the mock instead of the real hosts
    cl.CHECK_URL = f"{BASE}/generate_204"
    cl.CHECK_HOST = f"127.0.0.1:{PORT}"
    cl.NAS_LOGOUT_URL = f"{BASE}/logout"
    cl.PORTAL_HOST = f"127.0.0.1:{PORT}"

    config = {
        "username": "9000000000",
        "password": "p@ss word&x",
        "portal_hosts": ["127.0.0.1"],
    }

    # A hostile Wi-Fi could serve its own /userportal page.  The client must
    # refuse to send the password there.
    rogue_ok, rogue_msg = cl.login({
        "username": "9000000000",
        "password": "p@ss word&x",
        "portal_hosts": ["example.com"],
    }, device=None, verbose=False)

    # The portal's two-session cap: report it, never auto-kick.
    STATE["max_sessions"] = True
    max_ok, max_msg = cl.login(config, device=None, verbose=False)
    STATE["max_sessions"] = False

    # Self-care can list sessions despite the cap and must not create a
    # third internet session.
    list_ok, sessions, list_msg = cl.list_sessions(None, config)
    listed_ids = [s.sesno for s in sessions]
    auth_after_list = STATE["auth"]

    # Close the older one, then retry the normal login.
    logout_ok, logout_msg = cl.logout_session(None, config, "137700002")
    after_ok, after_sessions, _ = cl.list_sessions(None, config)

    STATE["first_post"] = True
    ok, msg = cl.login(config, device=None, verbose=True)
    srv.shutdown()

    checks = {
        "refused untrusted portal": (
            not rogue_ok and "untrusted portal" in rogue_msg),
        "max session detected": (
            not max_ok and cl.is_session_limit(max_msg)),
        "self-care list parsed": (
            list_ok and listed_ids == ["137700001", "137700002"]),
        "self-care used context cookie": STATE["context_self_care"],
        "self-care did not create session": not auth_after_list,
        "specific session logged out": (
            logout_ok and after_ok
            and [s.sesno for s in after_sessions] == ["137700001"]
            and "137700002" in STATE["logout_calls"]),
        "login succeeded": ok and msg == "logged in",
        "used sc token": STATE["saw_sc"],
        "kept JSESSIONID": STATE["saw_cookie"],
        "visited login.do": STATE["saw_login_do"],
        "encoded credentials": (
            "username=9000000000" in STATE["form"]
            and "password=p%40ss+word%26x" in STATE["form"]
            and "jsonresponse=1" in STATE["form"]),
        "recovered from redirect_to_nas": STATE["auth"] is True,
    }

    for name, passed in checks.items():
        print(("PASS  " if passed else "FAIL  ") + name)
    print("\nmax:", max_msg)
    print("sessions:", listed_ids)
    print("logout:", logout_msg)
    print("result:", msg)
    print("form:", STATE["form"])

    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
