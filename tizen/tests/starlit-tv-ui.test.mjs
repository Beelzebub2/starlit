import test, { mock } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import postcss from "postcss";

if (!globalThis.localStorage) {
  const storage = new Map();
  globalThis.localStorage = {
    getItem: (key) => (storage.has(String(key)) ? storage.get(String(key)) : null),
    setItem: (key, value) => storage.set(String(key), String(value)),
    removeItem: (key) => storage.delete(String(key))
  };
}

const { playScreenEnter, ScreenUtils, SCREEN_ENTER_CLASS, SCREEN_ENTER_CLEAR_MS } = await import(
  "../js/ui/navigation/screen.js"
);
const { applyNavGliderBox, getNavSelectionSettleMs, NAV_SELECTION_SETTLE_MS, createSettingsScreenMethods13 } =
  await import("../js/ui/screens/settings/settingsScreenMethods-13-apply-focus.js");
const { renderAddonRow } = await import("../js/ui/screens/plugin/pluginScreen.js");

function fakeElement() {
  const classes = new Set();
  return {
    style: {},
    classList: {
      add: (name) => classes.add(name),
      remove: (name) => classes.delete(name),
      contains: (name) => classes.has(name),
      toggle: (name, on) => (on ? classes.add(name) : classes.delete(name))
    }
  };
}

test("a screen that becomes visible plays its enter animation once", () => {
  const timers = [];
  const schedule = (fn, ms) => {
    timers.push({ fn, ms });
    return timers.length;
  };
  const cancelled = [];
  const container = fakeElement();
  playScreenEnter(container, schedule, (id) => cancelled.push(id));
  assert.equal(container.classList.contains(SCREEN_ENTER_CLASS), true);
  assert.equal(timers[0].ms, SCREEN_ENTER_CLEAR_MS);
  // Navigating back to it before the class clears restarts the timer.
  playScreenEnter(container, schedule, (id) => cancelled.push(id));
  assert.deepEqual(cancelled, [1]);
  timers[1].fn();
  assert.equal(container.classList.contains(SCREEN_ENTER_CLASS), false);
});

test("ScreenUtils.show only animates a screen that was hidden", () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const hidden = fakeElement();
    hidden.style.display = "none";
    ScreenUtils.show(hidden);
    assert.equal(hidden.style.display, "block");
    assert.equal(hidden.classList.contains(SCREEN_ENTER_CLASS), true);
    mock.timers.tick(SCREEN_ENTER_CLEAR_MS);
    assert.equal(hidden.classList.contains(SCREEN_ENTER_CLASS), false);

    // Re-rendering a visible screen must not replay it.
    ScreenUtils.show(hidden);
    assert.equal(hidden.classList.contains(SCREEN_ENTER_CLASS), false);
  } finally {
    mock.timers.reset();
  }
});

test("the settings glider moves by transform and only resizes when needed", () => {
  const glider = { style: { width: "", height: "" } };
  applyNavGliderBox(glider, { x: 10.4, y: 124.6, width: 440, height: 58 });
  assert.equal(glider.style.transform, "translate3d(10px, 125px, 0)");
  assert.equal(glider.style.webkitTransform, "translate3d(10px, 125px, 0)");
  assert.equal(glider.style.width, "440px");
  assert.equal(glider.style.height, "58px");
  let writes = 0;
  const tracked = {
    style: new Proxy(
      { width: "440px", height: "58px" },
      {
        set(target, key, value) {
          if (key === "width" || key === "height") writes += 1;
          target[key] = value;
          return true;
        }
      }
    )
  };
  applyNavGliderBox(tracked, { x: 10, y: 186, width: 440, height: 58 });
  assert.equal(writes, 0, "same size: no layout-affecting write");
  assert.equal(tracked.style.transform, "translate3d(10px, 186px, 0)");
});

test("constrained TVs wait longer for settings focus to settle", () => {
  assert.equal(getNavSelectionSettleMs(false), NAV_SELECTION_SETTLE_MS.standard);
  assert.equal(getNavSelectionSettleMs(true), NAV_SELECTION_SETTLE_MS.constrained);
  assert.ok(NAV_SELECTION_SETTLE_MS.constrained > NAV_SELECTION_SETTLE_MS.standard);
});

function createNavHarness() {
  const methods = createSettingsScreenMethods13();
  const screen = {
    ...methods,
    isMounted: true,
    focusZone: "nav",
    activeSection: "account",
    navIndex: 0,
    visibleSections: [{ id: "account" }, { id: "profiles" }, { id: "appearance" }, { id: "display" }],
    focusCalls: 0,
    opened: [],
    applyFocus() {
      this.focusCalls += 1;
    },
    async activateNavSelection() {
      this.activeSection = this.visibleSections[this.navIndex].id;
      this.opened.push(this.activeSection);
    }
  };
  return screen;
}

test("holding Down through Settings opens only the section focus stops on", () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const screen = createNavHarness();
    screen.moveNavFocus(1);
    mock.timers.tick(NAV_SELECTION_SETTLE_MS.standard - 10);
    screen.moveNavFocus(2);
    mock.timers.tick(NAV_SELECTION_SETTLE_MS.standard - 10);
    screen.moveNavFocus(3);
    assert.equal(screen.focusCalls, 3, "the highlight moved on every press");
    assert.deepEqual(screen.opened, [], "no section was built mid-hold");
    mock.timers.tick(NAV_SELECTION_SETTLE_MS.standard);
    assert.deepEqual(screen.opened, ["display"]);
  } finally {
    mock.timers.reset();
  }
});

