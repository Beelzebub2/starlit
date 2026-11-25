
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import test from "node:test";

const root = new URL("../", import.meta.url);
const read = (name) => fs.readFileSync(new URL(name, root), "utf8");
const app = read("web/app.js");
const html = read("web/index.html");
const resources = read("../tizen/js/i18n/index.js");

function appDictionaries() {
  const prefix = app.slice(0, app.indexOf("// Translation bundles"));
  return vm.runInNewContext(prefix + "\ncopy;", {});
}
function extraDictionaries() {
  const ctx = {};
  ctx.globalThis = ctx;
  vm.runInNewContext(read("web/translations.js"), ctx);
  return ctx.STARLIT_TV_SETUP_TRANSLATIONS;
}
const enAndPortuguese = appDictionaries();

test("TV setup bundles contain every message in every supported TV locale", () => {
  const match = resources.match(/const SUPPORTED_LOCALES = (\[[\s\S]*?\]);/);
  assert.ok(match, "missing canonical locale list");
  const locales = JSON.parse(match[1]);
  const extra = extraDictionaries();
  const keys = Object.keys(enAndPortuguese.en).sort();
  assert.deepEqual(Object.keys(enAndPortuguese.pt).sort(), keys);
  assert.deepEqual(Object.keys(extra).sort(), locales.filter(x => !["en", "pt-pt"].includes(x)).sort());
  for (const [locale, messages] of Object.entries(extra)) {
    assert.deepEqual(Object.keys(messages).sort(), keys, locale + " keys");
    for (const [key, text] of Object.entries(messages)) {
      assert.equal(typeof text, "string");
      assert.ok(text.trim(), locale + "/" + key + ": empty");
      assert.doesNotMatch(text, /\[\[(?:P|K)\d{4}\]\]/, locale + "/" + key + ": template leak");
      assert.doesNotMatch(text, /[\u0000-\u0008\u000b\u000e-\u001f]/, locale + "/" + key + ": control character");
    }
  }
  for (const key of [...html.matchAll(/data-i18n="([^"]+)"/g)].map(match => match[1])) {
    assert.ok(keys.includes(key), "HTML key not in English source: " + key);
  }
});

test("the browser loads translations before the app and chooses specific regional variants", () => {
  assert.ok(html.indexOf('src="/tv/translations.js"') < html.indexOf('src="/tv/app.js"'));
  const copy = { ...enAndPortuguese, ...extraDictionaries() };
  const start = app.indexOf("function selectLocale(");
  const end = app.indexOf("const locale = selectLocale();", start);
  assert.ok(start > 0 && end > start);
  const ctx = { navigator: { language: "en", languages: ["en"] } };
  const choose = vm.runInNewContext("const copy = " + JSON.stringify(copy) + ";\n" + app.slice(start, end) + "\nselectLocale;", ctx);
  assert.equal(choose(["pt-PT"]), "pt");
  assert.equal(choose(["pt-BR"]), "pt-br");
  assert.equal(choose(["es-MX"]), "es-419");
  assert.equal(choose(["fr-CA"]), "fr");
  assert.equal(choose(["zh-Hant"]), "zh-tw");
  assert.equal(choose(["zh-TW"]), "zh-tw");
  assert.equal(choose(["zh-HK"]), "zh-tw");
  assert.equal(choose(["zh-MO"]), "zh-tw");
  assert.equal(choose(["zh-Hans-TW"]), "zh-cn");
  assert.equal(choose(["zh-SG"]), "zh-cn");
  assert.equal(choose(["iw-IL"]), "he");
  assert.equal(choose(["in-ID"]), "id");
  assert.equal(choose(["nb-NO"]), "no");
  assert.equal(choose(["sr-Latn-RS"]), "sr-latn");
  assert.equal(choose(["xx-XX"]), "en");
});

test("localized document direction keeps pairing codes and URLs left-to-right", () => {
  const extra = extraDictionaries();
  const end = app.indexOf("document.title =");
  assert.ok(end > app.indexOf("const locale = selectLocale();"));
  const rtl = new Set(["ar", "he"]);
  for (const locale of [...Object.keys(enAndPortuguese), ...Object.keys(extra), "ar-EG", "iw-IL", "xx-XX"]) {
    const documentElement = {};
    const expectedLocale = {"ar-EG":"ar", "iw-IL":"he", "xx-XX":"en"}[locale] ?? locale;
    vm.runInNewContext(app.slice(0, end), {
      STARLIT_TV_SETUP_TRANSLATIONS: extra,
      navigator: { language: locale, languages: [locale] },
      document: { documentElement },
    });
    assert.equal(documentElement.lang, expectedLocale === "pt" ? "pt-PT" : expectedLocale, locale);
    assert.equal(documentElement.dir, rtl.has(expectedLocale) ? "rtl" : "ltr", locale);
  }
  for (const id of ["pair-code", "verify-code", "addon-url", "plugin-url"]) {
    assert.match(html.match(new RegExp('<[^>]* id="' + id + '"[^>]*>'))?.[0] || "", /\bdir="ltr"/);
  }
});
