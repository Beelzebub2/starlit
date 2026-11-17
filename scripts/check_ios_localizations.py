"""Validate the native iOS profile-unlock string catalog.

Compose Multiplatform catalogs are checked separately.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "iosApp/iosApp/Localizable.xcstrings"
SOURCE = ROOT / "iosApp/iosApp/ContentView.swift"
PROJECT = ROOT / "iosApp/iosApp.xcodeproj/project.pbxproj"
REQUIRED = {
    "profile_unlock_prompt": "%@",
    "profile_unlock_pin_hint": "",
    "profile_unlock_cancel": "",
    "profile_unlock_confirm": "",
}
LOCALES = ("en", "pt-PT", "pt-BR")


def main() -> None:
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    assert data["sourceLanguage"] == "en"
    assert data["version"] == "1.0"
    strings = data["strings"]
    assert set(strings) == set(REQUIRED), "Unexpected native UI string keys"
    swift = SOURCE.read_text(encoding="utf-8")
    project = PROJECT.read_text(encoding="utf-8")
    for locale in LOCALES:
        if locale != "en":
            assert f'"{locale}",' in project, f"{locale} missing from Xcode regions"
    for key, placeholder in REQUIRED.items():
        assert f'String(localized: "{key}")' in swift, f"Unused native key: {key}"
        entries = strings[key]["localizations"]
        assert set(entries) == set(LOCALES), f"{key}: locale coverage mismatch"
        for locale in LOCALES:
            unit = entries[locale]["stringUnit"]
            text = unit["value"]
            assert unit["state"] == "translated" and text.strip(), (
                f"{key}/{locale}: missing localized text"
            )
            assert text.count("%@") == (1 if placeholder else 0), (
                f"{key}/{locale}: placeholder mismatch"
            )
            assert "%d" not in text and "%s" not in text, (
                f"{key}/{locale}: unexpected format placeholder"
            )
    print("PASS: 4 native iOS PIN strings validated for en, pt-PT and pt-BR")


if __name__ == "__main__":
    main()
