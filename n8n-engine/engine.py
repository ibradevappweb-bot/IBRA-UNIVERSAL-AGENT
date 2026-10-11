#!/usr/bin/env python3
"""Moteur d'outils Python pour n8n, bibliothèque standard uniquement.

Les outils sont analysés puis interprétés par une petite machine AST.
Le code généré n'est jamais transmis à exec/eval et ne peut pas importer de modules.
"""
import ast
import json
import os
import re
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

HOST = os.environ.get("IBRA_ENGINE_HOST", "127.0.0.1")
PORT = int(os.environ.get("IBRA_ENGINE_PORT", "8766"))
TOKEN = os.environ.get("IBRA_ENGINE_TOKEN", "")
ROOT = Path(os.environ.get("IBRA_ENGINE_DATA", Path(__file__).resolve().parent / "data"))
DRAFTS = ROOT / "drafts"
TOOLS = ROOT / "tools"
NAME_RE = re.compile(r"^[a-z][a-z0-9_]{1,47}$")
CALLS = {"abs": abs, "min": min, "max": max, "round": round, "len": len, "sum": sum}
BINOPS = {ast.Add: lambda a,b:a+b, ast.Sub:lambda a,b:a-b, ast.Mult:lambda a,b:a*b,
          ast.Div:lambda a,b:a/b, ast.FloorDiv:lambda a,b:a//b, ast.Mod:lambda a,b:a%b,
          ast.Pow:lambda a,b:a**b}
CMPOPS = {ast.Eq:lambda a,b:a==b, ast.NotEq:lambda a,b:a!=b, ast.Lt:lambda a,b:a<b,
          ast.LtE:lambda a,b:a<=b, ast.Gt:lambda a,b:a>b, ast.GtE:lambda a,b:a>=b,
          ast.In:lambda a,b:a in b, ast.NotIn:lambda a,b:a not in b}

def stamp():
    return datetime.now(timezone.utc).isoformat()

def ensure_dirs():
    DRAFTS.mkdir(parents=True, exist_ok=True)
    TOOLS.mkdir(parents=True, exist_ok=True)

def validate_name(name):
    if not isinstance(name, str) or not NAME_RE.fullmatch(name):
        raise ValueError("Nom invalide : 2 à 48 caractères, minuscules, chiffres ou _ ; commencer par une lettre.")
    return name

def parse_code(code):
    if not isinstance(code, str) or not code.strip() or len(code) > 20000:
        raise ValueError("Le code doit être non vide et faire 20 000 caractères maximum.")
    try:
        tree = ast.parse(code)
    except SyntaxError as exc:
        raise ValueError(f"Syntaxe Python invalide ligne {exc.lineno}: {exc.msg}") from exc
    if len(tree.body) != 1 or not isinstance(tree.body[0], ast.FunctionDef):
        raise ValueError("Le code doit contenir uniquement def run(data):")
    fn = tree.body[0]
    if fn.name != "run" or len(fn.args.args) != 1 or fn.args.args[0].arg != "data":
        raise ValueError("Signature obligatoire : def run(data):")
    if fn.decorator_list or fn.args.vararg or fn.args.kwarg or fn.args.kwonlyargs or fn.args.defaults:
        raise ValueError("Décorateurs et arguments supplémentaires interdits.")
    allowed = (ast.Module, ast.FunctionDef, ast.arguments, ast.arg, ast.Return, ast.Assign,
               ast.If, ast.Pass, ast.Name, ast.Load, ast.Store, ast.Constant, ast.List,
               ast.Tuple, ast.Dict, ast.BinOp, ast.UnaryOp, ast.BoolOp, ast.Compare,
               ast.IfExp, ast.Call, ast.Subscript, ast.Add, ast.Sub, ast.Mult, ast.Div,
               ast.FloorDiv, ast.Mod, ast.Pow, ast.USub, ast.UAdd, ast.Not, ast.And,
               ast.Or, ast.Eq, ast.NotEq, ast.Lt, ast.LtE, ast.Gt, ast.GtE, ast.In,
               ast.NotIn, ast.keyword, ast.Slice)
    for node in ast.walk(tree):
        if not isinstance(node, allowed):
            raise ValueError("Élément Python non autorisé : " + type(node).__name__)
        if isinstance(node, ast.Name) and node.id.startswith("_"):
            raise ValueError("Les noms commençant par _ sont interdits.")
        if isinstance(node, ast.Call):
            if not isinstance(node.func, ast.Name) or node.func.id not in CALLS or node.keywords:
                raise ValueError("Appel interdit. Fonctions autorisées : abs, min, max, round, len, sum.")
    return tree, code

