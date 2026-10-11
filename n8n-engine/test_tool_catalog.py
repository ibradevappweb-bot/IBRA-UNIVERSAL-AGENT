import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import tool_catalog as catalog


VALID_CODE = "def run(data):\n    return data['value'] * 2\n"
VALID_TESTS = [
    {"input": {"value": 1}, "expected": 2},
    {"input": {"value": 3}, "expected": 6},
    {"input": {"value": -2}, "expected": -4},
]
APPROVED = {"approved": True, "reasons": ["Logique vérifiée"], "risks": ["Entrée numérique attendue"]}


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root_patch = patch.object(catalog, "ROOT", Path(self.temp.name))
        self.root_patch.start()

    def tearDown(self):
        self.root_patch.stop()
        self.temp.cleanup()

    def test_http_mutations_require_a_configured_token(self):
        self.assertEqual(catalog.mutation_auth_result("", "")[1:], (503, "IBRA_CATALOG_TOKEN_REQUIRED"))
        self.assertEqual(catalog.mutation_auth_result("secret", "")[1:], (401, "UNAUTHORIZED"))
        self.assertEqual(catalog.mutation_auth_result("secret", "Bearer wrong")[1:], (401, "UNAUTHORIZED"))
        self.assertEqual(catalog.mutation_auth_result("secret", "Bearer secret"), (True, 200, "OK"))

    def test_install_and_run(self):
        result = catalog.install_tool("double_value", VALID_CODE, VALID_TESTS, APPROVED)
        self.assertEqual(result["version"], 1)
        self.assertEqual(catalog.run_tool("double_value", {"value": 5})["result"], 10)


    def test_capabilities_are_saved_and_returned(self):
        review = {
            "approved": True, "reasons": ["reviewed"], "risks": [],
            "capabilities": ["file.read"],
        }
        result = catalog.install_tool("read_helper", VALID_CODE, VALID_TESTS,
                                      review, ["file.read"])
        self.assertEqual(result["active_version"], 1)
        self.assertEqual(catalog.list_tools()[0]["capabilities"], ["file.read"])
        run = catalog.run_tool("read_helper", {"value": 4})
        self.assertEqual(run["capabilities"], ["file.read"])
        self.assertEqual(run["result"], 8)

    def test_rejects_permission_review_mismatch(self):
        review = {
            "approved": True, "reasons": ["reviewed"], "risks": [],
            "capabilities": [],
        }
        with self.assertRaisesRegex(catalog.CatalogError, "permissions demandées"):
            catalog.install_tool("read_helper", VALID_CODE, VALID_TESTS,
                                 review, ["file.read"])
        self.assertEqual(catalog.list_tools(), [])


    def test_rollback_restores_previous_version_permissions(self):
        read_review = {
            "approved": True, "reasons": ["reviewed"], "risks": [],
            "capabilities": ["file.read"],
        }
        no_permissions_review = {
            "approved": True, "reasons": ["reviewed"], "risks": [],
            "capabilities": [],
        }
        catalog.install_tool("permissioned_tool", VALID_CODE, VALID_TESTS,
                             read_review, ["file.read"])
        catalog.install_tool("permissioned_tool", VALID_CODE, VALID_TESTS,
                             no_permissions_review, [])
        self.assertEqual(catalog.list_tools()[0]["capabilities"], [])
        catalog.rollback_tool("permissioned_tool")
        self.assertEqual(catalog.list_tools()[0]["capabilities"], ["file.read"])

    def test_rejects_unapproved_tuteur(self):
        review = {"approved": False, "reasons": ["Risque"], "risks": ["Non sûr"]}
        with self.assertRaises(catalog.CatalogError):
            catalog.install_tool("double_value", VALID_CODE, VALID_TESTS, review)
        self.assertEqual(catalog.list_tools(), [])

    def test_rejects_missing_tuteur(self):
        with self.assertRaises(catalog.CatalogError):
            catalog.install_tool("double_value", VALID_CODE, VALID_TESTS, None)

    def test_rejects_failed_test(self):
        cases = VALID_TESTS + [{"input": {"value": 2}, "expected": 999}]
        with self.assertRaises(catalog.CatalogError):
            catalog.install_tool("double_value", VALID_CODE, cases, APPROVED)
        self.assertEqual(catalog.list_tools(), [])

    def test_rejects_imports(self):
        code = "import os\ndef run(data):\n    return 1\n"
        with self.assertRaises(ValueError):
            catalog.install_tool("unsafe_tool", code, VALID_TESTS, APPROVED)

    def test_rejects_invalid_name(self):
        with self.assertRaises(catalog.CatalogError):
            catalog.install_tool("../bad", VALID_CODE, VALID_TESTS, APPROVED)

    def test_requires_three_tests(self):
        with self.assertRaises(catalog.CatalogError):
            catalog.install_tool("double_value", VALID_CODE, VALID_TESTS[:2], APPROVED)

    def test_versions_are_kept_and_rollback_works(self):
        catalog.install_tool("double_value", VALID_CODE, VALID_TESTS, APPROVED)
        code_v2 = "def run(data):\n    return data['value'] * 3\n"
        tests_v2 = [
            {"input": {"value": 1}, "expected": 3},
            {"input": {"value": 2}, "expected": 6},
            {"input": {"value": -2}, "expected": -6},
        ]
        catalog.install_tool("double_value", code_v2, tests_v2, APPROVED)
        self.assertEqual(catalog.run_tool("double_value", {"value": 4})["result"], 12)
        rollback = catalog.rollback_tool("double_value")
        self.assertEqual(rollback["active_version"], 1)
        self.assertEqual(catalog.run_tool("double_value", {"value": 4})["result"], 8)
        manifest = json.loads((Path(self.temp.name) / "double_value" / "manifest.json").read_text())
        self.assertEqual(manifest["versions"], [1, 2])

    def test_cannot_rollback_first_version(self):
        catalog.install_tool("double_value", VALID_CODE, VALID_TESTS, APPROVED)
        with self.assertRaises(catalog.CatalogError):
            catalog.rollback_tool("double_value")

    def test_unknown_tool_cannot_run(self):
        with self.assertRaises(catalog.CatalogError):
            catalog.run_tool("missing_tool", {})


if __name__ == "__main__":
    unittest.main(verbosity=2)
