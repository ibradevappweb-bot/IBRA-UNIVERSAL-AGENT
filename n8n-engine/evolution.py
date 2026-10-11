"""Autonomous tool-building cycle for IBRA with reviewed catalog installation.

Required environment: GEMINI_API_KEY. Optional: EXA_API_KEY.
Generated code is only interpreted by engine.py's restricted AST evaluator.
"""
import json
import os
import re
import time
import urllib.error
import urllib.request
from pathlib import Path

import engine
import tool_catalog

ROOT = Path(__file__).resolve().parent
DATA_DIR = ROOT / "evolution-data"
STATE_PATH = DATA_DIR / "state.json"
PROPOSALS_DIR = DATA_DIR / "proposals"
MODEL = os.environ.get("IBRA_GEMINI_MODEL", "gemini-2.5-flash")
GOALS = [
    {"id": "json-validator", "priority": 100, "title": "Validate required JSON fields",
     "objective": "Create a small deterministic run(data) helper that reports missing required keys."},
    {"id": "text-normalizer", "priority": 80, "title": "Normalize whitespace",
     "objective": "Create a deterministic run(data) helper that collapses repeated whitespace in a text value."},
    {"id": "numeric-guard", "priority": 60, "title": "Validate numeric input",
     "objective": "Create a deterministic run(data) helper that rejects booleans and non-finite numeric values."},
]


class EvolutionError(RuntimeError):
    pass


def choose_goal(goals, completed):
    pending = [goal for goal in goals if goal.get("id") not in completed]
    if not pending:
        return None
    pending.sort(key=lambda item: (-int(item.get("priority", 0)), str(item.get("id", ""))))
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,60}", str(pending[0].get("id", ""))):
        raise EvolutionError("Invalid goal identifier")
    return pending[0]


