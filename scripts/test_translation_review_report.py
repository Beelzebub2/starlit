"""Tests for the review queue generated alongside translation parity checks."""
import csv
from contextlib import redirect_stdout
from io import StringIO
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import check_translation_parity as parity
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

    def test_short_translations_are_flagged_for_human_review(self):
        source = {"message": "Authorization expired for Trakt"}
        rows = review_rows("compose", "he", source, {"message": "Trakt"}, {})
        self.assertIn("possible_truncation", rows[0][6])

    def test_critical_translations_do_not_lose_error_meaning(self):
        from scripts.complete_existing_locales import REVIEWED_OVERRIDES, locale_entries
        keys = {
            "he": ("trakt_error_authorization_expired", "trakt_error_list_limit_reached"),
            "cs": ("layout_overall_ratings_sub_on",),
        }
        for locale, names in keys.items():
            current = locale_entries(locale)
            for key in names:
                with self.subTest(locale=locale, key=key):
                    self.assertEqual(current[key], REVIEWED_OVERRIDES[locale][key])
                    self.assertGreater(len(current[key]), 15)

    def test_csv_preserves_unicode_and_neutralizes_formulas(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "reviews.csv"
            rows = [("compose", "he", "added", "example", "Choose", "=HYPERLINK(1)", "")]
            write_review_csv(str(destination), rows)
            with destination.open(encoding="utf-8-sig", newline="") as stream:
                reader = csv.DictReader(stream)
                self.assertEqual(next(reader)["translation"], "'=HYPERLINK(1)")


class FullCatalogTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        root_patch = patch.object(parity, "ROOT", self.root)
        self.addCleanup(root_patch.stop)
        root_patch.start()
        catalog_patch = patch.object(parity, "CATALOGS", {
            "compose": {"path": "resources", "baseline": "HEAD"},
        })
        self.addCleanup(catalog_patch.stop)
        catalog_patch.start()

    def write(self, location, content):
        path = self.root / "resources" / location
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("<resources>" + content + "</resources>", encoding="utf-8")

    def commit_fixture(self):
        def git(*args):
            subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)
        git("init", "-q")
        git("add", "resources")
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.test",
            "-c", "commit.gpgsign=false", "commit", "-qm", "Fixture catalog")

    def test_reads_every_catalog_file_and_rejects_duplicate_definitions(self):
        self.write("values/strings.xml", '<string name="one">First</string>')
        self.write("values/feature.xml", '<string name="two">Second</string>')
        self.assertEqual(parity.catalog("resources/values").strings,
                         {"one": "First", "two": "Second"})
        self.write("values/feature.xml", '<string name="one">Second</string>')
        with self.assertRaisesRegex(AssertionError, "Duplicate string one"):
            parity.catalog("resources/values")

    def test_disappearing_locale_and_untranslated_split_file_fail(self):
        self.write("values/strings.xml", '<string name="hello">Hello</string>')
        self.write("values-pt/strings.xml", '<string name="hello">Olá</string>')
        self.write("values-fr/strings.xml", '<string name="hello">Bonjour</string>')
        self.commit_fixture()
        self.write("values/feature.xml", '<string name="goodbye">Goodbye</string>')
        gone = self.root / "resources" / "values-fr"
        (gone / "strings.xml").unlink()
        gone.rmdir()

        output = StringIO()
        with redirect_stdout(output):
            self.assertEqual(parity.validate("HEAD"), 1)
        self.assertIn("compose/fr/hello: missing translation", output.getvalue())
        self.assertIn("compose/pt/goodbye: missing translation", output.getvalue())

    def test_historical_plural_debt_is_counted_and_new_groups_are_gated(self):
        self.write("values/strings.xml", '<string name="hello">Hello</string>'
            '<plurals name="legacy"><item quantity="other">%1$d choices</item></plurals>')
        self.write("values-ar/strings.xml", '<string name="hello">مرحباً</string>')
        self.commit_fixture()
        output = StringIO()
        with redirect_stdout(output):
            self.assertEqual(parity.validate("HEAD"), 0)
        self.assertIn("1 missing plural groups", output.getvalue())
        self.write("values/feature.xml",
            '<plurals name="new"><item quantity="other">%1$d results</item></plurals>')
        output = StringIO()
        with redirect_stdout(output):
            self.assertEqual(parity.validate("HEAD"), 1)
        self.assertIn("compose/ar/new: missing plural group", output.getvalue())

        self.write("values-ar/feature.xml", '<plurals name="new">'
            '<item quantity="few">%1$d نتائج</item><item quantity="other">%1$d نتيجة</item></plurals>')
        with redirect_stdout(StringIO()):
            self.assertEqual(parity.validate("HEAD"), 0)

    def test_plural_checks_locale_categories_and_other_placeholders(self):
        source = {"messages": {"one": "%1$d message", "other": "%1$d messages"}}
        self.assertEqual(parity.plural_problems(source, {
            "messages": {"few": "%1$d wiadomości", "other": "%1$d wiadomości"},
        }), {})
        self.assertEqual(parity.plural_problems(source, {
            "messages": {"few": "%1$s wiadomości"},
        }), {"messages": ["missing the required other quantity", "few: formatting parameters differ"]})


if __name__ == "__main__":
    unittest.main()
