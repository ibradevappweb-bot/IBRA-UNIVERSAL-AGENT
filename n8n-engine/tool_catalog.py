#!/usr/bin/env python3
"""Catalogue versionné d'outils IBRA : tests obligatoires, revue TUTEUR et rollback.

Bibliothèque standard uniquement. Les outils sont exécutés via l'interpréteur AST
restreint de engine.py, jamais avec exec/eval.
"""
import json
import os
import re
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

import engine

HOST = os.environ.get("IBRA_CATALOG_HOST", "127.0.0.1")
PORT = int(os.environ.get("IBRA_CATALOG_PORT", "8768"))
TOKEN = os.environ.get("IBRA_CATALOG_TOKEN", "")
ROOT = Path(os.environ.get("IBRA_CATALOG_DIR", Path(__file__).resolve().parent / "data" / "catalog"))
NAME_RE = re.compile(r"^[a-z][a-z0-9_]{1,47}$")


class CatalogError(ValueError):
    """Erreur contrôlée du catalogue."""


def now():
    return datetime.now(timezone.utc).isoformat()


def validate_name(name):
    if not isinstance(name, str) or not NAME_RE.fullmatch(name):
        raise CatalogError("Nom invalide : 2 à 48 caractères, minuscules, chiffres ou _.")
    return name


def safe_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(obj, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
                    encoding="utf-8")
    temp.replace(path)


def load_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CatalogError("Fichier du catalogue absent ou corrompu.") from exc


def tool_dir(name):
    return ROOT / validate_name(name)


def mutation_auth_result(token, authorization):
    """Require an explicit secret for every HTTP mutation; never trust a caller-supplied review alone."""
    if not token:
        return False, 503, "IBRA_CATALOG_TOKEN_REQUIRED"
    if authorization != "Bearer " + token:
        return False, 401, "UNAUTHORIZED"
    return True, 200, "OK"


def run_tests(code, cases):
    if not isinstance(cases, list) or not 3 <= len(cases) <= 20:
        raise CatalogError("Fournir entre 3 et 20 tests indépendants.")
    engine.parse_code(code)
    outcomes = []
    for index, case in enumerate(cases, 1):
        if not isinstance(case, dict) or set(case) != {"input", "expected"}:
            raise CatalogError("Chaque test doit contenir exactement input et expected.")
        if not isinstance(case["input"], (dict, list, str, int, float, bool, type(None))):
            raise CatalogError("Entrée de test non JSON.")
        try:
            actual = engine.execute_code(code, case["input"])
            json.dumps(actual, ensure_ascii=False, allow_nan=False)
            passed = type(actual) is type(case["expected"]) and actual == case["expected"]
            outcomes.append({"index": index, "passed": passed,
                             "expected": case["expected"], "actual": actual})
        except Exception as exc:
            outcomes.append({"index": index, "passed": False,
                             "expected": case["expected"],
                             "error": type(exc).__name__ + ": " + str(exc)})
    return outcomes


def list_tools():
    ROOT.mkdir(parents=True, exist_ok=True)
    result = []
    for folder in sorted(ROOT.iterdir()):
        if not folder.is_dir() or not NAME_RE.fullmatch(folder.name):
            continue
        manifest_path = folder / "manifest.json"
        if not manifest_path.exists():
            continue
        try:
            manifest = load_json(manifest_path)
            item = {key: manifest.get(key) for key in
                    ("name", "active_version", "versions", "updated_at")}
            active = manifest.get("active_version")
            if type(active) is int:
                active_record = load_json(folder / ("v" + str(active) + ".json"))
                item["capabilities"] = active_record.get("capabilities", [])
            else:
                item["capabilities"] = []
            result.append(item)
        except CatalogError:
            result.append({"name": folder.name, "status": "CORRUPTED"})
    return result


def install_tool(name, code, cases, tuteur_review, capabilities=None):
    """Installe une nouvelle version uniquement après tests verts et revue TUTEUR approuvée."""
    name = validate_name(name)
    if not isinstance(code, str) or len(code) > 20000:
        raise CatalogError("Code absent ou supérieur à 20 000 caractères.")
    if not isinstance(tuteur_review, dict) or type(tuteur_review.get("approved")) is not bool:
        raise CatalogError("Revue TUTEUR requise avec approved booléen.")
    if not isinstance(tuteur_review.get("reasons"), list) or not isinstance(tuteur_review.get("risks"), list):
        raise CatalogError("La revue TUTEUR doit fournir reasons et risks sous forme de listes.")
    if not tuteur_review["approved"]:
        raise CatalogError("Installation refusée : le TUTEUR n'a pas approuvé l'outil.")

    # Les permissions sont explicites et versionnées avec le code ; aucune permission implicite.
    import capability_broker
    requested_capabilities = capability_broker.validate_capabilities(
        [] if capabilities is None else capabilities)
    reviewed_capabilities = tuteur_review.get("capabilities", [])
    if not isinstance(reviewed_capabilities, list):
        raise CatalogError("La revue TUTEUR doit déclarer capabilities sous forme de liste.")
    reviewed_capabilities = capability_broker.validate_capabilities(reviewed_capabilities)
    if requested_capabilities != reviewed_capabilities:
        raise CatalogError("Installation refusée : permissions demandées et permissions approuvées par le TUTEUR différentes.")

    outcomes = run_tests(code, cases)
    if not outcomes or not all(item["passed"] for item in outcomes):
        raise CatalogError("Installation refusée : tous les tests doivent réussir.")

    folder = tool_dir(name)
    folder.mkdir(parents=True, exist_ok=True)
    manifest_path = folder / "manifest.json"
    if manifest_path.exists():
        manifest = load_json(manifest_path)
        versions = manifest.get("versions", [])
        active = manifest.get("active_version")
        if not isinstance(versions, list) or (active not in versions and active is not None):
            raise CatalogError("Manifeste de versions invalide.")
    else:
        manifest = {"name": name, "versions": [], "active_version": None}
        versions = []

    # Chaque version est immuable ; l'ancienne reste disponible pour le rollback.
    version = max(versions, default=0) + 1
    record = {
        "name": name, "version": version, "code": code,
        "capabilities": requested_capabilities,
        "tests": outcomes, "test_count": len(outcomes),
        "tuteur_review": tuteur_review, "installed_at": now(),
        "status": "INSTALLED"
    }
    version_path = folder / ("v" + str(version) + ".json")
    safe_json(version_path, record)
    manifest["versions"] = versions + [version]
    manifest["active_version"] = version
    manifest["updated_at"] = now()
    safe_json(manifest_path, manifest)
    return {"ok": True, "name": name, "version": version,
            "active_version": version, "tests_passed": len(outcomes),
            "rollback_available": len(manifest["versions"]) > 1}


