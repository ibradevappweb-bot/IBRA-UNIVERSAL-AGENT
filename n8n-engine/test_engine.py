import unittest
import engine

class EngineTests(unittest.TestCase):
    def test_addition(self):
        self.assertEqual(engine.execute_code("def run(data):\n    return data['a'] + data['b']\n", {"a": 2, "b": 3}), 5)
    def test_assignment_and_if(self):
        code = "def run(data):\n    total = data['a'] * 2\n    if total > 5:\n        return total\n    else:\n        return 0\n"
        self.assertEqual(engine.execute_code(code, {"a": 4}), 8)
    def test_rejects_import(self):
        with self.assertRaises(ValueError):
            engine.parse_code("import os\ndef run(data):\n    return 1\n")
    def test_rejects_attribute(self):
        with self.assertRaises(ValueError):
            engine.parse_code("def run(data):\n    return data.__class__\n")
    def test_rejects_loop(self):
        with self.assertRaises(ValueError):
            engine.parse_code("def run(data):\n    while True:\n        return 1\n")
    def test_requires_run_signature(self):
        with self.assertRaises(ValueError):
            engine.parse_code("def calculate(data):\n    return 1\n")
    def test_rejects_invalid_name(self):
        with self.assertRaises(ValueError):
            engine.validate_name("../bad")
    def test_rejects_nan(self):
        with self.assertRaises(ValueError):
            engine.execute_code("def run(data):\n    return 1e999\n", {})

if __name__ == "__main__":
    unittest.main(verbosity=2)
