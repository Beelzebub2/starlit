"""Lock in reviewed TV Portuguese translations and their format placeholders."""
import unittest

from scripts.complete_tizen_portuguese import (
    PT_BR, PT_PT, RES, apply, check_formats, read_strings,
)


class PortugueseTvLocalizationsTest(unittest.TestCase):
    def test_reviewed_portuguese_entries_exist_without_english_fallback(self):
        for locale, review in (("pt-pt", PT_PT), ("pt-br", PT_BR)):
            with self.subTest(locale=locale):
                actual = read_strings(RES / f"values-{locale}")
                for key, translated in review.items():
                    self.assertEqual(actual[key], translated, f"{locale}/{key}")

    def test_all_format_arguments_and_linebreaks_remain_intact(self):
        source = read_strings(RES / "values")
        for review in (PT_PT, PT_BR):
            for key, translation in review.items():
                with self.subTest(key=key):
                    check_formats(source[key], translation)

    def test_apply_is_idempotent_and_does_not_rewrite_reviewed_copy(self):
        self.assertEqual(apply("pt-pt", PT_PT, write=False), 0)
        self.assertEqual(apply("pt-br", PT_BR, write=False), 0)


if __name__ == "__main__":
    unittest.main()
