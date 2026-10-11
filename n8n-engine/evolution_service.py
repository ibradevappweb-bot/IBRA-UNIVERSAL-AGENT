"""Local HTTP wrapper for the IBRA evolution cycle.

Binds to loopback by default. Do not expose publicly; non-loopback binding requires a token.
"""
import json
import os
import threading
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

import evolution
import tool_catalog
import capability_broker

HOST = os.environ.get("IBRA_EVOLUTION_HOST", "127.0.0.1")
PORT = int(os.environ.get("IBRA_EVOLUTION_PORT", "8767"))
TOKEN = os.environ.get("IBRA_EVOLUTION_TOKEN", "")
RUN_LOCK = threading.Lock()

SVG_TAGS = {
    "svg", "g", "rect", "circle", "ellipse", "line", "polyline", "polygon",
    "path", "text", "tspan", "defs", "linearGradient", "stop"
}
SVG_ATTRS = {
    "xmlns", "width", "height", "viewBox", "x", "y", "x1", "y1", "x2", "y2",
    "cx", "cy", "r", "rx", "ry", "fill", "stroke", "stroke-width", "points",
    "d", "transform", "opacity", "font-size", "font-family", "font-weight",
    "text-anchor", "dominant-baseline", "offset", "stop-color", "stop-opacity",
    "id", "gradientUnits"
}


def validate_svg_image(content):
    """Accept only small, self-contained SVGs with a strict tag/attribute allowlist."""
    if not isinstance(content, str) or not content.strip():
        raise ValueError("L'outil doit retourner le contenu SVG sous forme de texte.")
    raw = content.encode("utf-8")
    if len(raw) > 300_000:
        raise ValueError("SVG trop volumineux (300 Ko maximum).")
    if "<!DOCTYPE" in content.upper() or "<!ENTITY" in content.upper():
        raise ValueError("DOCTYPE et entités XML interdits.")
    try:
        root = ET.fromstring(content)
    except ET.ParseError as exc:
        raise ValueError("Le résultat n'est pas un SVG/XML valide.") from exc
    if root.tag not in ("svg", "{http://www.w3.org/2000/svg}svg"):
        raise ValueError("La racine du fichier doit être <svg>.")
    count = 0
    for element in root.iter():
        count += 1
        tag = element.tag.rsplit("}", 1)[-1] if isinstance(element.tag, str) else ""
        if tag not in SVG_TAGS:
            raise ValueError("Élément SVG interdit : " + tag)
        for attr, value in element.attrib.items():
            attr_name = attr.rsplit("}", 1)[-1]
            if attr_name.lower().startswith("on") or attr_name not in SVG_ATTRS:
                raise ValueError("Attribut SVG interdit : " + attr_name)
            if "javascript:" in value.lower() or "data:" in value.lower() or "http://" in value.lower() or "https://" in value.lower():
                raise ValueError("Les ressources externes et scripts sont interdits.")
    if count > 5000:
        raise ValueError("SVG trop complexe : 5000 éléments maximum.")
    return content


def use_tool(name, data, save_as=None):
    """Run the active reviewed tool; optionally save a validated SVG through its file.write permission."""
    outcome = tool_catalog.run_tool(name, data)
    if save_as is None:
        return outcome
    if "file.write" not in outcome.get("capabilities", []):
        raise ValueError("L'outil actif ne possède pas la permission file.write.")
    svg = outcome.get("result")
    if isinstance(svg, dict):
        svg = svg.get("svg")
    validate_svg_image(svg)
    saved = capability_broker.write_file(name, save_as, svg)
    return {"ok": True, "name": name, "version": outcome["version"],
            "capabilities": outcome["capabilities"], "saved": saved,
            "format": "svg", "result": svg}


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
        route = urlparse(self.path).path
        if route not in ("/run", "/use-tool"):
            return self.send_json(404, {"ok": False, "error": "NOT_FOUND"})
        if not RUN_LOCK.acquire(blocking=False):
            return self.send_json(409, {"ok": False, "error": "CYCLE_ALREADY_RUNNING"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length > 300_000:
                return self.send_json(413, {"ok": False, "error": "REQUEST_TOO_LARGE"})
            body = json.loads(self.rfile.read(length).decode("utf-8")) if length else {}
            if not isinstance(body, dict):
                return self.send_json(400, {"ok": False, "error": "INVALID_JSON_OBJECT"})
            if route == "/use-tool":
                if "name" not in body or "data" not in body:
                    return self.send_json(400, {"ok": False, "error": "name_and_data_required"})
                result = use_tool(body["name"], body["data"], body.get("save_as"))
                return self.send_json(200, result)
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
