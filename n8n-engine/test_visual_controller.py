import base64
import io
import json
import os
import tempfile
import unittest
from unittest.mock import patch

import visual_controller


class FakeResponse:
    def __init__(self, payload):
        self.payload = json.dumps(payload).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self, *args):
        return self.payload


class VisualControllerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.NamedTemporaryFile(suffix=".jpg", delete=False)
        self.tmp.write(b"fake-jpeg-for-unit-test")
        self.tmp.close()
        self.addCleanup(lambda: os.path.exists(self.tmp.name) and os.unlink(self.tmp.name))

    def test_pass_review(self):
        output = {
            "success": True,
            "result": {
                "response": json.dumps({
                    "pass": True, "score": 92, "issues": [],
                    "correction_prompt": ""
                })
            }
        }
        def opener(request, timeout):
            self.assertEqual(timeout, 60)
            self.assertIn("Bearer test-token", request.get_header("Authorization"))
            body = json.loads(request.data.decode("utf-8"))
            self.assertEqual(body["prompt"].split("PROMPT ORIGINAL :\n")[-1], "un homme avec un lion")
            self.assertTrue(body["image"].startswith("data:image/jpeg;base64,"))
            return FakeResponse(output)

        with patch.dict(os.environ, {
            "CLOUDFLARE_ACCOUNT_ID": "test-account",
            "CLOUDFLARE_AUTH_TOKEN": "test-token",
        }, clear=False):
            result = visual_controller.inspect_image(
                self.tmp.name, "un homme avec un lion", opener=opener
            )
        self.assertTrue(result["ok"])
        self.assertTrue(result["pass"])
        self.assertEqual(result["score"], 92)
        self.assertEqual(result["correction_prompt"], "")

    def test_failed_review_keeps_correction(self):
        result = visual_controller._parse_review(json.dumps({
            "pass": False, "score": 41,
            "issues": ["main déformée"],
            "correction_prompt": "Corriger la main visible sans changer le sujet."
        }))
        self.assertFalse(result["pass"])
        self.assertIn("Corriger", result["correction_prompt"])

    def test_rejects_invalid_review(self):
        with self.assertRaises(ValueError):
            visual_controller._parse_review('{"pass":"yes","score":80}')

    def test_requires_prompt(self):
        with self.assertRaises(ValueError):
            visual_controller.inspect_image(self.tmp.name, " ")

    def test_missing_credentials(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(RuntimeError):
                visual_controller._credentials()


if __name__ == "__main__":
    unittest.main()