test("another key opens the pending section before it is handled", async () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const screen = createNavHarness();
    screen.moveNavFocus(2);
    assert.equal(screen.isNavStepKey(40), true);
    assert.equal(screen.isNavStepKey(39), false);
    assert.equal(await screen.flushNavSelection(), true);
    assert.deepEqual(screen.opened, ["appearance"]);
    mock.timers.tick(NAV_SELECTION_SETTLE_MS.constrained);
    assert.deepEqual(screen.opened, ["appearance"], "the cancelled timer never fires");
    assert.equal(await screen.flushNavSelection(), false);
  } finally {
    mock.timers.reset();
  }
});

test("moving back onto the open section cancels the pending one", () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const screen = createNavHarness();
    screen.moveNavFocus(1);
    screen.moveNavFocus(0);
    mock.timers.tick(NAV_SELECTION_SETTLE_MS.constrained);
    assert.deepEqual(screen.opened, []);
  } finally {
    mock.timers.reset();
  }
});

test("add-on rows escape manifest text and fall back to the host", () => {
  const row = renderAddonRow(
    "https://addons.example/manifest.json",
    2,
    { name: '<img src=x onerror="1">Torrentio', version: "1.2.0", logo: 'https://cdn.example/a.png" onload="x' },
    true
  );
  assert.doesNotMatch(row, /<img src=x/);
  assert.match(row, /&lt;img src=x onerror=&quot;1&quot;&gt;Torrentio/);
  assert.match(row, /src="https:\/\/cdn\.example\/a\.png&quot; onload=&quot;x"/);
  assert.match(row, /addons\.example · v1\.2\.0/);
  assert.match(row, /data-action="toggle"[^>]*>Disable</);
  assert.match(row, /data-focus-key="remove:2"/);

  const bare = renderAddonRow("https://stream-host.example/manifest.json", 0, null, false);
  assert.match(bare, /tv-addon-row is-disabled/);
  assert.match(bare, /<span class="tv-addon-name">stream-host.example<\/span>/);
  assert.match(bare, /<span>SH<\/span>/);
  assert.match(bare, /Manifest not loaded yet/);
  assert.match(bare, />Enable</);
});

// ---- Stylesheet guard ---------------------------------------------------------

const UI_CSS = new URL("../css/starlit-ui.css", import.meta.url);
const COMPOSITOR_ONLY = /^(?:-webkit-)?(?:transform|opacity)$/;

function transitionProperties(decl) {
  if (/-property$/.test(decl.prop)) {
    return decl.value.split(",").map((part) => part.trim());
  }
  // Shorthand: the first token of each comma-separated transition.
  return decl.value
    .split(/,(?![^(]*\))/)
    .map((part) => part.trim().split(/\s+/)[0])
    .filter(Boolean);
}

test("the Starlit UI layer only ever animates transform and opacity", async () => {
  const root = postcss.parse(await readFile(UI_CSS, "utf8"));
  const offenders = [];
  root.walkDecls((decl) => {
    const where = `${decl.parent.selector || decl.parent.params}: ${decl.prop}: ${decl.value}`;
    if (/^(?:-webkit-)?transition(?:-property)?$/.test(decl.prop)) {
      const props = transitionProperties(decl);
      if (!props.every((prop) => prop === "none" || COMPOSITOR_ONLY.test(prop))) offenders.push(where);
    }
    if (/backdrop-filter$/.test(decl.prop) && !/^none\b/.test(decl.value)) offenders.push(where);
    if (/filter$/.test(decl.prop) && /blur\(/.test(decl.value)) offenders.push(where);
    if (decl.prop === "will-change" && decl.value !== "auto") offenders.push(where);
  });
  root.walkAtRules(/keyframes$/, (rule) => {
    rule.walkDecls((decl) => {
      if (!COMPOSITOR_ONLY.test(decl.prop)) offenders.push(`@keyframes ${rule.params}: ${decl.prop}`);
    });
  });
  assert.deepEqual(offenders, []);
});

test("the Starlit UI layer stays inside what Tizen 4 can parse", async () => {
  const css = await readFile(UI_CSS, "utf8");
  const withoutComments = css.replace(/\/\*[\s\S]*?\*\//g, "");
  assert.doesNotMatch(withoutComments, /\b\d+(?:\.\d+)?(?:vw|vh|vmin|vmax)\b/);
  assert.doesNotMatch(withoutComments, /\b(?:clamp|min|max)\(/);
  assert.doesNotMatch(withoutComments, /:(?:is|where|has|focus-visible)\(/);
});

test("every flex gap in the UI layer gets the no-flex-gap fallback", async () => {
  const root = postcss.parse(await readFile(UI_CSS, "utf8"));
  const missing = [];
  root.walkRules((rule) => {
    let gap = false;
    let display = false;
    rule.walkDecls((decl) => {
      if (/^(?:gap|row-gap|column-gap)$/.test(decl.prop)) gap = true;
      if (decl.prop === "display" && /flex|grid/.test(decl.value)) display = true;
    });
    // The settings list spaces its items by hand (see its no-flex-gap rule).
    if (gap && !display && !/\.settings-sidebar\s*$/.test(rule.selector) && !/\.seeall-grid\s*$/.test(rule.selector)) {
      missing.push(rule.selector);
    }
  });
  assert.deepEqual(missing, []);
});

test("index.html links the UI layer after the TV layer", async () => {
  const html = await readFile(new URL("../index.html", import.meta.url), "utf8");
  const tv = html.indexOf('href="css/starlit-tv.css"');
  const ui = html.indexOf('href="css/starlit-ui.css"');
  assert.ok(tv > 0 && ui > tv);
});
