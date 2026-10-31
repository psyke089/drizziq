"""Check Android resource policies without a device or backup transport."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2] / "app/src/main"
A = "{http://schemas.android.com/apk/res/android}"


class AndroidResourcesTest(unittest.TestCase):
    def test_backupPolicy_allStorageDomains_excludedFromCloudAndDeviceTransfer(self):
        application = ET.parse(ROOT / "AndroidManifest.xml").getroot().find("application")
        self.assertEqual("false", application.get(A + "allowBackup"))
        self.assertEqual("false", application.get(A + "fullBackupContent"))
        resource = application.get(A + "dataExtractionRules")
        self.assertIsNotNone(resource, "Modern backup and transfer rules must be explicit")
        self.assertTrue(resource.startswith("@xml/"))
        rules = ET.parse(ROOT / "res/xml" / (resource.removeprefix("@xml/") + ".xml")).getroot()
        domains = {"root", "file", "database", "sharedpref", "external",
                   "device_root", "device_file", "device_database", "device_sharedpref"}
        for mode in ("cloud-backup", "device-transfer"):
            with self.subTest(mode=mode):
                section = rules.find(mode)
                self.assertIsNotNone(section, "An omitted mode allows transfers by default")
                excluded = {entry.get("domain") for entry in section.findall("exclude")
                            if entry.get("path") == "."}
                self.assertEqual(set(), domains - excluded)

    def test_launcherIcon_themedIcons_reusesTransparentForegroundArtwork(self):
        icon = ET.parse(ROOT / "res/mipmap-anydpi-v26/ic_launcher.xml").getroot()
        monochrome = icon.find("monochrome")
        self.assertIsNotNone(monochrome, "The launcher must provide a themed-icon layer")
        foreground = icon.find("foreground").get(A + "drawable")
        self.assertEqual(foreground, monochrome.get(A + "drawable"))
        vector = ET.parse(ROOT / "res/drawable" / (foreground.removeprefix("@drawable/") + ".xml")).getroot()
        self.assertEqual("vector", vector.tag)
        self.assertTrue(vector.findall(".//path"), "The monochrome mask must contain artwork")


if __name__ == "__main__":
    unittest.main()
