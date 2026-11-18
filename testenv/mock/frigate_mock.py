"""Minimal Frigate API mock for the FrigaStreamer test environment (standard library only).

Port 5000: internal API without authentication.
Port 8971: 'authenticated' API (HTTP instead of HTTPS; turn off 'Use HTTPS' in the app).
           POST /api/login with {"user","password"} returns cookie frigate_token; tokens expire
           after TOKEN_TTL seconds so automatically logging in again can be tested.

/api/go2rtc/streams is derived live from the paths MediaMTX has at that moment (just as
go2rtc only shows existing streams), with a fixed list as fallback.
"""
import json
import os
import secrets
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MEDIAMTX_API = os.environ.get("MEDIAMTX_API", "http://mediamtx:9997")
USER = os.environ.get("MOCK_USER", "admin")
PASSWORD = os.environ.get("MOCK_PASSWORD", "admin")
TOKEN_TTL = int(os.environ.get("TOKEN_TTL", "300"))
CONFIG = json.load(open(os.path.join(os.path.dirname(__file__), "frigate_config.json")))
FALLBACK = ["front_door", "front_door_sub", "garden", "garden_sub", "driveway", "garage", "doorbell", "shed",
            "fast_gop", "slow_gop", "back_door", "carport", "carport_sub"]
tokens = {}
lock = threading.Lock()


def stream_names():
    try:
        with urllib.request.urlopen(MEDIAMTX_API + "/v3/paths/list?itemsPerPage=1000", timeout=2) as r:
            items = json.load(r).get("items", [])
            # Just like go2rtc: all configured streams, even if the source (camera) is briefly gone.
            return sorted(i["name"] for i in items)
    except Exception as e:  # MediaMTX unreachable: behave like go2rtc without streams
        print("mediamtx api not reachable:", e)
        return []


class Handler(BaseHTTPRequestHandler):
    auth_required = False

    def log_message(self, fmt, *args):
        print("%s %s" % (self.server.server_port, fmt % args))

    def _send(self, code, body, headers=None):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self):
        if not self.auth_required:
            return True
        cookie = self.headers.get("Cookie", "")
        for part in cookie.split(";"):
            k, _, v = part.strip().partition("=")
            if k == "frigate_token":
                with lock:
                    exp = tokens.get(v)
                return exp is not None and exp > time.time()
        return False

    def do_POST(self):
        if self.path == "/api/login" and self.auth_required:
            length = int(self.headers.get("Content-Length", "0"))
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except ValueError:
                return self._send(400, {"message": "bad json"})
            if body.get("user") == USER and body.get("password") == PASSWORD:
                tok = secrets.token_hex(16)
                with lock:
                    tokens[tok] = time.time() + TOKEN_TTL
                return self._send(200, {}, {"Set-Cookie": "frigate_token=%s; Path=/; HttpOnly" % tok})
            return self._send(401, {"message": "Login failed"})
        self._send(404, {"message": "not found"})

    def do_GET(self):
        if not self._authorized():
            return self._send(401, {"message": "Unauthorized"})
        if self.path.startswith("/api/go2rtc/streams"):
            names = stream_names()
            return self._send(200, {n: {"producers": [{"url": "rtsp://mock/%s" % n}], "consumers": None} for n in names})
        if self.path.startswith("/api/config"):
            return self._send(200, CONFIG)
        if self.path.startswith("/api/version"):
            return self._send(200, b'"0.16.0-mock"')
        self._send(404, {"message": "not found"})


class AuthHandler(Handler):
    auth_required = True


def serve(port, handler):
    ThreadingHTTPServer(("0.0.0.0", port), handler).serve_forever()


if __name__ == "__main__":
    threading.Thread(target=serve, args=(8971, AuthHandler), daemon=True).start()
    print("frigate-mock: :5000 (internal) and :8971 (login %s)" % USER)
    serve(5000, Handler)
