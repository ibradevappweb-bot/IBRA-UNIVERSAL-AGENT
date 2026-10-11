#!/usr/bin/env python3
"""Broker de capacités IBRA : fichiers bornés, transformations JSON/CSV et connecteurs allowlistés.

Bibliothèque standard uniquement. Le code généré ne reçoit jamais un accès direct au système,
aux fichiers ou au réseau : l'orchestrateur appelle ce broker avec un nom d'outil enregistré.
"""
import csv
import io
import ipaddress
import json
import os
import re
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import tool_catalog

HOST = os.environ.get("IBRA_CAPABILITY_HOST", "127.0.0.1")
PORT = int(os.environ.get("IBRA_CAPABILITY_PORT", "8769"))
TOKEN = os.environ.get("IBRA_CAPABILITY_TOKEN", "")
WORKSPACE = Path(os.environ.get(
    "IBRA_CAPABILITY_WORKSPACE", Path(__file__).resolve().parent / "data" / "workspace"
)).resolve()
MAX_BYTES = 300_000
MAX_ROWS = 5_000
MAX_COLUMNS = 200
NAME_RE = re.compile(r"^[a-z][a-z0-9_]{1,47}$")
CAPABILITY_RE = re.compile(r"^(file\.(read|write)|data\.(json|csv)|connector:[a-z][a-z0-9_]{1,47})$")


class CapabilityError(ValueError):
    """Action refusée ou entrée de capacité invalide."""


