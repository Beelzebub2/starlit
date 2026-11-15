import assert from "node:assert/strict";
import test from "node:test";
import { classifyTvConsoleEvent, createTvUsageDiagnostics } from "./tvUsageDiagnostics.js";

function fixture({
  consent = null,
  platform = "tizen",
  route = "home",
  visibility = "visible",
  initialEvents = [],
  timings = [],
  random = (buffer) => buffer.fill(7),
  fetcher = null,
  saveFails = false
} = {}) {
  const values = new Map();
  if (consent) values.set("starlit.tv.usageDiagnostics.consent.v1", consent);
  const timers = new Map();
  const requests = [];
  let currentTime = 1000;
  let nextTimer = 0;
  let currentRoute = route;
  let currentVisibility = visibility;
  let subscriber = null;
  let subscriptions = 0;
  const diagnostics = createTvUsageDiagnostics({
    storage: {
      getItem: (key) => values.get(key) ?? null,
      setItem: (key, value) => {
        if (saveFails) throw new Error("Disk write denied");
        values.set(key, value);
      }
    },
    fetcher: fetcher || (async (url, options) => {
      requests.push({ url, options });
      return { ok: true };
    }),
    random,
    getConsoleEvents: () => initialEvents,
    subscribe: (handler) => {
      subscriptions++;
      subscriber = handler;
      return () => { subscriber = null; };
    },
    getTimings: () => timings,
    getVisibility: () => currentVisibility,
    now: () => currentTime,
    schedule: (callback, delay) => {
      const id = ++nextTimer;
      timers.set(id, { callback, delay });
      return id;
    },
    unschedule: (id) => timers.delete(id)
  });
  const start = () => diagnostics.start({ platform, version: "1.27.2", route: () => currentRoute });
  async function tick() {
    const next = timers.entries().next().value;
    assert(next, "expected a scheduled, deferred telemetry send");
    timers.delete(next[0]);
    currentTime += next[1].delay;
    next[1].callback();
    await new Promise((resolve) => setImmediate(resolve));
  }
  return {
    diagnostics, start, tick, requests, values, timers,
    setRoute(value) { currentRoute = value; },
    setVisibility(value) { currentVisibility = value; },
    emit(event) { subscriber?.(event); },
    advance(value) { currentTime += value; },
    get subscriptions() { return subscriptions; }
  };
}

test("TV diagnostics is inert without explicit consent", async () => {
  const f = fixture();
  f.start();
  assert.equal(f.diagnostics.isEnabled(), false);
  assert.equal(f.diagnostics.track("tv_runtime_error"), false);
  assert.equal(f.subscriptions, 0);
  assert.equal(f.requests.length, 0);
  assert.equal(f.timers.size, 0);
  assert.equal(f.diagnostics.getPendingCount(), 0);
});

test("explicit opt-in sends only four fixed fields and a session-only token", async () => {
  const f = fixture();
  f.start();
  assert.equal(f.diagnostics.setEnabled(true), true);
  assert.equal(f.subscriptions, 1);
  assert.equal(f.diagnostics.getPendingCount(), 1);
  await f.tick();
  assert.equal(f.requests.length, 1);
  const { url, options } = f.requests[0];
  assert.equal(url, "https://starlit-api.rrmtools.uk/gateway/telemetry/events");
  assert.equal(options.method, "POST");
  assert.equal(options.redirect, "error");
  const body = JSON.parse(options.body);
  assert.deepEqual(Object.keys(body).sort(), ["event", "installationId", "platform", "version"]);
  assert.equal(body.platform, "tizen");
  assert.equal(body.event, "app_open");
  assert.equal(body.version, "1.27.2");
  assert.match(body.installationId, /^[0-9a-f]{32}$/);
  assert.deepEqual([...f.values.keys()], ["starlit.tv.usageDiagnostics.consent.v1"],
    "the session token must never be persisted");
});

test("previously opted-in webOS loads existing device-local consent", async () => {
  const f = fixture({ consent: "yes", platform: "webos" });
  f.start();
  assert.equal(f.diagnostics.isEnabled(), true);
  await f.tick();
  assert.equal(JSON.parse(f.requests[0].options.body).platform, "webos");
});

test("diagnostics never sends during playback or while the TV app is hidden", async () => {
  const f = fixture({ route: "player", consent: "yes" });
  f.start();
  await f.tick();
  assert.equal(f.requests.length, 0);
  f.setRoute("home");
  f.setVisibility("hidden");
  await f.tick();
  assert.equal(f.requests.length, 0);
  f.setVisibility("visible");
  await f.tick();
  assert.equal(f.requests.length, 1);
});

