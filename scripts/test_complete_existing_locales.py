"""Regression tests for safe, additive catalog completion."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree as ET

from scripts.complete_existing_locales import (
    append_entries, locale_entries, safe_translation,
)


class SafeTranslationsTest(unittest.TestCase):
    def test_accepts_localized_text_with_matching_parameters(self):
        self.assertTrue(safe_translation("Found %1$d devices", "%1$d dispositivos encontrados"))
        self.assertTrue(safe_translation("Starlit", "Starlit"))

    def test_rejects_missing_or_damaged_placeholders(self):
        self.assertFalse(safe_translation("Found %1$d devices", "Encontrados %1$s dispositivos"))
        self.assertFalse(safe_translation("Found %1$d devices", "Dispositivos encontrados"))
        self.assertFalse(safe_translation("A\\nB", "A B"))
        self.assertFalse(safe_translation("A\nB", "A B"))
        self.assertFalse(safe_translation("Installing Starlit", "[[P0000]]"))
        self.assertFalse(safe_translation("Installing Starlit", "Installing Starlit"))
        self.assertFalse(safe_translation("Settings", ""))

    def test_preserves_existing_xml_entries_and_escapes_added_text(self):
        with tempfile.TemporaryDirectory() as directory:
            resources = Path(directory)
            (resources / "values-es").mkdir()
            target = resources / "values-es" / "strings.xml"
            target.write_text('<resources>\n  <string name="existing">Manual</string>\n</resources>\n',
                              encoding="utf-8")
            with patch("scripts.complete_existing_locales.RESOURCE_DIR", resources):
                append_entries("es", {"new": 'A & B < C "quoted"'})
                self.assertEqual(locale_entries("es"), {
                    "existing": "Manual", "new": 'A & B < C "quoted"',
                })
                data = ET.parse(target).getroot()
                self.assertEqual(len(data.findall("string")), 2)
                with self.assertRaisesRegex(ValueError, "Refusing to replace"):
                    append_entries("es", {"existing": "Overwritten"})
                self.assertEqual(locale_entries("es")["existing"], "Manual")


if __name__ == "__main__":
    unittest.main()
