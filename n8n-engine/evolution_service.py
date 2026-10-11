"""Local HTTP wrapper for the IBRA evolution cycle.

Binds to loopback by default. Do not expose publicly; non-loopback binding requires a token.
"""
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

import evolution

HOST = os.environ.get("IBRA_EVOLUTION_HOST", "127.0.0.1")
PORT = int(os.environ.get("IBRA_EVOLUTION_PORT", "8767"))
TOKEN = os.environ.get("IBRA_EVOLUTION_TOKEN", "")
RUN_LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[IBRA-EVOLUTION] " + (fmt % args))

    def send_json(self, status, payload):
        raw = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def authorized(self):
        return HOST in ("127.0.0.1", "localhost", "::1") or (
            bool(TOKEN) and self.headers.get("Authorization", "") == "Bearer " + TOKEN)

    def do_GET(self):
        if not self.authorized():
            return self.send_json(401, {"ok": False, "error": "UNAUTHORIZED"})
        route = urlparse(self.path).path
        if route == "/health":
            return self.send_json(200, {"ok": True, "service": "ibra-evolution", "port": PORT})
        if route == "/status":
            return self.send_json(200, {"ok": True, "status": evolution.status()})
        return self.send_json(404, {"ok": False, "error": "NOT_FOUND"})

    def do_POST(self):
        if not self.authorized():
            return self.send_json(401, {"ok": False, "error": "UNAUTHORIZED"})
        if urlparse(self.path).path != "/run":
            return self.send_json(404, {"ok": False, "error": "NOT_FOUND"})
        if not RUN_LOCK.acquire(blocking=False):
            return self.send_json(409, {"ok": False, "error": "CYCLE_ALREADY_RUNNING"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length > 1024:
                return self.send_json(413, {"ok": False, "error": "REQUEST_TOO_LARGE"})
            record = evolution.run_cycle()
            return self.send_json(200, {"ok": True, "cycle": record})
        except Exception as exc:
            return self.send_json(500, {"ok": False, "error": type(exc).__name__ + ": " + str(exc)})
        finally:
            RUN_LOCK.release()


def main():
    if HOST not in ("127.0.0.1", "localhost", "::1") and not TOKEN:
        raise SystemExit("IBRA_EVOLUTION_TOKEN is required for non-loopback binding.")
    print("IBRA Evolution API listening on http://" + HOST + ":" + str(PORT))
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
