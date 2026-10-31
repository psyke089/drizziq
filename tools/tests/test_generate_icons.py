import importlib.util
from pathlib import Path
import unittest
import tempfile
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location("generate_icons", Path(__file__).parents[1] / "generate_icons.py")
icons = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(icons)
A = "{http://schemas.android.com/apk/res/android}"


class SvgConversionTest(unittest.TestCase):
    def test_inherited_styles_negative_viewbox_and_transforms_are_preserved(self):
        source = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="-10 -20 40 60"><g stroke="#7CE6D2" stroke-width="2" fill="none" transform="translate(3 4) scale(.8)"><circle r="5"/><path d="M0 0L2 3"/></g></svg>'
        root = ET.fromstring(icons.convert_svg(source))
        self.assertEqual("40", root.get(A + "viewportWidth"))
        origin = root.find("group")
        self.assertEqual("10", origin.get(A + "translateX"))
        self.assertEqual("20", origin.get(A + "translateY"))
        paths = root.findall(".//path")
        self.assertEqual(2, len(paths))
        self.assertTrue(all(p.get(A + "strokeColor") == "#7CE6D2" for p in paths))
        self.assertTrue(all(p.get(A + "fillColor") == "#00000000" for p in paths))
        groups = root.findall(".//group")
        self.assertTrue(any(g.get(A + "translateX") == "3" for g in groups))
        self.assertTrue(any(g.get(A + "scaleX") == "0.8" for g in groups))

    def test_opacity_and_round_strokes_are_not_lost(self):
        root = ET.fromstring(icons.convert_svg('<svg viewBox="0 0 24 24"><g opacity=".5" stroke="#123456" stroke-linecap="round" stroke-linejoin="round" fill="none"><path d="M1 2L3 4" stroke-opacity=".4"/></g></svg>'))
        path = root.find(".//path")
        self.assertEqual("0.2", path.get(A + "strokeAlpha"))
        self.assertEqual("round", path.get(A + "strokeLineCap"))
        self.assertEqual("round", path.get(A + "strokeLineJoin"))

    def test_unsupported_filters_and_external_content_fail_instead_of_disappearing(self):
        for body in ('<filter id="blur"/>', '<image href="https://example.com/icon.png"/>', '<path d="M0 0" filter="url(#blur)"/>'):
            with self.assertRaises(ValueError):
                icons.convert_svg(f'<svg viewBox="0 0 24 24">{body}</svg>')

    def test_source_edits_rebuild_vectors_and_retired_icons_remove_only_generated_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            sources = root / "artwork/icons"
            sources.mkdir(parents=True)
            source = sources / "sample.svg"
            source.write_text('<svg viewBox="0 0 24 24"><path fill="#123456" d="M0 0L1 1"/></svg>')
            icons.generate(root)
            icons.generate(root, check=True)
            source.write_text(source.read_text().replace("#123456", "#654321"))
            with self.assertRaises(ValueError):
                icons.generate(root, check=True)
            icons.generate(root)
            output = root / "app/src/main/res/drawable/ic_sample.xml"
            self.assertIn("#654321", output.read_text())
            manual = output.with_name("ic_manual.xml")
            manual.write_text("<vector/>")
            source.rename(sources / "renamed.svg")
            icons.generate(root)
            self.assertFalse(output.exists())
            self.assertTrue(manual.exists())
            self.assertTrue(output.with_name("ic_renamed.xml").exists())

    def test_all_project_svgs_and_generated_resources_are_current(self):
        icons.generate(check=True)


if __name__ == "__main__":
    unittest.main()