test("classification never includes dynamic messages, arguments, tokens or paths", async () => {
  const cases = [
    ["Playback failed", "playback_failed"],
    ["[Nuvio playback] startup stall", "playback_start_slow"],
    ["PlayerScreen: P2P resolve failed", "torrent_engine_failed"],
    ["Tizen AVPlay seek stalled after settling; retrying the seek once", "tv_playback_stalled"],
    ["Plugin stream fetch failed", "tv_plugin_failed"],
    ["Tizen subtitle proxy unavailable", "tv_subtitle_failed"],
    ["Could not apply torrent cache size", "tv_usb_cache_warning"],
    ["Unhandled runtime error", "tv_runtime_error"],
    ["Unhandled promise rejection", "tv_unhandled_rejection"]
  ];
  for (const [message, expected] of cases) {
    assert.equal(classifyTvConsoleEvent({
      level: "warn",
      args: [message, { token: "SECRET", url: "https://private.example/path" }]
    }), expected);
  }
  assert.equal(classifyTvConsoleEvent({ level: "warn", args: ["user@domain.example"] }), null);
  assert.equal(classifyTvConsoleEvent({ level: "info", args: ["Playback failed"] }), null);
  assert.equal(classifyTvConsoleEvent({ level: "error", args: ["unrecognized warning", "SECRET"] }), null);
  const f = fixture({ consent: "yes" });
  f.start();
  f.emit({ level: "warn", args: ["Playback failed", "token://SECRET"] });
  await f.tick();
  await f.tick();
  assert.equal(JSON.parse(f.requests[1].options.body).event, "playback_failed");
  assert(!f.requests[1].options.body.includes("SECRET"));
});

test("event burst is deduplicated, with a bounded memory queue and paced sends", async () => {
  const f = fixture({ consent: "yes" });
  f.start();
  for (let n = 0; n < 1000; n++) {
    f.emit({ level: "warn", args: ["Playback failed", "untrusted data " + n] });
  }
  assert.equal(f.diagnostics.getPendingCount(), 2, "app_open + one failure, not 1000 events");
  await f.tick();
  assert.equal(f.requests.length, 1);
  await f.tick();
  assert.equal(f.requests.length, 2);
  f.advance(60_000);
  f.emit({ level: "warn", args: ["Playback failed", "another failure"] });
  await f.tick();
  assert.equal(f.requests.length, 3);
});

test("revoking consent drops unsent events and aborts an active request", async () => {
  let aborted = false;
  const f = fixture({
    consent: "yes",
    fetcher: async (_url, options) => {
      return new Promise((_resolve, reject) => {
        options.signal?.addEventListener?.("abort", () => {
          aborted = true;
          reject(new Error("aborted"));
        });
      });
    }
  });
  f.start();
  await f.tick();
  assert.equal(f.diagnostics.setEnabled(false), false);
  assert.equal(aborted, true);
  assert.equal(f.diagnostics.getPendingCount(), 0);
  assert.equal(f.timers.size, 0);
  assert.equal(f.values.get("starlit.tv.usageDiagnostics.consent.v1"), "no");
  assert.equal(f.diagnostics.track("playback_failed"), false);
});

test("no strong random source, storage access, unsupported platform or malformed version fails closed", async () => {
  const noEntropy = fixture({ consent: "yes", random: null });
  noEntropy.start();
  await noEntropy.tick();
  assert.equal(noEntropy.requests.length, 0);
  assert.equal(noEntropy.diagnostics.getPendingCount(), 0);
  const denied = fixture({ saveFails: true });
  denied.start();
  assert.equal(denied.diagnostics.setEnabled(true), false);
  assert.equal(denied.diagnostics.isEnabled(), false);
  const unsupported = fixture({ platform: "browser", consent: "yes" });
  unsupported.start();
  assert.equal(unsupported.diagnostics.setEnabled(true), false);
  assert.equal(unsupported.timers.size, 0);
});

test("startup threshold is reported only as a category, never an exact timeline", async () => {
  const f = fixture({ consent: "yes", timings: [
    { name: "boot-guard", at: 100 }, { name: "home-first-paint", at: 8500 }
  ] });
  f.start();
  await f.tick();
  await f.tick();
  const payloads = f.requests.map(({ options }) => JSON.parse(options.body));
  assert.deepEqual(payloads.map(({ event }) => event), ["app_open", "tv_startup_slow"]);
  assert(payloads.every((body) => !("duration" in body) && !("timeline" in body)));
});

test("captured pre-first-paint warnings are classified only after consent", async () => {
  const f = fixture({
    consent: "yes",
    initialEvents: [
      { level: "error", args: ["App bootstrap failed", { url: "private" }] },
      { level: "warn", args: ["Unknown: signed URL here"] }
    ]
  });
  f.start();
  await f.tick();
  await f.tick();
  assert.deepEqual(f.requests.map(({ options }) => JSON.parse(options.body).event),
    ["tv_runtime_error", "app_open"]);
});
test("turning on diagnostics does not upload earlier warnings from a non-consenting session", async () => {
  const f = fixture({
    initialEvents: [{ level: "error", args: ["App bootstrap failed", "personal diagnostic text"] }]
  });
  f.start();
  assert.equal(f.diagnostics.setEnabled(true), true);
  await f.tick();
  assert.deepEqual(f.requests.map(({ options }) => JSON.parse(options.body).event),
    ["app_open"], "only events generated after opt-in may be uploaded");
});
