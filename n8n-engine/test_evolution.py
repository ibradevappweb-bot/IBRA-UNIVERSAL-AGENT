import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import evolution


class EvolutionTests(unittest.TestCase):
    def test_highest_priority_selected(self):
        goals = [
            {"id": "low", "priority": 1, "title": "Low"},
            {"id": "high", "priority": 9, "title": "High"},
        ]
        self.assertEqual(evolution.choose_goal(goals, set())["id"], "high")

    def test_completed_goal_is_skipped(self):
        goals = [
            {"id": "first", "priority": 9, "title": "First"},
            {"id": "next", "priority": 1, "title": "Next"},
        ]
        self.assertEqual(evolution.choose_goal(goals, {"first"})["id"], "next")

    def test_tie_break_is_deterministic(self):
        goals = [
            {"id": "z", "priority": 1, "title": "Z"},
            {"id": "a", "priority": 1, "title": "A"},
        ]
        self.assertEqual(evolution.choose_goal(goals, set())["id"], "a")

    def test_empty_backlog_returns_none(self):
        self.assertIsNone(evolution.choose_goal([], set()))

    def test_new_default_goals_are_added_to_existing_backlog(self):
        merged = evolution.merge_backlog(
            [{"id": "legacy-goal", "priority": 1, "title": "Legacy"}],
            set()
        )
        identifiers = [goal["id"] for goal in merged]
        self.assertIn("legacy-goal", identifiers)
        self.assertIn("local-svg-image-creator", identifiers)
        self.assertEqual(len(identifiers), len(set(identifiers)))

    def test_completed_default_goals_are_not_readded(self):
        merged = evolution.merge_backlog([], {"local-svg-image-creator"})
        self.assertNotIn("local-svg-image-creator", [goal["id"] for goal in merged])

    def test_valid_candidate_runs_real_tests(self):
        candidate = {
            "name": "add_values",
            "summary": "Add two values",
            "code": "def run(data):\n    return data['a'] + data['b']\n",
            "tests": [
                {"input": {"a": 2, "b": 3}, "expected": 5},
                {"input": {"a": -1, "b": 1}, "expected": 0},
                {"input": {"a": 0, "b": 0}, "expected": 0},
            ],
        }
        result = evolution.execute_candidate(candidate)
        self.assertTrue(result["all_tests_passed"])
        self.assertEqual(len(result["tests"]), 3)

    def test_failed_case_prevents_approval(self):
        candidate = {
            "name": "subtract_values",
            "summary": "Intentionally wrong for expected output",
            "code": "def run(data):\n    return data['a'] - data['b']\n",
            "tests": [
                {"input": {"a": 2, "b": 3}, "expected": 5},
                {"input": {"a": 1, "b": 1}, "expected": 0},
                {"input": {"a": 0, "b": 0}, "expected": 0},
            ],
        }
        result = evolution.execute_candidate(candidate)
        self.assertFalse(result["all_tests_passed"])
        with patch.object(evolution, "ask_gemini", return_value={
            "approved": True, "reasons": ["looks fine"], "risks": []}):
            review = evolution.review_candidate({"id": "demo"}, result, [])
        self.assertFalse(review["approved"])


    def test_tuteur_must_echo_exact_requested_capabilities(self):
        candidate = {
            "name": "read_helper",
            "summary": "Needs file read permission",
            "code": "def run(data):\n    return data['value']\n",
            "capabilities": ["file.read"],
            "tests": [
                {"input": {"value": 1}, "expected": 1},
                {"input": {"value": 2}, "expected": 2},
                {"input": {"value": 3}, "expected": 3},
            ],
        }
        tested = evolution.execute_candidate(candidate)
        with patch.object(evolution, "ask_gemini", return_value={
            "approved": True, "reasons": ["ok"], "risks": [],
            "capabilities": [],
        }):
            with self.assertRaisesRegex(evolution.EvolutionError, "do not exactly match"):
                evolution.review_candidate({"id": "read-helper"}, tested, [])

    def test_imports_rejected(self):
        candidate = {
            "name": "unsafe_tool",
            "summary": "must be rejected",
            "code": "import os\ndef run(data):\n    return 1\n",
            "tests": [
                {"input": {}, "expected": 1},
                {"input": {}, "expected": 1},
                {"input": {}, "expected": 1},
            ],
        }
        with self.assertRaises(ValueError):
            evolution.execute_candidate(candidate)

    def test_missing_gemini_key_fails_explicitly(self):
        with patch.dict("os.environ", {}, clear=True):
            with self.assertRaisesRegex(evolution.EvolutionError, "GEMINI_API_KEY"):
                evolution.ask_gemini("test")

    def test_run_cycle_installs_approved_tool_into_versioned_catalog(self):
        svg = '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512"><rect width="512" height="512" fill="#14213d"/><circle cx="256" cy="256" r="90" fill="#fca311"/></svg>'
        candidate = {
            "name": "image_svg_creator",
            "summary": "Create a local vector image",
            "code": "def run(data):\n    return " + repr(svg) + "\n",
            "capabilities": ["file.write"],
            "tests": [
                {"input": {"subject": "sunset"}, "expected": svg},
                {"input": {"subject": "moon"}, "expected": svg},
                {"input": {"subject": "mountain"}, "expected": svg},
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            with patch.object(evolution, "DATA_DIR", base / "data"), \
                 patch.object(evolution, "STATE_PATH", base / "data" / "state.json"), \
                 patch.object(evolution, "PROPOSALS_DIR", base / "data" / "proposals"), \
                 patch.object(evolution.tool_catalog, "ROOT", base / "catalog"), \
                 patch.object(evolution, "research", return_value=[]), \
                 patch.object(evolution, "ask_gemini", side_effect=[
                     candidate,
                     {"approved": True, "reasons": ["tests passed"], "risks": [], "capabilities": ["file.write"]},
                 ]):
                record = evolution.run_cycle()
                self.assertEqual(record["status"], "INSTALLED")
                self.assertEqual(record["promotion"], "INSTALLED_IN_VERSIONED_CATALOG")
                self.assertEqual(record["catalog_installation"]["active_version"], 1)
                self.assertTrue((base / "data" / "proposals").exists())
                saved_state = evolution.json.loads(
                    (base / "data" / "state.json").read_text(encoding="utf-8"))
                self.assertEqual(len(saved_state["cycles"]), 1)
                self.assertEqual(saved_state["completed"], ["local-svg-image-creator"])
                installed = evolution.tool_catalog.run_tool("image_svg_creator", {"subject": "test"})
                self.assertEqual(installed["result"], svg)
                self.assertEqual(installed["capabilities"], ["file.write"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