def eval_expr(node, env):
    if isinstance(node, ast.Constant):
        return node.value
    if isinstance(node, ast.Name):
        if node.id not in env:
            raise ValueError("Variable inconnue : " + node.id)
        return env[node.id]
    if isinstance(node, ast.List):
        return [eval_expr(x, env) for x in node.elts]
    if isinstance(node, ast.Tuple):
        return tuple(eval_expr(x, env) for x in node.elts)
    if isinstance(node, ast.Dict):
        return {eval_expr(k, env): eval_expr(v, env) for k, v in zip(node.keys, node.values)}
    if isinstance(node, ast.Subscript):
        return eval_expr(node.value, env)[eval_expr(node.slice, env)]
    if isinstance(node, ast.Slice):
        return slice(eval_expr(node.lower, env) if node.lower else None,
                     eval_expr(node.upper, env) if node.upper else None,
                     eval_expr(node.step, env) if node.step else None)
    if isinstance(node, ast.BinOp) and type(node.op) in BINOPS:
        a, b = eval_expr(node.left, env), eval_expr(node.right, env)
        if isinstance(node.op, ast.Pow) and (not isinstance(b, (int, float)) or abs(b) > 1000):
            raise ValueError("Exposant hors limite.")
        result = BINOPS[type(node.op)](a, b)
        if isinstance(result, float) and (result != result or abs(result) == float("inf")):
            raise ValueError("Résultat numérique non fini.")
        return result
    if isinstance(node, ast.UnaryOp):
        value = eval_expr(node.operand, env)
        if isinstance(node.op, ast.USub): return -value
        if isinstance(node.op, ast.UAdd): return +value
        if isinstance(node.op, ast.Not): return not value
    if isinstance(node, ast.BoolOp):
        values = [eval_expr(x, env) for x in node.values]
        return all(values) if isinstance(node.op, ast.And) else any(values)
    if isinstance(node, ast.Compare):
        left = eval_expr(node.left, env)
        for op, comparator in zip(node.ops, node.comparators):
            right = eval_expr(comparator, env)
            if type(op) not in CMPOPS or not CMPOPS[type(op)](left, right):
                return False
            left = right
        return True
    if isinstance(node, ast.IfExp):
        return eval_expr(node.body if eval_expr(node.test, env) else node.orelse, env)
    if isinstance(node, ast.Call):
        return CALLS[node.func.id](*(eval_expr(x, env) for x in node.args))
    raise ValueError("Expression non pris en charge : " + type(node).__name__)

def execute_code(code, data):
    tree, _ = parse_code(code)
    env = {"data": data}
    fn = tree.body[0]
    def run_block(statements):
        for statement in statements:
            if isinstance(statement, ast.Assign):
                value = eval_expr(statement.value, env)
                for target in statement.targets:
                    if not isinstance(target, ast.Name) or target.id.startswith("_"):
                        raise ValueError("Affectation limitée à un nom simple.")
                    env[target.id] = value
            elif isinstance(statement, ast.If):
                branch = statement.body if eval_expr(statement.test, env) else statement.orelse
                returned, value = run_block(branch)
                if returned: return True, value
            elif isinstance(statement, ast.Return):
                return True, eval_expr(statement.value, env) if statement.value else None
            elif isinstance(statement, ast.Pass):
                continue
            else:
                raise ValueError("Instruction non prise en charge : " + type(statement).__name__)
        return False, None
    returned, result = run_block(fn.body)
    if not returned:
        raise ValueError("run(data) doit retourner une valeur.")
    # Vérifie que la valeur peut être transportée en JSON.
    json.dumps(result, ensure_ascii=False, allow_nan=False)
    return result

def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))

def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
    tmp.replace(path)

def path_for(folder, name):
    return folder / (validate_name(name) + ".json")

def list_records(folder):
    records = []
    for p in sorted(folder.glob("*.json")):
        try:
            item = read_json(p)
            records.append({k:item.get(k) for k in ("name","status","created_at","tested_at","promoted_at","last_test") if k in item})
        except Exception:
            records.append({"name":p.stem,"status":"CORRUPTED"})
    return records

