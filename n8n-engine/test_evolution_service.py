import unittest
from unittest.mock import patch

import evolution_service


class SvgValidationTests(unittest.TestCase):
    def test_valid_self_contained_svg_is_accepted(self):
        svg = '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512"><rect x="0" y="0" width="512" height="512" fill="#ffffff"/><circle cx="256" cy="256" r="80" fill="#3366ff"/></svg>'
        self.assertEqual(evolution_service.validate_svg_image(svg), svg)

    def test_invalid_xml_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "SVG/XML valide"):
            evolution_service.validate_svg_image("<svg><rect></svg>")

    def test_non_svg_root_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "racine"):
            evolution_service.validate_svg_image("<html/>")

    def test_script_elements_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Élément SVG interdit"):
            evolution_service.validate_svg_image('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>')

    def test_event_attributes_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Attribut SVG interdit"):
            evolution_service.validate_svg_image('<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"/>')

    def test_external_resources_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Attribut SVG interdit"):
            evolution_service.validate_svg_image('<svg xmlns="http://www.w3.org/2000/svg"><image href="https://example.com/a.png"/></svg>')

    def test_tool_can_return_svg_without_saving(self):
        svg = '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512"/>'
        with patch.object(evolution_service.tool_catalog, "run_tool", return_value={
            "ok": True, "name": "image_tool", "version": 1,
            "capabilities": ["file.write"], "result": svg
        }):
            result = evolution_service.use_tool("image_tool", {"subject": "sun"})
        self.assertEqual(result["result"], svg)

    def test_tool_saves_svg_only_with_write_permission(self):
        svg = '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512"/>'
        outcome = {"ok": True, "name": "image_tool", "version": 1,
                   "capabilities": ["file.write"], "result": svg}
        with patch.object(evolution_service.tool_catalog, "run_tool", return_value=outcome), \
             patch.object(evolution_service.capability_broker, "write_file",
                          return_value={"path": "images/test.svg", "bytes_written": len(svg)} ) as write:
            result = evolution_service.use_tool("image_tool", {"subject": "sun"}, "images/test.svg")
        write.assert_called_once_with("image_tool", "images/test.svg", svg)
        self.assertEqual(result["format"], "svg")
        self.assertEqual(result["saved"]["path"], "images/test.svg")

    def test_tool_without_write_permission_cannot_save(self):
        svg = '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512"/>'
        with patch.object(evolution_service.tool_catalog, "run_tool", return_value={
            "ok": True, "name": "image_tool", "version": 1,
            "capabilities": [], "result": svg
        }), patch.object(evolution_service.capability_broker, "write_file") as write:
            with self.assertRaisesRegex(ValueError, "permission file.write"):
                evolution_service.use_tool("image_tool", {}, "images/test.svg")
            write.assert_not_called()

    def test_non_svg_output_cannot_be_saved(self):
        with patch.object(evolution_service.tool_catalog, "run_tool", return_value={
            "ok": True, "name": "image_tool", "version": 1,
            "capabilities": ["file.write"], "result": "not an image"
        }), patch.object(evolution_service.capability_broker, "write_file") as write:
            with self.assertRaisesRegex(ValueError, "SVG"):
                evolution_service.use_tool("image_tool", {}, "images/test.svg")
            write.assert_not_called()


if __name__ == "__main__":
    unittest.main()
