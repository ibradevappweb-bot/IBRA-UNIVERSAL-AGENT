import json
import unittest
from pathlib import Path


class WorkflowTemplateTests(unittest.TestCase):
    def test_schedule_and_manual_trigger_call_local_api(self):
        path = Path(__file__).with_name("IBRA-Evolution-48h.n8n.json")
        workflow = json.loads(path.read_text(encoding="utf-8"))
        nodes = {node["name"]: node for node in workflow["nodes"]}
        self.assertIn("Manual Trigger", nodes)
        self.assertEqual(nodes["Every 48 hours"]["parameters"]["rule"]["interval"][0]["hoursInterval"], 48)
        request = nodes["Run Evolution Cycle"]
        self.assertEqual(request["type"], "n8n-nodes-base.httpRequest")
        self.assertEqual(request["parameters"]["url"], "http://127.0.0.1:8767/run")
        self.assertFalse(workflow["active"])
        self.assertIn("Run Evolution Cycle", workflow["connections"]["Manual Trigger"]["main"][0][0]["node"])
        self.assertIn("Run Evolution Cycle", workflow["connections"]["Every 48 hours"]["main"][0][0]["node"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
