"""Check complete Starlit UI translations across every shipped Compose and TV locale.

All source keys and formatting parameters are mandatory in every locale; there
are no grandfathered missing keys. Also report unchanged English text as
linguistic-review candidates (brand names are not automatically violations).
Run after editing catalogs and before PR/merge/release.
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import re
import subprocess
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FMT = re.compile(r"%(?:\d+\$)?[-+#0,.(]*\d*(?:\.\d+)?[diuoxXfFeEgGaAcCsSbBhH%]")

CATALOGS = {
    "compose": {
        "path": "composeApp/src/commonMain/composeResources",
        "source_files": ("strings.xml", "strings_changelog.xml", "strings_mdblist.xml", "strings_redesign.xml"),
        "baseline": "a684b128c",
        "full": True,
    },
    "tv": {
        "path": "tizen/res",
        "source_files": ("strings.xml",),
        "baseline": "c0c6c45fb",
        "full": True,
    },
}


def review_rows(product: str, locale: str, source: dict[str, str],
                current: dict[str, str], previous: dict[str, str]) -> list[tuple[str, ...]]:
    """Report changed translations for linguistic review; these are not approvals."""
    rows: list[tuple[str, ...]] = []
    for key in sorted(source.keys() & current.keys()):
        translated = current[key]
        if previous.get(key) == translated:
            continue
        english = source[key]
        flags = []
        if translated == english and re.search(r"[A-Za-z]{3,} [A-Za-z]{3,}", english):
            flags.append("english_identical")
        if 5 <= len(english) <= 45 and len(translated) > max(35, len(english) * 2.1):
            flags.append("possible_ui_overflow")
        if len(english) >= 20 and len(translated) <= 12 and len(translated) < len(english) * 0.23:
            flags.append("possible_truncation")
        rtl_range = {
            "ar": r"[\u0600-\u06FF]",
            "he": r"[\u0590-\u05FF]",
            "ur": r"[\u0600-\u06FF]",
        }.get(locale)
        if (rtl_range and translated != english and len(english) >= 25
                and not re.search(rtl_range, translated)):
            flags.append("rtl_script_missing")
        rows.append((
            product, locale, "added" if key not in previous else "revised",
            key, english, translated, ",".join(flags),
        ))
    return rows


def write_review_csv(path: str, rows: list[tuple[str, ...]]) -> None:
    """Write a spreadsheet-safe, human-readable review queue."""
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(("product", "locale", "change", "key", "english", "translation", "review_flags"))
        for row in sorted(rows, key=lambda item: (not bool(item[6]), item[1], item[3])):
            # Prevent spreadsheet formula evaluation when the report is opened.
            writer.writerow(("'" + value if value.lstrip().startswith(("=", "+", "-", "@"))
                             else value) for value in row)


def parse_xml(raw: bytes, source: str) -> dict[str, str]:
    data: dict[str, str] = {}
    for element in ET.parse(io.BytesIO(raw)).getroot():
        if element.tag != "string" or not element.get("name"):
            continue
        name = element.attrib["name"]
        if name in data:
            raise AssertionError(f"Duplicate string {name} in {source}")
        data[name] = "".join(element.itertext())
    return data


def catalog(location: str, files: tuple[str, ...], ref: str | None = None) -> dict[str, str]:
    values: dict[str, str] = {}
    for file in files:
        relative = f"{location}/{file}"
        if ref:
            proc = subprocess.run(
                ["git", "show", f"{ref}:{relative}"], cwd=ROOT, capture_output=True,
            )
            if proc.returncode:
                # An absent locale file in a baseline is allowed, but a missing
                # source file means the checkout is too shallow for validation.
                if "/values/" in location:
                    raise AssertionError(f"Missing Git baseline: {ref}:{relative}")
                continue
            raw = proc.stdout
        else:
            path = ROOT / relative
            if not path.exists():
                continue
            raw = path.read_bytes()
        for key, text in parse_xml(raw, relative).items():
            if key in values:
                raise AssertionError(f"Duplicate string {key} in {location}")
            values[key] = text
    return values


def validate(base_ref: str | None = None, *, strict: bool = False,
             report_json: str | None = None, review_csv: str | None = None) -> int:
    errors: list[str] = []
    report: dict[str, object] = {}
    review_queue: list[tuple[str, ...]] = []
    for product, config in CATALOGS.items():
        base = config["path"]
        path = f"{base}/values"
        files = config["source_files"]
        source = catalog(path, files)
        old_source = catalog(path, files, config["baseline"])
        new = set(source) - set(old_source)
        changed = {key for key in set(source) & set(old_source) if source[key] != old_source[key]}
        diff_keys: set[str] = set()
        if base_ref:
            previous_source = catalog(path, files, base_ref)
            diff_keys = (set(source) - set(previous_source)) | {
                key for key in set(source) & set(previous_source)
                if source[key] != previous_source[key]
            }
        required = set(source) if config["full"] else new | changed | diff_keys
        locales = sorted(folder for folder in (ROOT / base).glob("values-*") if folder.is_dir())
        locale_report: dict[str, object] = {}
        for folder in locales:
            locale = folder.name.removeprefix("values-")
            locale_path = f"{base}/{folder.name}"
            current = catalog(locale_path, ("strings.xml",))
            previous = catalog(locale_path, ("strings.xml",), config["baseline"]) if changed and not config["full"] else {}
            base_localized = catalog(locale_path, ("strings.xml",), base_ref) if base_ref else {}
            if base_ref and review_csv:
                review_queue.extend(review_rows(product, locale, source, current, base_localized))
            missing = set(source) - set(current)
            identical = sum(
                1 for key in current.keys() & source.keys()
                if current[key] == source[key] and re.search(r"[A-Za-z]{3,} [A-Za-z]{3,}", source[key])
            )
            locale_report[locale] = {
                "translated": len(current.keys() & source.keys()),
                "source": len(source),
                "missing": len(missing),
                "untranslated_candidates": identical,
                "obsolete_keys": len(current.keys() - source.keys()),
            }
            locale_required = set(source)
            # Keys created or changed in a locale on this PR never receive the
            # historical exemption, even when the English source is unchanged.
            if base_ref:
                locale_required |= {
                    key for key in current.keys() & source.keys()
                    if base_localized.get(key) != current[key]
                }
            for key in sorted(missing & locale_required):
                errors.append(f"{product}/{locale}/{key}: missing translation")
            for key in sorted(locale_required & set(current)):
                translation = current[key]
                if "[[P" in translation or "[[K" in translation:
                    errors.append(f"{product}/{locale}/{key}: leaked draft marker")
                # Literal percent escaping (%%) is not an interpolation argument.
                source_params = (item for item in FMT.findall(source[key]) if item != "%%")
                locale_params = (item for item in FMT.findall(translation) if item != "%%")
                if Counter(source_params) != Counter(locale_params):
                    errors.append(f"{product}/{locale}/{key}: formatting parameters differ")
                source_breaks = source[key].count(r"\n") + source[key].count("\n")
                translated_breaks = translation.count(r"\n") + translation.count("\n")
                if translated_breaks < source_breaks or (
                    source[key].count(r"\t") != translation.count(r"\t")
                ):
                    errors.append(f"{product}/{locale}/{key}: missing required line break/tab")
                if (not config["full"] and key in changed and
                    key in previous and translation == previous[key]):
                    errors.append(f"{product}/{locale}/{key}: stale copy for revised English")
                if key in diff_keys and key in base_localized and translation == base_localized[key]:
                    errors.append(f"{product}/{locale}/{key}: unchanged for modified English in this PR")
        print(
            f"{product}: {len(locales)} locale catalogs, {len(source)} source keys; "
            f"{len(new)} newer keys, {len(changed)} revised strings; "
            f"full key coverage required"
        )
        report[product] = {"source_keys": len(source), "locales": locale_report}
        for locale, value in locale_report.items():
            if value["missing"] or value["untranslated_candidates"]:
                print(f"  {locale}: {value['missing']} missing, "
                      f"{value['untranslated_candidates']} possible English fallbacks")
    if report_json:
        destination = Path(report_json)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if review_csv and base_ref:
        write_review_csv(review_csv, review_queue)
        print(f"Review CSV: {len(review_queue)} changed locale strings (not linguistic approval)")
    if errors:
        for err in errors[:100]:
            print("ERROR:", err)
        print(f"FAILED: {len(errors)} translation parity errors")
        return 1
    print("PASS: all Compose and TV source keys covered in every supported locale")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-ref", default=None, help="Git commit before the PR/push for incremental parity")
    parser.add_argument("--strict", action="store_true", help="Require full catalog coverage in all locales")
    parser.add_argument("--report-json", default=None, help="Write measured locale coverage as JSON")
    parser.add_argument("--review-csv", default=None,
                        help="Export changed locale entries for human review (requires --base-ref)")
    args = parser.parse_args()
    raise SystemExit(validate(args.base_ref or None, strict=args.strict,
                              report_json=args.report_json, review_csv=args.review_csv))