def read_state():
    if not STATE_PATH.exists():
        return {"schema_version": 1, "backlog": GOALS, "completed": [], "cycles": []}
    try:
        state = json.loads(STATE_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise EvolutionError("Evolution state is unreadable: " + str(exc)) from exc
    if not isinstance(state, dict) or state.get("schema_version", 1) != 1:
        raise EvolutionError("Evolution state schema is invalid")
    return state


def request_json(url, payload, headers=None):
    raw = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
    request = urllib.request.Request(
        url, data=raw,
        headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=35) as response:
            data = response.read(1_500_000)
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise EvolutionError("External request failed: " + str(exc)) from exc
    try:
        result = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise EvolutionError("External service returned invalid JSON") from exc
    if not isinstance(result, dict):
        raise EvolutionError("External service response must be an object")
    return result


def research(goal):
    """Research real sources through Exa only when its key is configured."""
    key = os.environ.get("EXA_API_KEY", "")
    if not key:
        return []
    result = request_json(
        "https://api.exa.ai/search",
        {"query": goal["title"] + " Python standard library secure implementation",
         "numResults": 4, "type": "auto",
         "contents": {"text": {"maxCharacters": 900}}},
        {"x-api-key": key})
    found = []
    for item in result.get("results", [])[:4]:
        if isinstance(item, dict) and isinstance(item.get("url"), str):
            found.append({"title": str(item.get("title", ""))[:180],
                          "url": item["url"][:1000],
                          "text": str(item.get("text", ""))[:900]})
    return found


def ask_gemini(prompt):
    key = os.environ.get("GEMINI_API_KEY", "")
    if not key:
        raise EvolutionError("GEMINI_API_KEY is missing; no proposal has been generated")
    endpoint = ("https://generativelanguage.googleapis.com/v1beta/models/"
                + MODEL + ":generateContent")
    result = request_json(endpoint, {
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0.1, "maxOutputTokens": 5000,
                             "responseMimeType": "application/json"}
    }, {"x-goog-api-key": key})
    try:
        text = result["candidates"][0]["content"]["parts"][0]["text"]
        parsed = json.loads(text)
    except (KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
        raise EvolutionError("Gemini did not return a valid JSON candidate") from exc
    if not isinstance(parsed, dict):
        raise EvolutionError("Gemini response must be a JSON object")
    return parsed


def candidate_prompt(goal, sources):
    return (
        "Return only JSON with keys name, summary, code, tests. The code must be exactly one "
        "function def run(data): using the restricted syntax accepted by the supplied engine: "
        "simple assignments, if/else, return, literals, arithmetic, comparisons, subscripts, and "
        "calls only to abs/min/max/round/len/sum. No imports, attributes, loops, comprehensions, "
        "or file/network access. Supply 3 to 5 test objects each with input and expected. Make "
        "the helper deterministic and small. Never claim tests were executed.\nOBJECTIVE: "
        + json.dumps(goal, ensure_ascii=False) + "\nRESEARCH SOURCES: "
        + json.dumps(sources, ensure_ascii=False))


def execute_candidate(candidate):
    name = candidate.get("name")
    code = candidate.get("code")
    cases = candidate.get("tests")
    if not isinstance(name, str) or not re.fullmatch(r"[a-z][a-z0-9_]{1,47}", name):
        raise EvolutionError("Candidate name is invalid")
    if not isinstance(code, str) or len(code) > 10000:
        raise EvolutionError("Candidate code is missing or too large")
    if not isinstance(cases, list) or not 3 <= len(cases) <= 5:
        raise EvolutionError("Candidate must contain 3 to 5 test cases")
    engine.parse_code(code)
    outcomes = []
    for index, case in enumerate(cases, 1):
        if not isinstance(case, dict) or "input" not in case or "expected" not in case:
            raise EvolutionError("Test case " + str(index) + " is malformed")
        try:
            actual = engine.execute_code(code, case["input"])
            json.dumps(actual, ensure_ascii=False, allow_nan=False)
            passed = type(actual) is type(case["expected"]) and actual == case["expected"]
            outcomes.append({"index": index, "passed": passed,
                             "expected": case["expected"], "actual": actual})
        except Exception as exc:
            outcomes.append({"index": index, "passed": False,
                             "expected": case.get("expected"),
                             "error": type(exc).__name__ + ": " + str(exc)})
    return {"name": name, "summary": str(candidate.get("summary", ""))[:1000],
            "code": code, "tests": outcomes,
            "all_tests_passed": bool(outcomes) and all(test["passed"] for test in outcomes)}


def review_candidate(goal, tested, sources):
    review = ask_gemini(
        "You are the independent IBRA TUTEUR, not the code author. Return JSON with keys "
        "approved (boolean), reasons (array), risks (array). Assess correctness, goal fit, edge cases, "
        "determinism, and safety. Approve only if all supplied tests pass and code stays in the "
        "restricted subset. Do not assume tests ran beyond the results shown.\n"
        + json.dumps({"goal": goal, "tested_proposal": tested, "research_sources": sources},
                     ensure_ascii=False))
    if type(review.get("approved")) is not bool:
        raise EvolutionError("TUTEUR response is missing boolean approved")
    if not isinstance(review.get("reasons", []), list) or not isinstance(review.get("risks", []), list):
        raise EvolutionError("TUTEUR reasons and risks must be arrays")
    review["approved"] = review["approved"] and tested["all_tests_passed"]
    return review


def run_cycle():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    PROPOSALS_DIR.mkdir(parents=True, exist_ok=True)
    state = read_state()
    goals = state.get("backlog", GOALS)
    completed = set(state.get("completed", []))
    goal = choose_goal(goals, completed)
    if goal is None:
        raise EvolutionError("No pending goals. A new goal needs to be added to the backlog.")
    sources = research(goal)
    candidate = ask_gemini(candidate_prompt(goal, sources))
    tested = execute_candidate(candidate)
    tuteur = review_candidate(goal, tested, sources)
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    cycle_id = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    installation = None
    installation_error = None
    if tuteur["approved"] and tested["all_tests_passed"]:
        try:
            # Re-run the tests at the catalogue boundary; keep previous versions for rollback.
            installation = tool_catalog.install_tool(
                tested["name"], tested["code"], candidate["tests"], tuteur)
            status = "INSTALLED"
            completed.add(goal["id"])
        except Exception as exc:
            status = "INSTALLATION_FAILED"
            installation_error = type(exc).__name__ + ": " + str(exc)
    else:
        status = "REJECTED"

    record = {
        "cycle_id": cycle_id, "created_at_utc": now, "goal": goal,
        "research_sources": sources, "proposal": tested, "tuteur": tuteur,
        "catalog_installation": installation,
        "installation_error": installation_error,
        "status": status,
        "promotion": "INSTALLED_IN_VERSIONED_CATALOG" if installation else "NOT_INSTALLED",
        "note": "Only restricted-AST tools with passing tests and an approving TUTEUR review are installed. The engine source and Android app are never rewritten."
    }
    target = PROPOSALS_DIR / (cycle_id + ".json")
    # Atomic write prevents a partial audit record on interruption.
    temporary = target.with_suffix(".tmp")
    temporary.write_text(json.dumps(record, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
                         encoding="utf-8")
    temporary.replace(target)
    state["schema_version"] = 1
    state["backlog"] = goals
    state["completed"] = sorted(completed)
    state.setdefault("cycles", []).append({
        "cycle_id": cycle_id, "goal_id": goal["id"], "status": status,
        "proposal_file": str(target.relative_to(DATA_DIR.parent))
    })
    state["cycles"] = state["cycles"][-100:]
    state["last_cycle_utc"] = now
    tmp_state = STATE_PATH.with_suffix(".tmp")
    tmp_state.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp_state.replace(STATE_PATH)
    return record


def status():
    state = read_state()
    return {"last_cycle_utc": state.get("last_cycle_utc"),
            "cycles": state.get("cycles", []),
            "pending_goals": [g["id"] for g in state.get("backlog", GOALS)
                              if g.get("id") not in set(state.get("completed", []))]}


if __name__ == "__main__":
    import sys
    try:
        if sys.argv[1:] == ["--status"]:
            print(json.dumps(status(), ensure_ascii=False, indent=2))
        elif not sys.argv[1:]:
            print(json.dumps({"ok": True, "cycle": run_cycle()}, ensure_ascii=False, indent=2))
        else:
            raise EvolutionError("Usage: python evolution.py [--status]")
    except Exception as exc:
        print(json.dumps({"ok": False, "error": type(exc).__name__ + ": " + str(exc)},
                         ensure_ascii=False), flush=True)
        raise SystemExit(1)
