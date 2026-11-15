// Opt-in, categorical reliability diagnostics for Tizen and webOS.
// Raw console messages stay in the device-only debug buffer. No analytics SDK,
// device fingerprint, title, URL, account, stack or free-form payload is sent.
import {
  getRecentConsoleDebugEvents,
  subscribeToConsoleDebugEvents
} from "./consoleDebugBuffer.js";
import { getBootTimings } from "./bootTiming.js";

const CONSENT_KEY = "starlit.tv.usageDiagnostics.consent.v1";
const ENDPOINT = "https://starlit-api.rrmtools.uk/gateway/telemetry/events";
const MAX_QUEUE = 12;
const MAX_SESSION_SENDS = 30;
const DEDUPE_MS = 60_000;
const SEND_DELAY_MS = 20_000;
const PAUSED_DELAY_MS = 30_000;
const REQUEST_TIMEOUT_MS = 3500;
const SLOW_START_MS = 8000;
const VERSION_PATTERN = /^\d{1,3}\.\d{1,3}\.\d{1,3}(?:[-+][a-zA-Z0-9.-]{1,24})?$/;
const TV_PLATFORMS = new Set(["tizen", "webos"]);
const EVENTS = new Set([
  "app_open", "playback_failed", "playback_start_slow", "torrent_engine_failed",
  "tv_runtime_error", "tv_unhandled_rejection", "tv_startup_slow",
  "tv_playback_stalled", "tv_plugin_failed", "tv_usb_cache_warning",
  "tv_subtitle_failed"
]);
// Match *only* statically authored console prefixes. Never upload the matched
// message, dynamic arguments or exceptions; unknown messages are discarded.
const CONSOLE_CODES = [
  ["App bootstrap failed", "tv_runtime_error"],
  ["Unhandled runtime error", "tv_runtime_error"],
  ["Unhandled promise rejection", "tv_unhandled_rejection"],
  ["Playback failed", "playback_failed"],
  ["Video error:", "playback_failed"],
  ["[Nuvio AVPlay error detail]", "playback_failed"],
  ["HLS source attach failed", "playback_failed"],
  ["DASH source attach failed", "playback_failed"],
  ["[Nuvio playback] startup stall", "playback_start_slow"],
  ["Playback stalled", "tv_playback_stalled"],
  ["webOS HLS playback stalled", "tv_playback_stalled"],
  ["Tizen AVPlay seek stalled", "tv_playback_stalled"],
  ["PlayerScreen: P2P resolve failed", "torrent_engine_failed"],
  ["WebOsEngineFsResolver: EngineFS create failed", "torrent_engine_failed"],
  ["WebOsEngineFsResolver: resolve failed", "torrent_engine_failed"],
  ["EngineFS local playback failed", "torrent_engine_failed"],
  ["[Nuvio PluginService] lifecycle watchdog recovery failed", "tv_plugin_failed"],
  ["Plugin stream fetch failed", "tv_plugin_failed"],
  ["Plugin code persistent", "tv_plugin_failed"],
  ["Could not apply torrent cache size", "tv_usb_cache_warning"],
  ["Tizen subtitle proxy unavailable", "tv_subtitle_failed"],
  ["Embedded subtitle discovery failed", "tv_subtitle_failed"],
  ["Subtitle fetch failed", "tv_subtitle_failed"],
  ["Subtitle attach failed", "tv_subtitle_failed"]
];

export function classifyTvConsoleEvent(event) {
  if (event?.level !== "warn" && event?.level !== "error") return null;
  const first = event?.args?.[0];
  if (typeof first !== "string") return null;
  for (const [prefix, code] of CONSOLE_CODES) {
    if (first === prefix || first.startsWith(prefix + " ") ||
        first.startsWith(prefix + ":")) return code;
  }
  return null;
}
function newSessionToken(random) {
  if (typeof random !== "function") return "";
  try {
    const bytes = new Uint8Array(16);
    random(bytes);
    return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  } catch (_) { return ""; }
}
function startupTooSlow(marks) {
  const start = marks.find((entry) => entry?.name === "boot-guard");
  const ready = marks.find((entry) => entry?.name === "home-first-paint");
  return Number.isFinite(start?.at) && Number.isFinite(ready?.at) &&
    ready.at - start.at >= SLOW_START_MS;
}

