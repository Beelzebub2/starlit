"""Check complete Starlit UI translations across every shipped Compose and TV locale.

All source strings and formatting parameters are mandatory in every locale.
Report historical plural gaps; newly added or revised groups are mandatory. Also report unchanged English text as
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
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FMT = re.compile(r"%(?:\d+\$)?[-+#0,.(]*\d*(?:\.\d+)?[diuoxXfFeEgGaAcCsSbBhH%]")

CATALOGS = {
    "compose": {
        "path": "composeApp/src/commonMain/composeResources",
        "baseline": "a684b128c",
    },
    "tv": {
        "path": "tizen/res",
        "baseline": "c0c6c45fb",
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
            "fa": r"[\u0600-\u06FF]",
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


@dataclass
class Catalog:
    strings: dict[str, str]
    plurals: dict[str, dict[str, str]]


def resource_files(location: str, ref: str | None = None) -> list[str]:
    if ref is None:
        return sorted(path.name for path in (ROOT / location).glob("*.xml") if path.is_file())
    # Enumerate the files actually present in the baseline. Do not guess their names
    # from the current tree: a new XML file can be added in a feature branch.
    result = subprocess.run(["git", "ls-tree", "--name-only", f"{ref}:{location}"],
                            cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        return []
    return sorted(name for name in result.stdout.splitlines() if name.endswith(".xml"))


def catalog(location: str, ref: str | None = None) -> Catalog:
    resources = Catalog({}, {})
    for name in resource_files(location, ref):
        relative = f"{location}/{name}"
        if ref is None:
            raw = (ROOT / relative).read_bytes()
        else:
            raw = subprocess.check_output(["git", "show", f"{ref}:{relative}"], cwd=ROOT)
        for node in ET.parse(io.BytesIO(raw)).getroot():
            key = node.get("name")
            if not key:
                continue
            if node.tag == "string":
                # Baselines may predate duplicate checking. The current checkout
                # must have exactly one definition per key and locale.
                if key in resources.strings and ref is None:
                    raise AssertionError(f"Duplicate string {key} in {relative}")
                resources.strings[key] = "".join(node.itertext())
            if node.tag == "plurals":
                if key in resources.plurals and ref is None:
                    raise AssertionError(f"Duplicate plural group {key} in {relative}")
                values = {}
                for item in node:
                    quantity = item.get("quantity")
                    if (item.tag != "item" or quantity not in PLURAL_QUANTITIES or
                            quantity in values):
                        raise AssertionError(f"Invalid or duplicate plural quantity in {relative}/{key}")
                    values[quantity] = "".join(item.itertext())
                resources.plurals[key] = values
    return resources


PLURAL_QUANTITIES = {"zero", "one", "two", "few", "many", "other"}


def placeholders(text: str) -> Counter[str]:
    return Counter(value for value in FMT.findall(text) if value != "%%")


def plural_problems(source: dict[str, dict[str, str]],
                    current: dict[str, dict[str, str]]) -> dict[str, list[str]]:
    problems: dict[str, list[str]] = {}
    for key in sorted(source.keys() & current.keys()):
        english = source[key]
        localized = current[key]
        errors = []
        if "other" not in localized:
            errors.append("missing the required other quantity")
        for quantity, text in sorted(localized.items()):
            # A locale can need zero/two/few/many even though English does not.
            expected = english.get(quantity, english.get("other", ""))
            if placeholders(expected) != placeholders(text):
                errors.append(f"{quantity}: formatting parameters differ")
        if errors:
            problems[key] = errors
    return problems


def validate(base_ref: str | None = None, *, strict: bool = False,
             report_json: str | None = None, review_csv: str | None = None) -> int:
    errors: list[str] = []
    report: dict[str, object] = {}
    review_queue: list[tuple[str, ...]] = []
    for product, config in CATALOGS.items():
        base = config["path"]
        path = f"{base}/values"
        source_catalog = catalog(path)
        source, source_plurals = source_catalog.strings, source_catalog.plurals
        old_source = catalog(path, config["baseline"]).strings
        if not source or not old_source:
            raise AssertionError(f"Missing source catalog or Git baseline for {path}")
        new = set(source) - set(old_source)
        changed = {key for key in set(source) & set(old_source) if source[key] != old_source[key]}
        diff_keys: set[str] = set()
        new_plurals: set[str] = set()
        if base_ref:
            previous = catalog(path, base_ref)
            previous_source = previous.strings
            if not previous_source:
                raise AssertionError(f"Missing comparison source catalog for {base_ref}:{path}")
            new_plurals = {key for key, items in source_plurals.items()
                           if previous.plurals.get(key) != items}
            diff_keys = (set(source) - set(previous_source)) | {
                key for key in set(source) & set(previous_source)
                if source[key] != previous_source[key]
            }
        locales = {
            folder.name for folder in (ROOT / base).glob("values-*") if folder.is_dir()
        }
        if base_ref:
            # A whole locale deletion must not look like perfect parity.
            baseline_dirs = subprocess.check_output(
                ["git", "ls-tree", "-d", "--name-only", f"{base_ref}:{base}"],
                cwd=ROOT, text=True, encoding="utf-8",
            )
            locales.update(name for name in baseline_dirs.splitlines() if name.startswith("values-"))
        locale_report: dict[str, object] = {}
        for folder in sorted(locales):
            locale = folder.removeprefix("values-")
            locale_path = f"{base}/{folder}"
            current_catalog = catalog(locale_path)
            current = current_catalog.strings
            base_localized = catalog(locale_path, base_ref).strings if base_ref else {}
            missing_plurals = source_plurals.keys() - current_catalog.plurals.keys()
            plural_issues = plural_problems(source_plurals, current_catalog.plurals)
            for key in sorted(new_plurals & missing_plurals):
                errors.append(f"{product}/{locale}/{key}: missing plural group")
            for key in sorted(new_plurals & plural_issues.keys()):
                for problem in plural_issues[key]:
                    errors.append(f"{product}/{locale}/{key}: {problem}")
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
                "missing_plural_groups": sorted(missing_plurals),
                "plural_format_problems": plural_issues,
            }
            for key in sorted(missing):
                errors.append(f"{product}/{locale}/{key}: missing translation")
            for key in sorted(source.keys() & current.keys()):
                translation = current[key]
                if "[[P" in translation or "[[K" in translation:
                    errors.append(f"{product}/{locale}/{key}: leaked draft marker")
                if placeholders(source[key]) != placeholders(translation):
                    errors.append(f"{product}/{locale}/{key}: formatting parameters differ")
                source_breaks = source[key].count(r"\n") + source[key].count("\n")
                translated_breaks = translation.count(r"\n") + translation.count("\n")
                if translated_breaks < source_breaks or (
                    source[key].count(r"\t") != translation.count(r"\t")
                ):
                    errors.append(f"{product}/{locale}/{key}: missing required line break/tab")
                if key in diff_keys and key in base_localized and translation == base_localized[key]:
                    errors.append(f"{product}/{locale}/{key}: unchanged for modified English in this PR")
        print(
            f"{product}: {len(locales)} locale catalogs, {len(source)} source keys; "
            f"{len(new)} newer keys, {len(changed)} revised strings; "
            f"full key coverage required"
        )
        report[product] = {"source_keys": len(source), "source_plural_groups": len(source_plurals),
                           "locales": locale_report}
        for locale, value in locale_report.items():
            if (value["missing"] or value["untranslated_candidates"] or
                    value["missing_plural_groups"] or value["plural_format_problems"]):
                print(f"  {locale}: {value['missing']} missing, "
                      f"{value['untranslated_candidates']} possible English fallbacks; "
                      f"{len(value['missing_plural_groups'])} missing plural groups, "
                      f"{len(value['plural_format_problems'])} plural format candidates")
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
    print("PASS: all Compose and TV string keys covered; historical plural debt reported separately")
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
