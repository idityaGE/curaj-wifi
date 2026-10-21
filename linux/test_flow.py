#!/usr/bin/env python3
"""
Offline test for curaj-login: emulates the Inventum portal on localhost,
including the "redirect_to_nas -> logout -> retry" recovery path.

    python3 test_flow.py
"""
from __future__ import annotations

import importlib.machinery
import importlib.util
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = __import__("pathlib").Path(__file__).resolve().parent
STATE = {
    "auth": False,
    "first_post": True,
    "saw_sc": False,
    "saw_login_do": False,
    "saw_cookie": False,
    "form": "",
}
PORT = 8765
BASE = f"http://127.0.0.1:{PORT}"


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
            STATE["saw_cookie"] = "JSESSIONID=TEST123" in (
                self.headers.get("Cookie") or "")
            STATE["form"] = body

            if STATE["first_post"]:
                return self._send(
                    200, b'{"errorKey":"redirect_to_nas"}',
                    {"Content-Type": "text/json"})

            STATE["auth"] = True
            return self._send(
                200, b'{"errorKey":"success","errorMessage":"success_net"}',
                {"Content-Type": "text/json"})

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
    cl.PORTAL_HOST = "127.0.0.1"

    # A hostile Wi-Fi could serve its own /userportal page.  The client must
    # refuse to send the password there.
    rogue_ok, rogue_msg = cl.login({
        "username": "9000000000",
        "password": "p@ss word&x",
        "portal_hosts": ["example.com"],
    }, device=None, verbose=False)

    ok, msg = cl.login({
        "username": "9000000000",
        "password": "p@ss word&x",
        "portal_hosts": ["127.0.0.1"],
    }, device=None, verbose=True)
    srv.shutdown()

    checks = {
        "refused untrusted portal": (
            not rogue_ok and "untrusted portal" in rogue_msg),
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
    print("\nresult:", msg)
    print("form:", STATE["form"])

    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
