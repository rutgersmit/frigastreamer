"""Minimale Frigate-API-mock voor de FrigaStreamer-testomgeving (alleen standaardbibliotheek).

Poort 5000: interne API zonder authenticatie.
Poort 8971: 'geauthenticeerde' API (HTTP i.p.v. HTTPS, zet in de app 'HTTPS gebruiken' uit).
            POST /api/login met {"user","password"} geeft cookie frigate_token; tokens verlopen
            na TOKEN_TTL seconden zodat het automatisch opnieuw inloggen te testen is.

/api/go2rtc/streams wordt live afgeleid van de paden die MediaMTX op dat moment heeft (zoals
go2rtc alleen bestaande streams toont), met een vaste lijst als terugval.
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
FALLBACK = ["voordeur", "voordeur_sub", "tuin", "tuin_sub", "oprit", "garage", "deurbel", "schuur",
            "snel_gop", "traag_gop", "achterdeur", "carport", "carport_sub"]
tokens = {}
lock = threading.Lock()


def stream_names():
    try:
        with urllib.request.urlopen(MEDIAMTX_API + "/v3/paths/list?itemsPerPage=1000", timeout=2) as r:
            items = json.load(r).get("items", [])
            # Net als go2rtc: alle geconfigureerde streams, ook als de bron (camera) even weg is.
            return sorted(i["name"] for i in items)
    except Exception as e:  # MediaMTX onbereikbaar: gedraag je als go2rtc zonder streams
        print("mediamtx api niet bereikbaar:", e)
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
    print("frigate-mock: :5000 (intern) en :8971 (login %s)" % USER)
    serve(5000, Handler)