class Handler(BaseHTTPRequestHandler):
    server_version = "IBRA-Engine/1.0"
    def log_message(self, fmt, *args):
        print("[IBRA-ENGINE] " + (fmt % args))
    def authorized(self):
        return not TOKEN or self.headers.get("Authorization", "") == "Bearer " + TOKEN
    def send_json(self, status, obj):
        raw = json.dumps(obj, ensure_ascii=False, allow_nan=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)
    def body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length < 1 or length > 300000: raise ValueError("JSON absent ou supérieur à 300 Ko.")
        obj = json.loads(self.rfile.read(length).decode("utf-8"))
        if not isinstance(obj, dict): raise ValueError("Le JSON doit être un objet.")
        return obj
    def do_GET(self):
        if not self.authorized(): return self.send_json(401, {"ok":False,"error":"UNAUTHORIZED"})
        route = urlparse(self.path).path
        if route == "/health": return self.send_json(200, {"ok":True,"service":"ibra-python-engine","version":"1.0"})
        if route == "/drafts": return self.send_json(200, {"ok":True,"drafts":list_records(DRAFTS)})
        if route == "/tools": return self.send_json(200, {"ok":True,"tools":list_records(TOOLS)})
        return self.send_json(404, {"ok":False,"error":"NOT_FOUND"})
    def do_POST(self):
        if not self.authorized(): return self.send_json(401, {"ok":False,"error":"UNAUTHORIZED"})
        try:
            body, route = self.body(), urlparse(self.path).path
            if route == "/draft":
                name = validate_name(body.get("name"))
                _, code = parse_code(body.get("code"))
                record = {"name":name,"code":code,"status":"DRAFT","created_at":stamp(),"last_test":None}
                write_json(path_for(DRAFTS,name), record)
                return self.send_json(201, {"ok":True,"name":name,"status":"DRAFT"})
            if route == "/test":
                name = validate_name(body.get("name"))
                p = path_for(DRAFTS,name)
                if not p.exists(): return self.send_json(404, {"ok":False,"error":"Brouillon introuvable"})
                record = read_json(p)
                result = execute_code(record["code"], body.get("data", {}))
                record.update({"status":"TESTED","tested_at":stamp(),"last_test":{"ok":True,"result":result}})
                write_json(p,record)
                return self.send_json(200, {"ok":True,"name":name,"status":"TESTED","result":result})
            if route == "/promote":
                name = validate_name(body.get("name"))
                p = path_for(DRAFTS,name)
                if not p.exists(): return self.send_json(404, {"ok":False,"error":"Brouillon introuvable"})
                record = read_json(p)
                if record.get("status") != "TESTED" or not record.get("last_test",{}).get("ok"):
                    raise ValueError("Promotion refusée : /test doit réussir avant /promote.")
                record.update({"status":"PROMOTED","promoted_at":stamp()})
                write_json(path_for(TOOLS,name),record)
                write_json(p,record)
                return self.send_json(200, {"ok":True,"name":name,"status":"PROMOTED"})
            if route == "/run":
                name = validate_name(body.get("name"))
                p = path_for(TOOLS,name)
                if not p.exists(): return self.send_json(404, {"ok":False,"error":"Outil promu introuvable"})
                result = execute_code(read_json(p)["code"],body.get("data",{}))
                return self.send_json(200, {"ok":True,"name":name,"result":result})
            return self.send_json(404, {"ok":False,"error":"NOT_FOUND"})
        except (ValueError, KeyError, TypeError, ZeroDivisionError, OverflowError) as exc:
            return self.send_json(400, {"ok":False,"error":str(exc)})
        except Exception as exc:
            return self.send_json(422, {"ok":False,"error":str(exc)})

def main():
    ensure_dirs()
    if HOST not in ("127.0.0.1","localhost","::1") and not TOKEN:
        raise SystemExit("Définissez IBRA_ENGINE_TOKEN avant toute écoute hors localhost.")
    print(f"IBRA Python Engine prêt sur http://{HOST}:{PORT}")
    ThreadingHTTPServer((HOST,PORT),Handler).serve_forever()

if __name__ == "__main__":
    main()