def run_tool(name, data):
    name = validate_name(name)
    manifest = load_json(tool_dir(name) / "manifest.json")
    version = manifest.get("active_version")
    if type(version) is not int:
        raise CatalogError("Aucune version active.")
    record = load_json(tool_dir(name) / ("v" + str(version) + ".json"))
    result = engine.execute_code(record["code"], data)
    json.dumps(result, ensure_ascii=False, allow_nan=False)
    return {"ok": True, "name": name, "version": version,
            "capabilities": record.get("capabilities", []), "result": result}


def rollback_tool(name):
    name = validate_name(name)
    folder = tool_dir(name)
    manifest = load_json(folder / "manifest.json")
    versions = manifest.get("versions", [])
    active = manifest.get("active_version")
    earlier = [v for v in versions if type(v) is int and v < active]
    if not earlier:
        raise CatalogError("Rollback impossible : aucune version antérieure.")
    previous = max(earlier)
    # Vérifie que la cible existe et contient du code interprétable avant activation.
    record = load_json(folder / ("v" + str(previous) + ".json"))
    engine.parse_code(record.get("code"))
    import capability_broker
    capability_broker.validate_capabilities(record.get("capabilities", []))
    manifest["active_version"] = previous
    manifest["updated_at"] = now()
    safe_json(folder / "manifest.json", manifest)
    return {"ok": True, "name": name, "active_version": previous,
            "rolled_back_from": active}


class Handler(BaseHTTPRequestHandler):
    server_version = "IBRA-Tool-Catalog/1.0"

    def log_message(self, fmt, *args):
        print("[IBRA-CATALOG] " + (fmt % args))

    def send_json(self, status, obj):
        raw = json.dumps(obj, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def authorized(self):
        return not TOKEN or self.headers.get("Authorization", "") == "Bearer " + TOKEN

    def body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length < 1 or length > 300000:
            raise CatalogError("JSON absent ou supérieur à 300 Ko.")
        obj = json.loads(self.rfile.read(length).decode("utf-8"))
        if not isinstance(obj, dict):
            raise CatalogError("Le JSON doit être un objet.")
        return obj

    def do_GET(self):
        if not self.authorized():
            return self.send_json(401, {"ok": False, "error": "UNAUTHORIZED"})
        route = urlparse(self.path).path
        if route == "/health":
            return self.send_json(200, {"ok": True, "service": "ibra-tool-catalog", "version": "1.0"})
        if route == "/tools":
            return self.send_json(200, {"ok": True, "tools": list_tools()})
        return self.send_json(404, {"ok": False, "error": "NOT_FOUND"})

    def do_POST(self):
        allowed, status, error = mutation_auth_result(
            TOKEN, self.headers.get("Authorization", ""))
        if not allowed:
            return self.send_json(status, {"ok": False, "error": error})
        try:
            body = self.body()
            route = urlparse(self.path).path
            if route == "/install":
                result = install_tool(body.get("name"), body.get("code"),
                                      body.get("tests"), body.get("tuteur_review"))
                return self.send_json(201, result)
            if route == "/run":
                if "data" not in body:
                    raise CatalogError("Champ data obligatoire.")
                return self.send_json(200, run_tool(body.get("name"), body["data"]))
            if route == "/rollback":
                return self.send_json(200, rollback_tool(body.get("name")))
            return self.send_json(404, {"ok": False, "error": "NOT_FOUND"})
        except (CatalogError, ValueError, KeyError, TypeError, OSError) as exc:
            return self.send_json(400, {"ok": False, "error": str(exc)})
        except Exception as exc:
            return self.send_json(422, {"ok": False, "error": type(exc).__name__ + ": " + str(exc)})


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    if HOST not in ("127.0.0.1", "localhost", "::1") and not TOKEN:
        raise SystemExit("Définissez IBRA_CATALOG_TOKEN avant toute écoute hors localhost.")
    print(f"IBRA Tool Catalog prêt sur http://{HOST}:{PORT}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
