import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import capability_broker as broker
import tool_catalog


CODE = "def run(data):\n    return data['value']\n"
TESTS = [
    {"input": {"value": 1}, "expected": 1},
    {"input": {"value": 2}, "expected": 2},
    {"input": {"value": 1}, "expected": 1},
]


class CapabilityBrokerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        base = Path(self.temp.name)
        self.catalog_patch = patch.object(tool_catalog, "ROOT", base / "catalog")
        self.workspace_patch = patch.object(broker, "WORKSPACE", base / "workspace")
        self.catalog_patch.start()
        self.workspace_patch.start()
        broker.WORKSPACE.mkdir(parents=True, exist_ok=True)

    def tearDown(self):
        self.workspace_patch.stop()
        self.catalog_patch.stop()
        self.temp.cleanup()

    def install(self, name, capabilities):
        review = {
            "approved": True,
            "reasons": ["Test local"],
            "risks": ["Test only"],
            "capabilities": list(capabilities),
        }
        return tool_catalog.install_tool(name, CODE, TESTS, review, capabilities)

    def test_tool_without_permission_cannot_read_file(self):
        self.install("no_access", [])
        (broker.WORKSPACE / "secret.txt").write_text("private", encoding="utf-8")
        with self.assertRaisesRegex(broker.CapabilityError, "Permission refusée"):
            broker.read_file("no_access", "secret.txt")

    def test_explicit_file_read_and_write_permissions(self):
        self.install("file_agent", ["file.read", "file.write"])
        result = broker.write_file("file_agent", "inbox/result.txt", "bonjour")
        self.assertEqual(result["bytes_written"], 7)
        self.assertEqual(broker.read_file("file_agent", "inbox/result.txt")["content"], "bonjour")

    def test_path_traversal_is_rejected(self):
        self.install("file_agent", ["file.read", "file.write"])
        for path in ("../secret.txt", "/etc/passwd", "a/../secret", "a\\secret"):
            with self.subTest(path=path), self.assertRaises(broker.CapabilityError):
                broker.write_file("file_agent", path, "x")

    def test_symlink_is_rejected(self):
        self.install("file_agent", ["file.read"])
        outside = Path(self.temp.name) / "outside.txt"
        outside.write_text("not for broker", encoding="utf-8")
        link = broker.WORKSPACE / "escape.txt"
        link.symlink_to(outside)
        with self.assertRaisesRegex(broker.CapabilityError, "sort de l'espace|symboliques"):
            broker.read_file("file_agent", "escape.txt")

    def test_json_select_rename_and_filter(self):
        self.install("json_agent", ["data.json"])
        selected = broker.transform_json(
            "json_agent", {"name": "Ada", "age": 31}, "select", fields=["name"])
        self.assertEqual(selected["result"], {"name": "Ada"})
        renamed = broker.transform_json(
            "json_agent", {"first": "Ada"}, "rename", mapping={"first": "name"})
        self.assertEqual(renamed["result"], {"name": "Ada"})
        filtered = broker.transform_json(
            "json_agent", [{"ok": True}, {"ok": False}, "invalid"],
            "filter_equal", key="ok", equals=True)
        self.assertEqual(filtered["result"], [{"ok": True}])

    def test_json_unknown_operation_rejected(self):
        self.install("json_agent", ["data.json"])
        with self.assertRaises(broker.CapabilityError):
            broker.transform_json("json_agent", {"a": 1}, "delete_all")

    def test_csv_filter_select_and_rename(self):
        self.install("csv_agent", ["data.csv"])
        result = broker.transform_csv(
            "csv_agent", "name,region,active\nAda,Conakry,yes\nAli,Kindia,no\n",
            select=["name", "region"], rename={"name": "client"},
            filter_column="active", equals="yes")
        self.assertEqual(result["rows"], 1)
        self.assertEqual(result["headers"], ["client", "region"])
        self.assertEqual(result["csv"], "client,region\nAda,Conakry\n")

    def test_permissions_must_match_tuteur_review(self):
        review = {"approved": True, "reasons": [], "risks": [], "capabilities": []}
        with self.assertRaisesRegex(tool_catalog.CatalogError, "différentes"):
            tool_catalog.install_tool("mismatch_tool", CODE, TESTS, review, ["file.read"])

    def test_invalid_or_duplicate_capabilities_rejected(self):
        with self.assertRaises(broker.CapabilityError):
            broker.validate_capabilities(["file.read", "file.read"])
        with self.assertRaises(broker.CapabilityError):
            broker.validate_capabilities(["file.delete"])

    def test_connector_requires_explicit_permission_before_network(self):
        self.install("no_connector", [])
        with patch.dict(os.environ, {"IBRA_CONNECTORS_JSON": "{}"}), \
             patch.object(broker.urllib.request, "build_opener") as opener:
            with self.assertRaisesRegex(broker.CapabilityError, "Permission refusée"):
                broker.connector_request("no_connector", "exa_search", "GET", "/search")
            opener.assert_not_called()

    def test_connector_path_method_and_host_are_allowlisted(self):
        self.install("exa_agent", ["connector:exa_search"])
        config = {
            "exa_search": {
                "base_url": "https://api.example.com/v1",
                "methods": ["POST"],
                "paths": ["/search"]
            }
        }
        with patch.dict(os.environ, {"IBRA_CONNECTORS_JSON": json.dumps(config)}), \
             patch.object(broker.urllib.request, "build_opener") as opener:
            with self.assertRaisesRegex(broker.CapabilityError, "Chemin API non autorisé"):
                broker.connector_request("exa_agent", "exa_search", "POST", "/admin")
            with self.assertRaisesRegex(broker.CapabilityError, "Méthode HTTP non autorisée"):
                broker.connector_request("exa_agent", "exa_search", "DELETE", "/search")
            opener.assert_not_called()

    def test_connector_rejects_http_and_local_hosts(self):
        for url in ("http://api.example.com", "https://localhost", "https://127.0.0.1"):
            with self.subTest(url=url), self.assertRaises(broker.CapabilityError):
                broker._validate_public_https_base(url)

    def test_broker_requires_token_to_start(self):
        with patch.object(broker, "TOKEN", ""):
            with self.assertRaisesRegex(SystemExit, "obligatoire"):
                broker.main()


if __name__ == "__main__":
    unittest.main(verbosity=2)
