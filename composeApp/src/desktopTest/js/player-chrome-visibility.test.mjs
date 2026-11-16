// Run with: node --test composeApp/src/desktopTest/js/player-chrome-visibility.test.mjs
// Keep the standalone Back button synchronized with desktop player chrome visibility.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const resources = new URL("../../desktopMain/resources/player-ui/", import.meta.url);
const html = readFileSync(new URL("controls.html", resources), "utf8");
const css = readFileSync(new URL("controls.css", resources), "utf8");
const script = readFileSync(new URL("controls.js", resources), "utf8");

const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(([, selectors, declarations]) => ({
  selectors: selectors.split(",").map(selector => selector.trim()),
  declarations,
}));

const declarationsFor = selector => rules
  .filter(rule => rule.selectors.includes(selector))
  .map(rule => rule.declarations)
  .join("\n");

test("Back fades and cannot intercept clicks when the player controls hide", () => {
  assert.match(html, /class="header-button back-button" id="backButton"/);
  assert.match(script, /root\.classList\.toggle\("chrome-hidden", Boolean\(showError \|\| !state\.controlsVisible\)\)/);

  const hidden = declarationsFor(".player.chrome-hidden .back-button");
  assert.match(hidden, /opacity:\s*0\s*;/, "Back must fade with the player controls");
  assert.match(hidden, /pointer-events:\s*none\s*;/, "Hidden Back must not intercept pointer input");
});

test("Back is also hidden for playback errors without disabling its normal state", () => {
  const error = declarationsFor(".player.error-active .back-button");
  assert.match(error, /opacity:\s*0\s*;/);
  assert.match(error, /pointer-events:\s*none\s*;/);

  const button = declarationsFor(".header-button.back-button");
  assert.match(button, /background:/, "Back retains its visible chrome styling");
  const resizing = declarationsFor(".player.native-resizing .back-button");
  assert.match(resizing, /transition-duration:\s*1ms\s*!important\s*;/);
});
