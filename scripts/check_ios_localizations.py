"""Validate the native iOS profile-unlock string catalog.

Compose Multiplatform catalogs are checked separately.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "iosApp/iosApp.xcodeproj/project.pbxproj"
CATALOGS = {
    "iosApp/iosApp/Localizable.xcstrings": (
        "iosApp/iosApp/ContentView.swift",
        {
            "profile_unlock_prompt": "%@",
            "profile_unlock_pin_hint": "",
            "profile_unlock_cancel": "",
            "profile_unlock_confirm": "",
        },
    ),
    "iosApp/DownloadsWidgetExtension/Localizable.xcstrings": (
        "iosApp/DownloadsWidgetExtension/DownloadsLiveActivityWidget.swift",
        {
            "download_activity_downloading": "",
            "download_activity_paused": "",
            "download_activity_failed": "",
            "download_activity_active": "",
        },
    ),
}
LOCALES = ("en", "pt-PT", "pt-BR")


def main() -> None:
    project = PROJECT.read_text(encoding="utf-8")
    for locale in LOCALES:
        if locale != "en":
            assert f'"{locale}",' in project, f"{locale} missing from Xcode regions"
    checked = 0
    for catalog_path, (source_path, required) in CATALOGS.items():
        data = json.loads((ROOT / catalog_path).read_text(encoding="utf-8"))
        assert data["sourceLanguage"] == "en"
        assert data["version"] == "1.0"
        strings = data["strings"]
        assert set(strings) == set(required), f"{catalog_path}: unexpected string keys"
        swift = (ROOT / source_path).read_text(encoding="utf-8")
        for key, placeholder in required.items():
            assert f'String(localized: "{key}")' in swift, f"Unused native key: {key}"
            entries = strings[key]["localizations"]
            assert set(entries) == set(LOCALES), f"{key}: locale coverage mismatch"
            for locale in LOCALES:
                unit = entries[locale]["stringUnit"]
                value = unit["value"]
                assert unit["state"] == "translated" and value.strip(), (
                    f"{key}/{locale}: missing localized text"
                )
                assert value.count("%@") == (1 if placeholder else 0), (
                    f"{key}/{locale}: placeholder mismatch"
                )
                assert "%d" not in value and "%s" not in value, (
                    f"{key}/{locale}: unexpected format placeholder"
                )
            checked += 1
    print(f"PASS: {checked} native iOS strings across {len(CATALOGS)} "
          "bundles validated for en, pt-PT and pt-BR")


if __name__ == "__main__":
    main()
