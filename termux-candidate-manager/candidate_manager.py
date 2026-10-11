#!/usr/bin/env python3
"""IBRA Evolution candidate manager for Termux.

Uses the catalog's restricted code engine for candidate tests. Never uses exec/eval.
"""
from __future__ import annotations
import argparse, json, os, re, sys, tempfile
from pathlib import Path
from typing import Any

NAME_RE = re.compile(r"^[a-z][a-z0-9_-]{1,79}$")
HERE = Path(__file__).resolve().parent
PENDING = HERE / "pending_candidates"

def candidate_path(name: str) -> Path:
    if not isinstance(name, str) or not NAME_RE.fullmatch(name):
        raise ValueError("INVALID_NAME")
    path = (PENDING / (name + ".json")).resolve()
    if path.parent != PENDING.resolve():
        raise ValueError("INVALID_PATH")
    return path

def read_candidate(name: str) -> tuple[Path, dict[str, Any]]:
    path = candidate_path(name)
    if not path.is_file():
        raise FileNotFoundError("CANDIDATE_NOT_FOUND: " + name)
    row = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(row, dict) or row.get("name") != name:
        raise ValueError("INVALID_CANDIDATE_RECORD")
    return path, row

def write_record(path: Path, row: dict[str, Any]) -> None:
    fd, temp_name = tempfile.mkstemp(prefix=".candidate-", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(row, f, ensure_ascii=False, indent=2, allow_nan=False)
            f.write("\n")
        os.replace(temp_name, path)
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)

def list_candidates() -> list[dict[str, Any]]:
    PENDING.mkdir(parents=True, exist_ok=True)
    output = []
    for path in sorted(PENDING.glob("*.json")):
        try:
            row = json.loads(path.read_text(encoding="utf-8"))
            output.append({"name": row.get("name", path.stem), "status": row.get("status", "UNKNOWN"),
                "cases": len(row.get("cases", [])) if isinstance(row.get("cases"), list) else 0,
                "capabilities": row.get("capabilities", []), "file": path.name})
        except Exception as exc:
            output.append({"name": path.stem, "status": "INVALID_RECORD", "error": str(exc)})
    return output

def run_tests(row: dict[str, Any]) -> dict[str, Any]:
    code, cases = row.get("code"), row.get("cases")
    if not isinstance(code, str) or not code.strip():
        raise ValueError("INVALID_CODE")
    if not isinstance(cases, list) or not cases:
        raise ValueError("TESTS_REQUIRED")
    import tool_catalog
    engine = getattr(tool_catalog, "engine", None)
    execute = getattr(engine, "execute_code", None) if engine else None
    if not callable(execute):
        raise RuntimeError("RESTRICTED_ENGINE_UNAVAILABLE")
    results, passed = [], 0
    for i, case in enumerate(cases):
        if not isinstance(case, dict) or "input" not in case or "expected" not in case:
            results.append({"index": i, "passed": False, "error": "CASE_REQUIRES_INPUT_AND_EXPECTED"})
            continue
        try:
            actual = execute(code, case["input"])
            ok = actual == case["expected"]
            results.append({"index": i, "passed": ok, "expected": case["expected"], "actual": actual})
            passed += int(ok)
        except Exception as exc:
            results.append({"index": i, "passed": False, "error": type(exc).__name__ + ": " + str(exc)})
    return {"passed": passed, "total": len(cases), "all_passed": passed == len(cases), "results": results}

def review(name: str, validate: bool) -> dict[str, Any]:
    path, row = read_candidate(name)
    report = None
    if validate:
        report = run_tests(row)
        row["test_report"] = report
        row["status"] = "VALIDATED" if report["all_passed"] else "REJECTED"
        row["review_reason"] = "All tests passed." if report["all_passed"] else "At least one test failed."
    else:
        row["status"], row["review_reason"] = "REJECTED", "Rejected explicitly by operator."
    write_record(path, row)
    return {"ok": True, "name": name, "status": row["status"], "test_report": report}

def install(name: str) -> dict[str, Any]:
    path, row = read_candidate(name)
    if row.get("status") != "VALIDATED" or not isinstance(row.get("test_report"), dict) or row["test_report"].get("all_passed") is not True:
        raise ValueError("CANDIDATE_NOT_VALIDATED_WITH_PASSING_TESTS")
    import tool_catalog
    installer = getattr(tool_catalog, "install_tool", None)
    if not callable(installer):
        raise RuntimeError("CATALOG_INSTALLER_UNAVAILABLE")
    result = installer(name=row["name"], code=row["code"], tests=row["cases"],
        tuteur_review="All candidate tests passed using the restricted execution engine.",
        capabilities=row.get("capabilities", []))
    row["status"], row["install_result"] = "INSTALLED", result
    write_record(path, row)
    return {"ok": True, "name": name, "status": "INSTALLED", "catalog_result": result}

def rollback(name: str) -> dict[str, Any]:
    import tool_catalog
    fn = getattr(tool_catalog, "rollback_tool", None)
    if not callable(fn):
        raise RuntimeError("CATALOG_ROLLBACK_FUNCTION_UNAVAILABLE")
    return {"ok": True, "name": name, "rollback_result": fn(name)}

def main() -> int:
    p = argparse.ArgumentParser(description="IBRA Evolution candidate manager")
    sp = p.add_subparsers(dest="cmd", required=True)
    sp.add_parser("list")
    sh = sp.add_parser("show"); sh.add_argument("name")
    rv = sp.add_parser("review"); rv.add_argument("name"); g = rv.add_mutually_exclusive_group(required=True)
    g.add_argument("--validate", action="store_true"); g.add_argument("--reject", action="store_true")
    ins = sp.add_parser("install"); ins.add_argument("name")
    rb = sp.add_parser("rollback"); rb.add_argument("name")
    a = p.parse_args()
    try:
        if a.cmd == "list": result = {"ok": True, "candidates": list_candidates()}
        elif a.cmd == "show": result = {"ok": True, "candidate": read_candidate(a.name)[1]}
        elif a.cmd == "review": result = review(a.name, a.validate)
        elif a.cmd == "install": result = install(a.name)
        else: result = rollback(a.name)
        print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False)); return 0
    except Exception as exc:
        print(json.dumps({"ok": False, "error": type(exc).__name__ + ": " + str(exc)}, ensure_ascii=False)); return 1

if __name__ == "__main__":
    raise SystemExit(main())
