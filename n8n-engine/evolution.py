"""Safe first-stage planner for IBRA's autonomous evolution cycle."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STATE = ROOT / "evolution-data" / "state.json"
GOALS = [
    {"id": "json-validator", "priority": 100, "title": "Validate JSON inputs"},
    {"id": "text-normalizer", "priority": 80, "title": "Normalize whitespace"},
    {"id": "numeric-guard", "priority": 60, "title": "Validate numeric values"},
]


def choose_goal(goals, completed):
    pending = [goal for goal in goals if goal["id"] not in completed]
    if not pending:
        return None
    return sorted(pending, key=lambda item: (-item["priority"], item["id"]))[0]


def main():
    STATE.parent.mkdir(parents=True, exist_ok=True)
    state = json.loads(STATE.read_text()) if STATE.exists() else {"completed": [], "cycles": []}
    goal = choose_goal(state.get("backlog", GOALS), set(state.get("completed", [])))
    result = {"goal": goal, "status": "NO_PENDING_GOALS" if goal is None else "PLANNED"}
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
