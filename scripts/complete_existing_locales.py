"""Fill only missing Compose locale entries with reviewable machine-translation drafts.

Usage:
  py -3 scripts/complete_existing_locales.py --locale es --apply
  py -3 scripts/complete_existing_locales.py --all --apply

Never overwrites a present translation, never substitutes English on a failed
translation, and never silently accepts broken formatting placeholders. These
drafts still require linguistic and visual review before publication.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape

try:
    from scripts.translate_popular_languages import decode_text, encode_text, source_entries
except ModuleNotFoundError:
    # Direct execution adds scripts/, rather than the repository root, to sys.path.
    from translate_popular_languages import decode_text, encode_text, source_entries

ROOT = Path(__file__).resolve().parents[1]
RESOURCE_DIR = ROOT / "composeApp/src/commonMain/composeResources"
CACHE = ROOT / "build/localization-drafts-existing"
# `in` is Android's legacy Indonesian locale tag; Google Translate uses `id`.
LANGUAGES = {
    "ar": "ar", "bg": "bg", "bn": "bn", "cs": "cs", "de": "de",
    "el": "el", "es": "es", "fr": "fr", "he": "he", "hu": "hu",
    "id": "id", "in": "id", "it": "it", "ja": "ja", "nb": "no",
    "nl": "nl", "pl": "pl", "ro": "ro", "ru": "ru", "sk": "sk",
    "tr": "tr", "ur": "ur", "vi": "vi",
}
FORMAT = re.compile(r"%(?:\d+\$)?[-+#0,.(]*\d*(?:\.\d+)?[diuoxXfFeEgGaAcCsSbBhH%]")
ENGLISH_SENTENCE = re.compile(r"\b[a-zA-Z]{3,}\b(?:\s+\b[a-zA-Z]{3,}\b)+")
# Proper product, subscription and technology names are intentionally shared
# across languages. Keep this allowlist narrow and explicit, not a generic
# bypass for untranslated English messages.
UNCHANGED_BRAND_KEYS = {
    "collections_editor_tmdb_studio_warner",
    "collections_editor_tmdb_watch_provider_prime",
    "community_membership_tier_supporter_plus",
    "settings_advanced_discord_rich_presence",
    "settings_licenses_attributions_upstream_desktop_title",
    "settings_playback_nvidia_rtx_super_resolution",
    "settings_playback_nvidia_rtx_video_section",
    "settings_playback_regex_preset_dolby_atmos_dts",
    "settings_playback_regex_preset_hdr_dolby_vision",
    "trakt_watch_progress_source_nuvio",
}
REVIEWED_OVERRIDES = {
    "bg": {},
    "bn": {
        "settings_licenses_attributions_tvmaze_body": (
            "TVmaze (https://www.tvmaze.com) থেকে পর্বের মেটাডেটা ও ছবি ব্যবহার করা হয়, "
            "যা CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/) লাইসেন্সের অধীনে উপলব্ধ। "
            "অ্যানিমে পর্বের তারিখ অনুযায়ী ছবির মিল নির্ধারণের পদ্ধতি সমন্বয় করা হয়েছে। "
            "Starlit, TVmaze-এর সঙ্গে সংশ্লিষ্ট নয় এবং TVmaze এটি অনুমোদনও করে না।"
        ),
    },
    "cs": {
        "player_auto_next_episode_toggle": "Automaticky další epizoda",
        "layout_overall_ratings_sub_on": "Zobrazují se standardní hodnocení a hodnocení TMDB.",
    },
    "de": {
        "collections_editor_tmdb_person_title_format": "TMDB-Person %1$s",
        "tmdb_sources_person_fallback_title": "TMDB-Person %1$d",
    },
    "fr": {"watch_later_series": "Séries"},
    "he": {
        "trakt_error_authorization_expired": "פג תוקף ההרשאה של Trakt",
        "trakt_error_list_limit_reached": "הגעת למגבלת הרשימות ב-Trakt",
        "p2p_consent_body": (
            "השידור הזה משתמש בטכנולוגיית עמית לעמית (P2P). בהפעלת P2P אתם מאשרים ומסכימים כי:"
            r"\n\n" "• כתובת ה-IP שלכם תהיה גלויה למשתמשים אחרים ברשת"
            r"\n" "• האחריות לתוכן שאליו אתם ניגשים מוטלת עליכם בלבד"
            r"\n" "• אתם מאשרים שיש לכם זכות חוקית לצפות בתוכן זה באזור השיפוט שלכם"
            r"\n" "• Starlit אינה מארחת, מפיצה או שולטת בתוכן P2P כלשהו"
            r"\n" "• Starlit אינה אחראית להשלכות משפטיות הנובעות מהשימוש שלכם בשידורי P2P"
            r"\n\n" "השימוש בתכונה זו הוא באחריותכם בלבד. אפשר לכבות P2P בכל עת בהגדרות."
        ),
        "settings_appearance_app_icon_arctic_blue": "כחול ארקטי",
        "settings_playback_libmpv_video_output": "מנוע תצוגה libmpv",
        "settings_playback_libmpv_video_output_dialog": "מנוע תצוגה libmpv",
        "settings_playback_map_dv7_to_hevc": "DV7 - מעבר ל-HEVC",
        "theme_arctic_blue": "כחול ארקטי",
    },
    "hu": {
        "auth_google_link_button": "Google-fiók összekapcsolása",
        "theme_arctic_blue": "Sarkvidéki kék",
    },
    "ja": {
        "streams_sources_summary": "%1$d 件のソース（%2$d 個のアドオンから）",
        "streams_sources_summary_searching": "%1$d 件のソース（%2$d 個のアドオンから）・%3$d 件を検索中",
    },
    "nb": {
        "auth_google_link_button": "Koble til Google",
        "settings_appearance_top_bar_style": "Stil for topplinjen",
        "settings_appearance_top_bar_style_sheet_title": "Stil for topplinjen",
    },
    "ro": {
        "auth_google_link_button": "Conectează Google",
        "compose_settings_page_hover_preview": "Previzualizare la trecerea cursorului",
        "settings_tracking_anime_id_imdb": "Preferă IMDb",
        "settings_tracking_anime_id_kitsu": "Preferă Kitsu",
        "settings_tracking_anime_id_mal": "Preferă MyAnimeList",
        "settings_tracking_anime_id_tvdb": "Preferă TVDB",
    },
    "ur": {
        "downloads_remove_selected_message": (
            "کیا آپ منتخب کردہ %1$d ڈاؤن لوڈز ہٹانا چاہتے ہیں؟ ان کی مکمل اور نامکمل فائلیں حذف "
            "ہو جائیں گی، اور قطار میں موجود یا جاری منتقلیاں منسوخ ہو جائیں گی۔"
        ),
        "player_cast_alternative": (
            "مقامی فائلیں، P2P اسٹریمز، DRM اور نجی ذرائع کو Cast ریسیور پر منتقل نہیں کیا جا سکتا۔ "
            "جوڑے گئے کمپیوٹر پر Starlit PC کے ذریعے چلانے کا اختیار دستیاب رہے گا۔"
        ),
    },
    "vi": {"remote_pc_tile_network_caption": "Tìm máy tính trên mạng Wi-Fi này."},
    "es": {
        "auth_forgot_password": "¿Has olvidado tu contraseña?",
        "remote_bluetooth_host_ready": (
            "El control remoto Bluetooth está disponible. Empareja tu teléfono con este PC "
            "en los ajustes de Bluetooth y selecciona Buscar PC por Bluetooth en el teléfono."
        ),
        "settings_mdblist_library_description": (
            "Lista de seguimiento y tus listas estáticas de películas y series."
        ),
        "settings_mdblist_progress_description": (
            "Historial de visualización, progreso de reproducción y A continuación desde MDBList."
        ),
        "watch_later_saved": "Guardado en Ver más tarde",
        "watch_later_series": "Series",
    },
}


def locale_entries(locale: str) -> dict[str, str]:
    path = RESOURCE_DIR / f"values-{locale}" / "strings.xml"
    entries: dict[str, str] = {}
    for item in ET.parse(path).getroot():
        if item.tag != "string" or not item.get("name"):
            continue
        key = item.attrib["name"]
        if key in entries:
            raise ValueError(f"Duplicate key {key} in {path}")
        entries[key] = "".join(item.itertext())
    return entries


def safe_translation(source: str, value: str | None, *, allow_identical: bool = False) -> bool:
    if not isinstance(value, str) or not value.strip() or "[[K" in value or "[[P" in value:
        return False
    source_params = Counter(token for token in FORMAT.findall(source) if token != "%%")
    target_params = Counter(token for token in FORMAT.findall(value) if token != "%%")
    if source_params != target_params:
        return False
    for control in (r"\n", "\n", r"\t", "\t", r"\r", "\r"):
        if source.count(control) != value.count(control):
            return False
    # Multiword unchanged English UI instructions are generally untranslated.
    if source == value and ENGLISH_SENTENCE.search(source) and not allow_identical:
        return False
    return True


def append_entries(locale: str, entries: dict[str, str]) -> None:
    if not entries:
        return
    path = RESOURCE_DIR / f"values-{locale}" / "strings.xml"
    original = path.read_text(encoding="utf-8-sig")
    old_keys = set(locale_entries(locale))
    duplicate = set(entries) & old_keys
    if duplicate:
        raise ValueError(f"Refusing to replace existing translations in {locale}: {sorted(duplicate)[:3]}")
    position = original.rfind("</resources>")
    if position < 0:
        raise ValueError(f"Missing closing resources tag: {path}")
    newline = "\r\n" if "\r\n" in original else "\n"
    lines = []
    for key, value in sorted(entries.items()):
        escaped_value = escape(value, {'"': "&quot;"})
        lines.append(f'    <string name="{key}">{escaped_value}</string>')
    additions = newline.join(lines)
    updated = original[:position].rstrip("\r\n") + newline + additions + newline + original[position:]
    ET.fromstring(updated)
    temp = path.with_name(path.name + ".i18n-tmp")
    try:
        temp.write_text(updated, encoding="utf-8", newline="")
        os.replace(temp, path)
    finally:
        if temp.exists():
            temp.unlink()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all", action="store_true", help="All still-incomplete shipped Compose locales")
    group.add_argument("--locale", action="append", choices=sorted(LANGUAGES))
    parser.add_argument("--apply", action="store_true", help="Call the translator and append safe drafts")
    parser.add_argument("--retry", action="store_true", help="Re-translate rejected keys individually")
    parser.add_argument("--workers", type=int, default=4, choices=range(1, 7))
    args = parser.parse_args()

    english = source_entries()
    locales = list(LANGUAGES) if args.all else args.locale
    selections: dict[str, dict[str, dict[str, object]]] = {}
    for locale in locales:
        existing = locale_entries(locale)
        unexpected = set(existing) - set(english)
        if unexpected:
            # Obsolete locale-only keys are historical debt, not a reason to
            # discard otherwise valid translations or rewrite reviewed copy.
            print(f"{locale}: preserving {len(unexpected)} obsolete locale-only keys", flush=True)
        missing = sorted(set(english) - set(existing))
        print(f"{locale}: {len(existing)}/{len(english)} keys, {len(missing)} missing", flush=True)
        if missing:
            selections[locale] = {}
            for key in missing:
                text, tokens = encode_text(english[key])
                selections[locale][key] = {"text": text, "tokens": tokens}
    if not selections or not args.apply:
        if selections:
            print(f"DRY RUN: {sum(map(len, selections.values()))} entries require translation")
        return 0

    CACHE.mkdir(parents=True, exist_ok=True)
    payload = {
        "languages": {locale: LANGUAGES[locale] for locale in selections},
        "prepared_by_locale": selections,
    }
    (CACHE / "source.json").write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    if args.retry:
        for locale, prepared in selections.items():
            response_file = CACHE / f"{locale}.json"
            if not response_file.exists():
                continue
            previous = json.loads(response_file.read_text(encoding="utf-8"))
            discarded = 0
            for key, item in prepared.items():
                raw = previous.get(key)
                value = decode_text(raw, item["tokens"]) if isinstance(raw, str) and raw else None
                if not safe_translation(english[key], value):
                    if key in previous:
                        discarded += 1
                        del previous[key]
            if discarded:
                response_file.write_text(json.dumps(previous, ensure_ascii=False), encoding="utf-8")
                print(f"{locale}: retrying {discarded} rejected drafts as individual strings", flush=True)
    env = dict(os.environ)
    env.update(STARLIT_TRANSLATION_DRAFT_DIR=str(CACHE),
               STARLIT_TRANSLATION_EXISTING="1",
               STARLIT_TRANSLATION_WORKERS=str(args.workers),
               STARLIT_TRANSLATION_SINGLETON="1" if args.retry else "0")
    subprocess.run(["node", str(ROOT / "scripts/translate_popular_languages.mjs")],
                   cwd=ROOT, env=env, check=True)

    missing_count = 0
    for locale, prepared in selections.items():
        response_file = CACHE / f"{locale}.json"
        results = json.loads(response_file.read_text(encoding="utf-8")) if response_file.exists() else {}
        accepted: dict[str, str] = {}
        rejected: list[str] = []
        for key, item in prepared.items():
            raw = results.get(key)
            decoded = decode_text(raw, item["tokens"]) if isinstance(raw, str) and raw else None
            if key in REVIEWED_OVERRIDES.get(locale, {}):
                decoded = REVIEWED_OVERRIDES[locale][key]
            if key in UNCHANGED_BRAND_KEYS:
                decoded = english[key]
            if decoded is not None and safe_translation(
                english[key], decoded, allow_identical=key in UNCHANGED_BRAND_KEYS
            ):
                accepted[key] = decoded
            else:
                rejected.append(key)
        append_entries(locale, accepted)
        missing_count += len(rejected)
        print(f"{locale}: added {len(accepted)} safely formatted drafts; "
              f"{len(rejected)} require retry/review", flush=True)
        if rejected:
            print("  missing: " + ", ".join(rejected[:12]), flush=True)
    if missing_count:
        print(f"PARTIAL: {missing_count} strings could not be safely translated")
        return 2
    print("All requested missing keys filled with machine drafts (linguistic review still required)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
