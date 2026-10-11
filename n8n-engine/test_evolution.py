import unittest
import evolution


class EvolutionTests(unittest.TestCase):
    def test_choose_highest_priority_deterministically(self):
        backlog = [
            {"id": "z-goal", "priority": 10},
            {"id": "a-goal", "priority": 10},
            {"id": "urgent", "priority": 20},
        ]
        self.assertEqual(evolution.choose_next(backlog, set())["id"], "urgent")
        self.assertEqual(evolution.choose_next(backlog, {"urgent"})["id"], "a-goal")

    def test_no_goals_left_fails_explicitly(self):
        with self.assertRaises(evolution.EvolutionError):
            evolution.choose_next([{"id": "done", "priority": 1}], {"done"})

    def test_invalid_goal_id_rejected(self):
        with self.assertRaises(evolution.EvolutionError):
            evolution.choose_next([{"id": "../bad", "priority": 1}], set())

    def test_candidate_validated_in_restricted_interpreter(self):
        candidate = {
            "name": "add_values",
            "summary": "Adds two numbers.",
            "code": "def run(data):\n    return data['a'] + data['b']\n",
            "tests": [
                {"input": {"a": 1, "b": 2}, "expected": 3},
                {"input": {"a": -1, "b": 4}, "expected": 3},
                {"input": {"a": 0, "b": 0}, "expected": 0},
            ],
        }
        result = evolution.validate_and_test(candidate)
        self.assertTrue(result["all_tests_passed"])
        self.assertEqual(len(result["tests"]), 3)

    def test_failed_test_is_recorded_not_hidden(self):
        candidate = {
            "name": "add_values",
            "summary": "Deliberately wrong.",
            "code": "def run(data):\n    return data['a'] - data['b']\n",
            "tests": [
                {"input": {"a": 1, "b": 2}, "expected": 3},
                {"input": {"a": 0, "b": 0}, "expected": 0},
                {"input": {"a": 3, "b": 4}, "expected": 7},
            ],
        }
        result = evolution.validate_and_test(candidate)
        self.assertFalse(result["all_tests_passed"])
        self.assertFalse(result["tests"][0]["passed"])

    def test_rejects_imports(self):
        candidate = {
            "name": "unsafe",
            "summary": "Must fail.",
            "code": "import os\ndef run(data):\n    return 1\n",
            "tests": [
                {"input": {}, "expected": 1},
                {"input": {}, "expected": 1},
                {"input": {}, "expected": 1},
            ],
        }
        with self.assertRaises(ValueError):
            evolution.validate_and_test(candidate)

    def test_requires_three_to_five_tests(self):
        candidate = {
            "name": "tiny",
            "summary": "Too few tests.",
            "code": "def run(data):\n    return 1\n",
            "tests": [{"input": {}, "expected": 1}],
        }
        with self.assertRaises(evolution.EvolutionError):
            evolution.validate_and_test(candidate)


if __name__ == "__main__":
    unittest.main(verbosity=2)