def _json_size(value):
    try:
        return len(json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8"))
    except (TypeError, ValueError) as exc:
        raise CapabilityError("Les données doivent être du JSON valide, sans NaN/Infinity.") from exc


def validate_capabilities(capabilities):
    if not isinstance(capabilities, list) or len(capabilities) > 20:
        raise CapabilityError("capabilities doit être une liste de 0 à 20 permissions.")
    if any(not isinstance(item, str) or not CAPABILITY_RE.fullmatch(item) for item in capabilities):
        raise CapabilityError("Permission inconnue. Permissions autorisées : file.read, file.write, data.json, data.csv, connector:<id>.")
    if len(set(capabilities)) != len(capabilities):
        raise CapabilityError("Permissions dupliquées.")
    return sorted(capabilities)


def require_permission(tool_name, permission):
    if not isinstance(tool_name, str) or not NAME_RE.fullmatch(tool_name):
        raise CapabilityError("Nom d'outil invalide.")
    manifest_path = tool_catalog.tool_dir(tool_name) / "manifest.json"
    manifest = tool_catalog.load_json(manifest_path)
    active = manifest.get("active_version")
    if type(active) is not int:
        raise CapabilityError("Aucune version active pour cet outil.")
    version_path = tool_catalog.tool_dir(tool_name) / ("v" + str(active) + ".json")
    record = tool_catalog.load_json(version_path)
    capabilities = validate_capabilities(record.get("capabilities", []))
    if permission not in capabilities:
        raise CapabilityError("Permission refusée : " + permission + " n'est pas accordée à " + tool_name + ".")
    return record


def safe_path(relative_path, for_write=False):
    if not isinstance(relative_path, str) or not relative_path or len(relative_path) > 240:
        raise CapabilityError("Chemin relatif requis (240 caractères maximum).")
    if "\x00" in relative_path or "\\" in relative_path:
        raise CapabilityError("Chemin invalide.")
    parsed = Path(relative_path)
    if parsed.is_absolute() or any(part in ("", ".", "..") for part in relative_path.split("/")):
        raise CapabilityError("Chemin interdit : utiliser un chemin relatif sans . ni ...")
    target = (WORKSPACE / parsed).resolve()
    try:
        target.relative_to(WORKSPACE)
    except ValueError as exc:
        raise CapabilityError("Le chemin sort de l'espace autorisé.") from exc
    # Refuse les liens symboliques dans le chemin, même s'ils pointent actuellement à l'intérieur.
    cursor = WORKSPACE
    for part in parsed.parts:
        cursor = cursor / part
        if cursor.is_symlink():
            raise CapabilityError("Les liens symboliques sont interdits.")
    if for_write:
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and not target.is_file():
            raise CapabilityError("La destination doit être un fichier.")
    elif not target.is_file():
        raise CapabilityError("Fichier introuvable.")
    return target


def read_file(tool_name, relative_path):
    require_permission(tool_name, "file.read")
    target = safe_path(relative_path)
    if target.stat().st_size > MAX_BYTES:
        raise CapabilityError("Fichier trop volumineux (300 Ko maximum).")
    raw = target.read_bytes()
    if len(raw) > MAX_BYTES:
        raise CapabilityError("Fichier trop volumineux (300 Ko maximum).")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise CapabilityError("Seuls les fichiers texte UTF-8 sont acceptés.") from exc
    return {"path": relative_path, "content": text, "bytes": len(raw)}


def write_file(tool_name, relative_path, content):
    require_permission(tool_name, "file.write")
    if not isinstance(content, str):
        raise CapabilityError("content doit être du texte UTF-8.")
    raw = content.encode("utf-8")
    if len(raw) > MAX_BYTES:
        raise CapabilityError("Fichier trop volumineux (300 Ko maximum).")
    target = safe_path(relative_path, for_write=True)
    temp = None
    try:
        # NamedTemporaryFile crée un nom imprévisible avec création exclusive ;
        # un fichier/lien préexistant ne peut donc pas détourner l'écriture.
        with tempfile.NamedTemporaryFile(
                mode="wb", dir=str(target.parent), prefix=".ibra-", suffix=".tmp",
                delete=False) as handle:
            temp = Path(handle.name)
            handle.write(raw)
        temp.replace(target)
    finally:
        if temp is not None and temp.exists():
            temp.unlink()
    return {"path": relative_path, "bytes_written": len(raw)}


def transform_json(tool_name, data, operation, fields=None, mapping=None, key=None, equals=None):
    require_permission(tool_name, "data.json")
    if _json_size(data) > MAX_BYTES:
        raise CapabilityError("JSON trop volumineux (300 Ko maximum).")
    if operation == "identity":
        result = data
    elif operation == "select" and isinstance(data, dict):
        if not isinstance(fields, list) or any(not isinstance(x, str) for x in fields):
            raise CapabilityError("fields doit être une liste de noms.")
        result = {field: data[field] for field in fields if field in data}
    elif operation == "rename" and isinstance(data, dict):
        if not isinstance(mapping, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in mapping.items()):
            raise CapabilityError("mapping doit associer des noms texte à des noms texte.")
        result = {mapping.get(k, k): v for k, v in data.items()}
        if len(result) != len(data):
            raise CapabilityError("Le renommage crée des clés en double.")
    elif operation == "filter_equal" and isinstance(data, list):
        if len(data) > MAX_ROWS or not isinstance(key, str):
            raise CapabilityError("Liste trop longue ou clé invalide.")
        result = [row for row in data if isinstance(row, dict) and row.get(key) == equals]
    else:
        raise CapabilityError("Opération JSON invalide ou type d'entrée incompatible.")
    if _json_size(result) > MAX_BYTES:
        raise CapabilityError("Résultat JSON trop volumineux.")
    return {"operation": operation, "result": result}


def transform_csv(tool_name, text, select=None, rename=None, filter_column=None, equals=None):
    require_permission(tool_name, "data.csv")
    if not isinstance(text, str) or len(text.encode("utf-8")) > MAX_BYTES:
        raise CapabilityError("CSV absent ou supérieur à 300 Ko.")
    try:
        reader = csv.DictReader(io.StringIO(text, newline=""))
        headers = reader.fieldnames
        if not headers or len(headers) > MAX_COLUMNS or any(not h for h in headers):
            raise CapabilityError("En-têtes CSV absents, vides ou trop nombreux.")
        rows = []
        for row in reader:
            if len(rows) >= MAX_ROWS:
                raise CapabilityError("CSV limité à 5000 lignes.")
            if None in row:
                raise CapabilityError("Ligne CSV avec plus de colonnes que l'en-tête.")
            rows.append(row)
    except csv.Error as exc:
        raise CapabilityError("CSV invalide : " + str(exc)) from exc
    columns = headers
    if select is not None:
        if not isinstance(select, list) or any(not isinstance(c, str) for c in select):
            raise CapabilityError("select doit être une liste de colonnes.")
        if any(c not in headers for c in select):
            raise CapabilityError("Une colonne demandée n'existe pas.")
        columns = select
    if rename is None:
        rename = {}
    if not isinstance(rename, dict) or any(k not in headers or not isinstance(v, str) or not v for k, v in rename.items()):
        raise CapabilityError("rename doit renommer uniquement des colonnes existantes.")
    output_headers = [rename.get(c, c) for c in columns]
    if len(set(output_headers)) != len(output_headers):
        raise CapabilityError("Le renommage crée des en-têtes dupliqués.")
    if filter_column is not None:
        if filter_column not in headers:
            raise CapabilityError("Colonne de filtre inexistante.")
        rows = [row for row in rows if row.get(filter_column) == str(equals)]
    output = io.StringIO(newline="")
    writer = csv.DictWriter(output, fieldnames=output_headers, extrasaction="ignore", lineterminator="\n")
    writer.writeheader()
    for row in rows:
        writer.writerow({rename.get(c, c): row.get(c, "") for c in columns})
    result = output.getvalue()
    if len(result.encode("utf-8")) > MAX_BYTES:
        raise CapabilityError("CSV transformé trop volumineux.")
    return {"headers": output_headers, "rows": len(rows), "csv": result}


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _load_connectors():
    raw = os.environ.get("IBRA_CONNECTORS_JSON", "{}")
    try:
        connectors = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise CapabilityError("IBRA_CONNECTORS_JSON n'est pas un JSON valide.") from exc
    if not isinstance(connectors, dict):
        raise CapabilityError("IBRA_CONNECTORS_JSON doit être un objet.")
    return connectors


def _validate_public_https_base(base_url):
    parsed = urllib.parse.urlsplit(base_url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise CapabilityError("Le connecteur doit utiliser une URL HTTPS sans identifiants, query ni fragment.")
    host = parsed.hostname.lower()
    if host in ("localhost", "localhost.localdomain") or host.endswith(".localhost") or host.endswith(".local"):
        raise CapabilityError("Hôte local interdit.")
    try:
        ip = ipaddress.ip_address(host)
        if not ip.is_global:
            raise CapabilityError("Adresse IP non publique interdite.")
    except ValueError:
        # Noms DNS autorisés uniquement s'ils ont été inscrits explicitement par l'administrateur.
        if "." not in host or host.startswith(".") or host.endswith("."):
            raise CapabilityError("Nom d'hôte invalide.")
    return parsed


def connector_request(tool_name, connector_id, method, path, payload=None):
    permission = "connector:" + str(connector_id)
    require_permission(tool_name, permission)
    if not isinstance(connector_id, str) or not re.fullmatch(r"[a-z][a-z0-9_]{1,47}", connector_id):
        raise CapabilityError("Identifiant de connecteur invalide.")
    connectors = _load_connectors()
    config = connectors.get(connector_id)
    if not isinstance(config, dict):
        raise CapabilityError("Connecteur non configuré.")
    base = config.get("base_url")
    parsed = _validate_public_https_base(base if isinstance(base, str) else "")
    methods = config.get("methods", [])
    paths = config.get("paths", [])
    if not isinstance(methods, list) or method not in methods or method not in ("GET", "POST"):
        raise CapabilityError("Méthode HTTP non autorisée pour ce connecteur.")
    if not isinstance(paths, list) or not isinstance(path, str) or path not in paths:
        raise CapabilityError("Chemin API non autorisé pour ce connecteur.")
    if not path.startswith("/") or "?" in path or "#" in path or ".." in path or "\\" in path:
        raise CapabilityError("Le chemin doit être un chemin API exact, sans query ni traversal.")
    # Aucun hôte ni chemin libre : l'URL est construite uniquement à partir de la configuration.
    base_path = parsed.path.rstrip("/")
    url = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, base_path + path, "", ""))
    body = None
    headers = {"Accept": "application/json", "User-Agent": "IBRA-Capability-Broker/1.0"}
    if method == "POST":
        if payload is None or _json_size(payload) > MAX_BYTES:
            raise CapabilityError("POST exige un payload JSON de 300 Ko maximum.")
        body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    elif payload is not None:
        raise CapabilityError("GET n'accepte pas de payload.")
    token_env = config.get("token_env")
    if token_env is not None:
        if not isinstance(token_env, str) or not re.fullmatch(r"IBRA_CONNECTOR_[A-Z0-9_]+_TOKEN", token_env):
            raise CapabilityError("Nom de variable de secret invalide.")
        secret = os.environ.get(token_env, "")
        if not secret:
            raise CapabilityError("Secret du connecteur manquant dans l'environnement.")
        token_header = config.get("token_header", "Authorization")
        token_prefix = config.get("token_prefix", "Bearer ")
        if (not isinstance(token_header, str)
                or not re.fullmatch(r"[A-Za-z][A-Za-z0-9-]{0,63}", token_header)
                or token_header.lower() in {"host", "content-length", "connection",
                                            "transfer-encoding", "cookie", "set-cookie"}):
            raise CapabilityError("En-tête de secret non autorisé.")
        if not isinstance(token_prefix, str) or len(token_prefix) > 30:
            raise CapabilityError("Préfixe du secret invalide.")
        headers[token_header] = token_prefix + secret
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    opener = urllib.request.build_opener(_NoRedirect())
    try:
        with opener.open(request, timeout=10) as response:
            raw = response.read(MAX_BYTES + 1)
    except urllib.error.HTTPError as exc:
        # Ne suit jamais les redirections et ne divulgue pas le corps de réponse d'erreur.
        raise CapabilityError("Connecteur HTTP refusé ou en erreur : " + str(exc.code)) from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise CapabilityError("Connecteur indisponible : " + type(exc).__name__) from exc
    if len(raw) > MAX_BYTES:
        raise CapabilityError("Réponse du connecteur supérieure à 300 Ko.")
    try:
        result = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise CapabilityError("Le connecteur doit retourner du JSON valide.") from exc
    if _json_size(result) > MAX_BYTES:
        raise CapabilityError("Réponse JSON trop volumineuse.")
    return {"connector": connector_id, "status": "OK", "result": result}


