"""Focused tests for the incremental hard-coded UI detector."""
import unittest

from scripts.check_hardcoded_ui_text import literal_ui_copy


class UiLiteralTests(unittest.TestCase):
    def test_rejects_visible_copy_and_accessibility_labels(self):
        values = literal_ui_copy(
            'Text("Connect now")\n'
            'NuvioCompactActionButton(label = "Pair with TV")\n'
            'Icon(Icons.Rounded.Close, contentDescription = "Close menu")\n'
            'Button("Unlock")\n'
        )
        self.assertEqual(values["Connect now"], 1)
        self.assertEqual(values["Pair with TV"], 1)
        self.assertEqual(values["Close menu"], 1)
        self.assertEqual(values["Unlock"], 1)

    def test_ignores_dynamic_values_and_resourced_labels(self):
        values = literal_ui_copy(
            'Text(stringResource(Res.string.action_cancel))\n'
            'Text("$index")\n'
            'Text(" · ")\n'
            'Text("Starlit")\n'
            'Text(String(localized: "profile_unlock_prompt"))\n'
        )
        self.assertFalse(values)

    def test_tracks_duplicate_literals(self):
        values = literal_ui_copy('Text("Retry")\nText("Retry")\n')
        self.assertEqual(values["Retry"], 2)

    def test_multiline_literal_call(self):
        values = literal_ui_copy('Text(\n    "Missing translation",\n    style = MaterialTheme.typography.bodyLarge,\n)')
        self.assertEqual(values["Missing translation"], 1)


if __name__ == "__main__":
    unittest.main()