export function createTvUsageDiagnostics({
  // Access localStorage lazily; its getter can throw on restricted TV origins.
  storage = undefined,
  fetcher = globalThis.fetch?.bind(globalThis),
  random = globalThis.crypto?.getRandomValues?.bind(globalThis.crypto),
  getConsoleEvents = getRecentConsoleDebugEvents,
  subscribe = subscribeToConsoleDebugEvents,
  getTimings = getBootTimings,
  getVisibility = () => globalThis.document?.visibilityState || "visible",
  now = Date.now,
  schedule = setTimeout,
  unschedule = clearTimeout,
  createAbort = () => typeof AbortController === "function" ? new AbortController() : null
} = {}) {
  let started = false;
  let enabled = false;
  let platform = "";
  let version = "";
  let getRoute = () => "";
  let queue = [];
  let timer = null;
  let requestTimeoutTimer = null;
  let sending = false;
  let controller = null;
  let sessionToken = "";
  let sent = 0;
  let epoch = 0;
  let unsubscribe = null;
  const lastByCode = new Map();

  function activeStorage() {
    try { return storage === undefined ? globalThis.localStorage : storage; }
    catch (_) { return null; }
  }
  function savedConsent() {
    try { return activeStorage()?.getItem?.(CONSENT_KEY) === "yes"; }
    catch (_) { return false; }
  }
  function canSend() {
    return started && enabled && TV_PLATFORMS.has(platform) &&
      VERSION_PATTERN.test(version) && typeof fetcher === "function";
  }
  function canDrainNow() {
    try {
      const route = String(getRoute() || "").toLowerCase();
      return getVisibility() !== "hidden" && route !== "player" && route !== "stream";
    } catch (_) { return false; }
  }
  function arm(delay = SEND_DELAY_MS) {
    if (!canSend() || sending || timer !== null || !queue.length ||
        sent >= MAX_SESSION_SENDS) return;
    timer = schedule(() => {
      timer = null;
      void drain();
    }, delay);
  }
  function track(code) {
    if (!canSend() || !EVENTS.has(code) || sent >= MAX_SESSION_SENDS) return false;
    const at = now();
    if (at - (lastByCode.get(code) ?? -Infinity) < DEDUPE_MS) return false;
    if (queue.length >= MAX_QUEUE) return false;
    lastByCode.set(code, at);
    queue.push(code);
    arm();
    return true;
  }
  function capture({ includeEarlierSessionEvents = false } = {}) {
    if (unsubscribe || !canSend()) return;
    // Existing console capture is local and already installed. Never ask it
    // to stringify, inspect or transfer any additional console arguments.
    try {
      unsubscribe = subscribe((event) => {
        const code = classifyTvConsoleEvent(event);
        if (code) track(code);
      });
      // Only a consent choice saved before app launch covers its startup
      // warnings. An opt-in made during this session must not retrospectively
      // upload events recorded while the TV had not yet granted consent.
      if (includeEarlierSessionEvents) {
        for (const event of getConsoleEvents().slice(-MAX_QUEUE)) {
          const code = classifyTvConsoleEvent(event);
          if (code) track(code);
        }
      }
    } catch (_) { /* Optional diagnostics cannot affect the TV startup flow. */ }
  }
  function stopCapture() {
    try { unsubscribe?.(); } catch (_) {}
    unsubscribe = null;
  }
  async function drain() {
    if (!canSend() || sending || !queue.length) return;
    if (!canDrainNow()) {
      arm(PAUSED_DELAY_MS);
      return;
    }
    if (!sessionToken) sessionToken = newSessionToken(random);
    // Fail closed on runtimes without strong randomness. Math.random() is
    // deliberately not an acceptable substitute for the gateway token.
    if (!sessionToken) {
      queue = [];
      return;
    }
    const event = queue.shift();
    sending = true;
    sent++;
    const run = epoch;
    controller = createAbort();
    const abortTimer = controller ? schedule(() => controller?.abort(), REQUEST_TIMEOUT_MS) : null;
    requestTimeoutTimer = abortTimer;
    try {
      await fetcher(ENDPOINT, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ installationId: sessionToken, platform, version, event }),
        cache: "no-store",
        redirect: "error",
        ...(controller ? { signal: controller.signal } : {})
      });
    } catch (_) {
      // No retry, no disk queue, no logging. Diagnostics cannot affect playback.
    } finally {
      if (abortTimer !== null) unschedule(abortTimer);
      if (requestTimeoutTimer === abortTimer) requestTimeoutTimer = null;
      if (run === epoch) {
        sending = false;
        controller = null;
        arm();
      }
    }
  }
  function clear() {
    epoch++;
    if (timer !== null) unschedule(timer);
    timer = null;
    if (requestTimeoutTimer !== null) unschedule(requestTimeoutTimer);
    requestTimeoutTimer = null;
    controller?.abort?.();
    controller = null;
    sending = false;
    queue = [];
    lastByCode.clear();
    sessionToken = "";
    sent = 0;
    stopCapture();
  }
  return {
    isEnabled() { return started ? enabled : savedConsent(); },
    start({ platform: name, version: appVersion, route = () => "" } = {}) {
      if (started) return;
      platform = String(name || "").toLowerCase();
      version = String(appVersion || "");
      getRoute = typeof route === "function" ? route : () => "";
      if (!TV_PLATFORMS.has(platform)) return;
      started = true;
      enabled = savedConsent();
      if (!enabled) return;
      capture({ includeEarlierSessionEvents: true });
      track("app_open");
      try { if (startupTooSlow(getTimings())) track("tv_startup_slow"); } catch (_) {}
    },
    setEnabled(value) {
      if (!value) {
        enabled = false;
        clear();
        try { activeStorage()?.setItem?.(CONSENT_KEY, "no"); } catch (_) {}
        return false;
      }
      // Only an explicit, durably recorded TV preference can enable sends.
      if (!started || !TV_PLATFORMS.has(platform) || !VERSION_PATTERN.test(version)) return false;
      try {
        const writableStorage = activeStorage();
        if (typeof writableStorage?.setItem !== "function") return false;
        writableStorage.setItem(CONSENT_KEY, "yes");
        if (!savedConsent()) return false;
      } catch (_) { return false; }
      enabled = true;
      capture();
      track("app_open");
      try { if (startupTooSlow(getTimings())) track("tv_startup_slow"); } catch (_) {}
      return true;
    },
    track,
    stop() {
      enabled = false;
      clear();
      started = false;
    },
    getPendingCount() { return queue.length; }
  };
}
export const TvUsageDiagnostics = createTvUsageDiagnostics();
