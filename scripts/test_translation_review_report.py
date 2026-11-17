"""Tests for the review queue generated alongside translation parity checks."""
import csv
import tempfile
import unittest
from pathlib import Path

from scripts.check_translation_parity import review_rows, write_review_csv


class ReviewReportTest(unittest.TestCase):
    def test_only_added_or_revised_translations_are_included(self):
        source = {"new": "Retry import sync", "old": "Play", "brand": "Prime Video"}
        current = {"new": "Erneuter Versuch der Synchronisation des Imports", "old": "Abspielen",
                   "brand": "Prime Video"}
        previous = {"old": "Abspielen"}
        rows = review_rows("compose", "de", source, current, previous)
        self.assertEqual([row[3] for row in rows], ["brand", "new"])
        self.assertEqual(rows[0][2], "added")
        self.assertIn("english_identical", rows[0][6])
        self.assertIn("possible_ui_overflow", rows[1][6])

    def test_rtl_text_without_script_is_flagged_for_review(self):
        source = {"prompt": "Choose a subtitle language to continue"}
        rows = review_rows("compose", "he", source, {"prompt": "Select a language"}, {})
        self.assertIn("rtl_script_missing", rows[0][6])
        self.assertEqual(rows[0][2], "added")

    def test_csv_preserves_unicode_and_neutralizes_formulas(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "reviews.csv"
            rows = [("compose", "he", "added", "example", "Choose", "=HYPERLINK(1)", "")]
            write_review_csv(str(destination), rows)
            with destination.open(encoding="utf-8-sig", newline="") as stream:
                reader = csv.DictReader(stream)
                self.assertEqual(next(reader)["translation"], "'=HYPERLINK(1)")


if __name__ == "__main__":
    unittest.main()
