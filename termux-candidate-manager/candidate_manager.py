#!/usr/bin/env python3
"""IBRA Evolution candidate manager.

Run from the existing IBRA-Evolution Termux project directory.
Candidate code is tested with the same restricted engine used by tool_catalog;
it is never executed with Python exec/eval/subprocess.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
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
    record = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(record, dict) or record.get("name") != name:
        raise ValueError("INVALID_CANDIDATE_RECORD")
    return path, record


def write_record(path: Path, record: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=".candidate-", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(record, f, ensure_ascii=False, indent=2, allow_nan=False)
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
            output.append({
                "name": row.get("name", path.stem),
                "status": row.get("status", "UNKNOWN"),
                "cases": len(row.get("cases", [])) if isinstance(row.get("cases"), list) else 0,
                "capabilities": row.get("capabilities", []),
                "file": path.name,
            })
        except Exception as exc:
            output.append({"name": path.stem, "status": "INVALID_RECORD", "error": str(exc)})
    return output


def run_candidate_tests(record: dict[str, Any]) -> dict[str, Any]:
    code = record.get("code")
    cases = record.get("cases")
    if not isinstance(code, str) or not code.strip():
        raise ValueError("INVALID_CODE")
    if not isinstance(cases, list) or not cases:
        raise ValueError("TESTS_REQUIRED")

    # Use the restricted execution engine already used by the catalog.
    import tool_catalog  # local module in the existing Termux project
    engine = getattr(tool_catalog, "engine", None)
    if engine is None or not callable(getattr(engine, "execute_code", None)):
        raise RuntimeError("RESTRICTED_ENGINE_UNAVAILABLE")

    results = []
    passed = 0
    for index, case in enumerate(cases):
        if not isinstance(case, dict) or "input" not in case or "expected" not in case:
            results.append({"index": index, "passed": False, "error": "CASE_REQUIRES_INPUT_AND_EXPECTED"})
            continue
        try:
            actual = engine.execute_code(code, case["input"])
            expected = case["expected"]
            ok = actual == expected
            results.append({"index": index, "passed": ok, "expected": expected, "actual": actual})
            passed += int(ok)
        except Exception as exc:
            results.append({"index": index, "passed": False, "error": type(exc).__name__ + ": " + str(exc)})
    return {"passed": passed, "total": len(cases), "all_passed": passed == len(cases), "results": results}


def review_candidate(name: str, decision: str) -> dict[str, Any]:
    path, record = read_candidate(name)
    if decision == "validate":
        report = run_candidate_tests(record)
        record["test_report"] = report
        if not report["all_passed"]:
            record["status"] = "REJECTED"
            record["review_reason"] = "One or more tests failed."
        else:
            record["status"] = "VALIDATED"
            record["review_reason"] = "All candidate tests passed in the restricted engine."
    elif decision == "reject":
        record["status"] = "REJECTED"
        record["review_reason"] = "Rejected explicitly by operator."
        report = None
    else:
        raise ValueError("INVALID_DECISION")
    write_record(path, record)
    return {"ok": True, "name": name, "status": record["status"], "test_report": report}


def install_candidate(name: str) -> dict[str, Any]:
    _, record = read_candidate(name)
    if record.get("status") != "VALIDATED":
        raise ValueError("CANDIDATE_NOT_VALIDATED")
    report = record.get("test_report")
    if not isinstance(report, dict) or report.get("all_passed") is not True:
        raise ValueError("PASSING_TEST_REPORT_REQUIRED")
    import tool_catalog
    installer = getattr(tool_catalog, "install_tool", None)
    if not callable(installer):
        raise RuntimeError("CATALOG_INSTALLER_UNAVAILABLE")
    # Keep the catalog's own installation checks and versioning as the final gate.
    result = installer(
        name=record["name"],
        code=record["code"],
        tests=record["cases"],
        tuteur_review="Candidate tests passed in the restricted engine; reviewed by IBRA Evolution candidate manager.",
        capabilities=record.get("capabilities", []),
    )
    record["status"] = "INSTALLED"
    record["install_result"] = result
    write_record(candidate_path(name), record)
    return {"ok": True, "name": name, "status": "INSTALLED", "catalog_result": result}


def rollback_tool(name: str) -> Any:
    import tool_catalog
    rollback = getattr(tool_catalog, "rollback_tool", None)
    if not callable(rollback):
        raise RuntimeError("CATALOG_ROLLBACK_FUNCTION_UNAVAILABLE")
    return rollback(name)


def main() -> int:
    parser = argparse.ArgumentParser(description="Review and manage IBRA Evolution code candidates")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("list", help="list candidates")
    show = sub.add_parser("show", help="show a candidate's code and tests")
    show.add_argument("name")
    review = sub.add_parser("review", help="run candidate tests and set VALIDATED or REJECTED")
    review.add_argument("name")
    decision = review.add_mutually_exclusive_group(required=True)
    decision.add_argument("--validate", action="store_true")
    decision.add_argument("--reject", action="store_true")
    install = sub.add_parser("install", help="install a VALIDATED candidate into the catalog")
    install.add_argument("name")
    rollback = sub.add_parser("rollback", help="roll back a catalog tool to its previous version")
    rollback.add_argument("name")
    args = parser.parse_args()

    try:
        if args.command == "list":
            result = {"ok": True, "candidates": list_candidates()}
        elif args.command == "show":
            _, row = read_candidate(args.name)
            result = {"ok": True, "candidate": row}
        elif args.command == "review":
            result = review_candidate(args.name, "validate" if args.validate else "reject")
        elif args.command == "install":
            result = install_candidate(args.name)
        elif args.command == "rollback":
            result = {"ok": True, "name": args.name, "rollback_result": rollback_tool(args.name)}
        else:
            raise ValueError("UNKNOWN_COMMAND")
        print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))
        return 0
    except Exception as exc:
        print(json.dumps({"ok": False, "error": type(exc).__name__ + ": " + str(exc)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
