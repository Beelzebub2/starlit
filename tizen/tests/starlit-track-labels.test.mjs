import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFile } from "node:fs/promises";
import { describeTrack } from "../js/ui/screens/player/trackLabelParts.js";

const require = createRequire(import.meta.url);
const desktop = require("../../composeApp/src/desktopMain/resources/player-ui/track-labels.js");

const cases = [
  { label: "Japanese / Opus / 2.0 / 48 kHz / 192 kbps / JPBD (Stereo)", language: "jpn", languageName: "Japanese" },
  { label: "English / Opus / 2.0 / 48 kHz / 192 kbps / USBD (unknown, Opus)" },
  { label: "English (E-AC-3 5.1)", language: "en", languageName: "English" },
  { label: "TrueHD Atmos 7.1", language: "en", languageName: "English" },
  { label: "English / Commentary / AAC / 2.0", language: "en", languageName: "English" },
  { label: "Signs & Songs (SSA)", language: "en", languageName: "English", kind: "subtitle", forced: true },
  { label: "Portuguese (Brazil)", language: "pt-BR", languageName: "Portuguese (Brazil)", kind: "subtitle" },
  { label: "hi", language: "hi", languageName: "Hindi" },
  { label: "Track 3 (Stereo, AAC)" },
  { label: "Japanese / Opus / 2.0 / 48 kHz / 192 kbps / JPBD (Opus Stereo)", languageName: "Japanese" },
];

test("TV track labels read like the desktop and phone menus", () => {
  assert.deepEqual(describeTrack(cases[0]), { title: "Japanese", badges: [], detail: "Opus · Stereo · 192 kbps · JPBD" });
  assert.equal(describeTrack(cases[2]).detail, "Dolby Digital Plus · 5.1");
  assert.deepEqual(describeTrack(cases[4]).badges, ["commentary"]);
  assert.equal(describeTrack(cases[5]).detail, "Signs & Songs · ASS");
  assert.equal(describeTrack(cases[9]).detail, "Opus · Stereo · 192 kbps · JPBD", "TV codec suffix joins with a space");
});

test("the TV copy of the label rules matches the desktop player copy", () => {
  cases.forEach((input) => assert.deepEqual(describeTrack(input), desktop.describeTrack(input), input.label));
});

test("the TV copy stays parseable by Tizen 4 (no Unicode property escapes)", async () => {
  const source = await readFile(new URL("../js/ui/screens/player/trackLabelParts.js", import.meta.url), "utf8");
  assert.doesNotMatch(source, /\p\{/);
});
