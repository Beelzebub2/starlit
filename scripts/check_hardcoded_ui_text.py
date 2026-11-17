"""Prevent newly introduced literal copy in Kotlin and Swift UI call sites.

Historical hard-coded strings are grandfathered; newly added text must use a
localized resource or content dynamically provided by the application.
"""
from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = re.compile(r"^(?:composeApp/src/[^/]+Main/kotlin|androidApp/src/main/kotlin|tvApp/src/main/kotlin)/.*\.kt$")
SWIFT = re.compile(r"^iosApp/iosApp/.*\.swift$")
UI_LITERAL = re.compile(
    r'\b(?:Text|Button|TextButton|DialogButton|MenuItem|SecureField|TextField|OutlinedTextField)'
    r'\s*\(\s*(?:text\s*=\s*|label\s*=\s*|title\s*=\s*)?"(?P<call>(?:[^"\\]|\\.)*)"'
    r'|\b(?:contentDescription|accessibilityLabel|accessibilityHint|placeholder|label|title)'
    r'\s*=\s*"(?P<property>(?:[^"\\]|\\.)*)"',
    re.MULTILINE,
)
INTERPOLATION = re.compile(r"\$\{[^}]*\}|\$[a-zA-Z_][a-zA-Z_0-9]*|\\\([^)]*\)")
CONSTANTS = {"Starlit", "MyAnimeList", "Trakt", "TMDB", "AniList", "Simkl"}


def literal_ui_copy(source: str) -> Counter[str]:
    found: Counter[str] = Counter()
    for match in UI_LITERAL.finditer(source):
        literal = match.group("call") or match.group("property") or ""
        if literal in CONSTANTS:
            continue
        if re.search(r"[A-Za-zÀ-ÿ]", INTERPOLATION.sub("", literal)):
            found[literal] += 1
    return found


def git(*args: str, ok: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True,
        encoding="utf-8", errors="replace",
    )
    if ok and result.returncode:
        raise RuntimeError(result.stderr.strip() or "git command failed")
    return result


def validate(base_ref: str | None) -> int:
    if not base_ref or not base_ref.strip("0"):
        parents = git("rev-parse", "HEAD^", ok=False)
        if parents.returncode:
            raise RuntimeError("No baseline for hard-coded UI text check")
        base_ref = parents.stdout.strip()
    git("rev-parse", "--verify", f"{base_ref}^{{commit}}")
    changed = git("diff", "--name-only", f"{base_ref}...HEAD", "--",
                  "composeApp/src", "androidApp/src", "tvApp/src", "iosApp/iosApp")
    failures: list[str] = []
    checked = 0
    for path in changed.stdout.splitlines():
        if not (KOTLIN.fullmatch(path) or SWIFT.fullmatch(path)):
            continue
        current = ROOT / path
        if not current.is_file():
            continue
        previous = git("show", f"{base_ref}:{path}", ok=False)
        old = literal_ui_copy(previous.stdout if previous.returncode == 0 else "")
        new = literal_ui_copy(current.read_text(encoding="utf-8-sig"))
        checked += 1
        for literal, count in sorted((new - old).items()):
            failures.append(f"{path}: new hard-coded UI copy: {literal!r} ({count})")
    for failure in failures[:60]:
        print("ERROR:", failure)
    if failures:
        print(f"FAIL: {len(failures)} newly introduced UI string literals")
        return 1
    print(f"PASS: no new hard-coded UI labels in {checked} changed source files")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-ref", default=None, help="Before-change Git SHA")
    options = parser.parse_args()
    raise SystemExit(validate(options.base_ref))