class Handler(BaseHTTPRequestHandler):
    server_version = "IBRA-Capability-Broker/1.0"

    def log_message(self, fmt, *args):
        print("[IBRA-CAPABILITIES] " + (fmt % args))

    def send_json(self, status, payload):
        raw = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def authorized(self):
        return bool(TOKEN) and self.headers.get("Authorization", "") == "Bearer " + TOKEN

    def body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length < 1 or length > MAX_BYTES:
            raise CapabilityError("JSON absent ou supérieur à 300 Ko.")
        obj = json.loads(self.rfile.read(length).decode("utf-8"))
        if not isinstance(obj, dict):
            raise CapabilityError("Le corps doit être un objet JSON.")
        return obj

    def do_GET(self):
        if not self.authorized():
            return self.send_json(401, {"ok": False, "error": "UNAUTHORIZED"})
        if self.path == "/health":
            return self.send_json(200, {"ok": True, "service": "ibra-capability-broker"})
        return self.send_json(404, {"ok": False, "error": "NOT_FOUND"})

    def do_POST(self):
        if not self.authorized():
            return self.send_json(401, {"ok": False, "error": "UNAUTHORIZED"})
        try:
            body = self.body()
            route = urllib.parse.urlsplit(self.path).path
            tool = body.get("tool")
            if route == "/file/read":
                result = read_file(tool, body.get("path"))
            elif route == "/file/write":
                result = write_file(tool, body.get("path"), body.get("content"))
            elif route == "/data/json":
                if "data" not in body or not isinstance(body.get("operation"), str):
                    raise CapabilityError("data et operation sont obligatoires pour /data/json.")
                result = transform_json(tool, body["data"], body["operation"],
                                        body.get("fields"), body.get("mapping"),
                                        body.get("key"), body.get("equals"))
            elif route == "/data/csv":
                if not isinstance(body.get("csv"), str):
                    raise CapabilityError("csv est obligatoire pour /data/csv.")
                result = transform_csv(tool, body["csv"], body.get("select"),
                                       body.get("rename"), body.get("filter_column"),
                                       body.get("equals"))
            elif route == "/connector/request":
                result = connector_request(tool, body.get("connector"), body.get("method"),
                                           body.get("path"), body.get("payload"))
            else:
                return self.send_json(404, {"ok": False, "error": "NOT_FOUND"})
            return self.send_json(200, {"ok": True, **result})
        except (CapabilityError, tool_catalog.CatalogError, ValueError, KeyError, TypeError, OSError) as exc:
            return self.send_json(400, {"ok": False, "error": str(exc)})
        except Exception as exc:
            return self.send_json(500, {"ok": False, "error": type(exc).__name__})


def main():
    WORKSPACE.mkdir(parents=True, exist_ok=True)
    if not TOKEN:
        raise SystemExit("IBRA_CAPABILITY_TOKEN est obligatoire : le broker refuse de démarrer sans jeton.")
    if HOST not in ("127.0.0.1", "localhost", "::1"):
        raise SystemExit("Le broker de capacités doit rester sur localhost.")
    print(f"IBRA Capability Broker prêt sur http://{HOST}:{PORT}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
