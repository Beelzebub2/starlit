import assert from "node:assert/strict";
import test from "node:test";
import fs from "node:fs";
import path from "node:path";
const root = path.resolve(import.meta.dirname || path.dirname(new URL(import.meta.url).pathname), "..");
const read = (name) => fs.readFileSync(path.join(root, name), "utf8");

test("addon subtitle label survives repository normalization", () => {
  const source = read("js/data/repository/subtitleRepository.js");
  assert.match(source, /label: typeof subtitle\.label === "string"/);
  assert.match(source, /subtitle\.label\.trim\(\)\.slice\(0, 160\)/);
});

test("subtitle track selector displays addon-provided label before language fallback", () => {
  const source = read("js/ui/screens/player/playerScreenMethods-49-collect-subtitle-option-items.js");
  assert.match(source, /title: cleanDisplayText\(track\?\.label\) \|\| languageLabel/);
});

test("mounted HTML text tracks use the descriptive label", () => {
  const source = read("js/ui/screens/player/playerScreenMethods-68-load-subtitles.js");
  assert.match(source, /subtitle\.label \|\| subtitle\.lang/);
});
