import unittest
import evolution


class PlannerTests(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
