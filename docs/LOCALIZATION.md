# Starlit localization

English in `composeApp/src/commonMain/composeResources/values` is the fallback
for missing translated keys. The Appearance > App language menu is defined by
`AppLanguage.kt`; language choices use standard BCP 47 tags and are persisted
independently of the account profile.

## Additional language drafts (November 2026)

The additional catalogs are Chinese (Simplified and Traditional), Korean, Thai,
Ukrainian, Persian (RTL), Malay, Filipino, Swedish, Finnish, Tamil, Telugu and
Swahili. **Hindi is intentionally not included.** Existing catalogs (including
Portuguese for Portugal and Brazil) are preserved.

New catalog strings were machine-translated from the current English Compose
resources. These are **draft localizations, not professionally reviewed copies**.
Before declaring a language fully polished, obtain native-speaker review of UI
context, plurals, accessibility descriptions, technical language, legal/privacy
text, text overflow, and right-to-left alignment (Persian). Some English
fallbacks may remain where the translation provider could not safely retain
format placeholders; default English plural resources remain in use unless a
locale-specific plural translation is supplied.

## Maintenance

- Add new English keys to the source files. Keep names, `%1$s` / `%1$d`
  placeholders, escaped newlines and technical identifiers unchanged.
- Localize newly added strings for the supported locales; untranslated keys
  fall back to English. Never replace a native-reviewed translation with
  an automated draft.
- Run `py -3 scripts/check_localizations.py` to detect key drift, leaked
  translation markers, and formatting mismatches in the additional catalogs.
- Run `python3 scripts/check_translation_parity.py --strict --base-ref <before-change SHA>`
  to check strings in all values XML files, including feature-specific catalogs.
  Add `--report-json` with an output path for per-locale string and plural
  coverage. Existing plural fallbacks are reported separately; new or revised
  groups must be localized. Preserve locale-specific plural quantities and
  include an `other` case.
- Validate builds with the Compose resource generator and test switching
  between device language, Portuguese, Chinese variants and Persian on the
  supported platforms.
- `py -3 scripts/translate_popular_languages.py` is provided only to generate
  initial drafts in a clean worktree. It uses a best-effort public translation
  service (network required), refuses to overwrite an existing locale directory,
  and does not guarantee linguistic correctness. Do not run it during CI.
