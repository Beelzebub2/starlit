// Run with: node --test composeApp/src/desktopTest/js/track-labels.test.mjs
// Same cases as TrackLabelPartsTest.kt so the desktop chrome and Compose panel read tracks alike.
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { test } from "node:test";

const require = createRequire(import.meta.url);
const { describeTrack } = require("../../desktopMain/resources/player-ui/track-labels.js");

const audio = (label, language, languageName) => describeTrack({ label, language, languageName, kind: "audio" });
const subtitle = (label, language, languageName, forced = false) =>
  describeTrack({ label, language, languageName, kind: "subtitle", forced });

test("release group titles become a language and ordered facts", () => {
  assert.deepEqual(
    audio("Japanese / Opus / 2.0 / 48 kHz / 192 kbps / JPBD (Stereo)", "jpn", "Japanese"),
    { title: "Japanese", badges: [], detail: "Opus · Stereo · 192 kbps · JPBD" },
  );
  const english = audio("English / Opus / 2.0 / 48 kHz / 192 kbps / USBD (unknown, Opus)");
  assert.equal(english.title, "English");
  assert.equal(english.detail, "Opus · Stereo · 192 kbps · USBD");
});

test("codecs use familiar names and keep Atmos", () => {
  assert.equal(audio("English / E-AC-3 / 5.1 / 640 kbps", "en", "English").detail, "Dolby Digital Plus · 5.1 · 640 kbps");
  assert.equal(audio("TrueHD Atmos 7.1", "en", "English").detail, "Dolby TrueHD Atmos · 7.1");
  assert.equal(audio("English (5.1, E-AC-3-JOC)", "en", "English").detail, "Dolby Digital Plus Atmos · 5.1");
  assert.equal(audio("DTS-HD MA 5.1", "en", "English").detail, "DTS-HD MA · 5.1");
  assert.equal(audio("AAC 2.0", "en", "English").detail, "AAC · Stereo");
  assert.equal(audio("ac3 / 6 ch", "en", "English").detail, "Dolby Digital · 5.1");
  assert.equal(audio("Japanese / JPBD (Opus Stereo)", "", "Japanese").detail, "Opus · Stereo · JPBD");
});

test("keywords become badges without repeating on line two", () => {
  assert.deepEqual(audio("English / Commentary / AAC / 2.0", "en", "English").badges, ["commentary"]);
  assert.equal(audio("English / Commentary / AAC / 2.0", "en", "English").detail, "AAC · Stereo");
  assert.equal(audio("Director's Commentary", "en", "English").detail, "Director's Commentary");
  assert.deepEqual(subtitle("English [SDH]", "en", "English"), { title: "English", badges: ["sdh"], detail: "" });
  assert.deepEqual(audio("hi", "hi", "Hindi").badges, [], "a bare language code is not the SDH keyword");
});

test("subtitles put the edition before the format and honour the forced flag", () => {
  assert.deepEqual(
    subtitle("Signs & Songs (SSA)", "en", "English", true),
    { title: "English", badges: ["forced"], detail: "Signs & Songs · ASS" },
  );
  assert.equal(subtitle("hdmv_pgs_subtitle", "de", "German").detail, "PGS");
});

test("language repeats, codes and generated names never reach line two", () => {
  assert.equal(audio("English", "en", "English").detail, "");
  assert.equal(subtitle("Portuguese (Brazil)", "pt-BR", "Portuguese (Brazil)").detail, "");
  assert.equal(audio("eng", "eng", "English").detail, "");
  assert.equal(audio("Track 3").title, "");
  assert.equal(audio("Track 3 (Stereo, AAC)").detail, "AAC · Stereo");
  assert.deepEqual(audio("Hindi", "hin", "hin"), { title: "Hindi", badges: [], detail: "" });
});
