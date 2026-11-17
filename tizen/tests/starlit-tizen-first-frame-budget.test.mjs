import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { TIZEN_FIRST_FRAME_DEADLINE_MS, tizenFirstFrameRemainingMs, canContinueTizenFirstFrameWait }
  from "../js/core/player/tizenFirstFrameBudget.js";

test("Tizen first-frame clock does not reset when transport advances", () => {
  const first = 1_000;
  assert.equal(tizenFirstFrameRemainingMs(first, first), TIZEN_FIRST_FRAME_DEADLINE_MS);
  assert.equal(tizenFirstFrameRemainingMs(first, first + 30_000), TIZEN_FIRST_FRAME_DEADLINE_MS - 30_000);
  assert.equal(canContinueTizenFirstFrameWait(first, first + TIZEN_FIRST_FRAME_DEADLINE_MS - 1), true);
  assert.equal(canContinueTizenFirstFrameWait(first, first + TIZEN_FIRST_FRAME_DEADLINE_MS), false);
  assert.equal(tizenFirstFrameRemainingMs(first, first + TIZEN_FIRST_FRAME_DEADLINE_MS + 10_000), 0);
});
test("Unstarted clock is neutral and clock skew cannot increase the deadline", () => {
  assert.equal(tizenFirstFrameRemainingMs(0), null);
  assert.equal(canContinueTizenFirstFrameWait(0), true);
  assert.equal(tizenFirstFrameRemainingMs(1000, 950), TIZEN_FIRST_FRAME_DEADLINE_MS);
  assert.equal(tizenFirstFrameRemainingMs(1000, NaN), 0);
});

test("expired native first-frame clock cannot loop on readyState or request another fallback", async () => {
  const source = readFileSync(new URL("../js/ui/screens/player/playerScreenMethods-38-schedule-playback-stall-guard.js", import.meta.url), "utf8")
    .replace(/^import [^;]+;\r?\n/gm, "")
    .replace(/^export /gm, "");
  let activeCallback = null;
  let scheduledMs = 0;
  let errors = 0;
  let retries = 0;
  const ctx = {
    internals: {
      PlayerController: {
        video: { networkState: 2, readyState: 3 },
        getPlaybackReadyState: () => 3,
        getLastPlaybackErrorCode: () => 0,
        getLastHlsErrorDiagnostic: () => null,
        getAlternativePlaybackEngine: () => { retries++; return "hls.js"; },
        playbackEngine: "avplay"
      },
      Environment: { isTizen: () => true, isWebOS: () => false },
      isRecoverableHlsFragmentTimeout: () => false,
      isExpiredStreamUrl: () => false,
      isTerminalHlsHttpStatus: () => false,
      WEBOS_NATIVE_STARTUP_LOADING_EXTENSION_MS: 10000,
      WEBOS_HLS_PLAYBACK_RECOVERY_MAX_ATTEMPTS: 2,
      logEngineFsDebug() {},
      t: () => ""
    },
    canContinueTizenFirstFrameWait: () => false,
    tizenFirstFrameRemainingMs: () => 0,
    setTimeout(fn, ms) { activeCallback = fn; scheduledMs = ms; return 1; },
    console
  };
  const methods = vm.runInNewContext(source + "\ncreatePlayerScreenMethods38()", ctx);
  const screen = Object.assign(methods, {
    isExternalFrameMode: () => false,
    activePlaybackUrl: "http://127.0.0.1:2710/media",
    hasPresentedPlaybackFrame: false,
    tizenFirstFrameStartedAt: 1000,
    loadingVisible: true,
    currentEngineFsStream: null,
    clearPlaybackStallGuard() { this.playbackStallTimer = null; },
    getPlaybackStallTimeoutMs: () => 60000,
    markPlaybackPresentedAfterAdvance: () => false,
    releaseStartupAudioGate() {},
    markPlaybackSourceFailed() {},
    getStreamCandidateByUrl: () => null,
    getCurrentStreamCandidate: () => null,
    getStartupErrorMessage: () => "Playback could not start",
    showStartupError() { errors++; },
    tryServerPlaybackFallback() { retries++; return true; },
    retryTizenAvPlayThroughLocalProxy() { retries++; return true; }
  });
  screen.schedulePlaybackStallGuard();
  assert.equal(scheduledMs, 1, "deadline cannot be postponed even when readyState is high");
  await activeCallback();
  assert.equal(errors, 1, "a terminal startup failure must be displayed");
  assert.equal(retries, 0, "expired budget cannot retry engines indefinitely");
});

test("a missing Tizen torrent is recreated after a bounded same-URL retry", () => {
  const source = readFileSync(new URL("../js/ui/screens/player/playerScreenMethods-37-should-defer-engine-fs-startup-stall.js", import.meta.url), "utf8")
    .replace(/^import [^;]+;\r?\n/gm, "").replace(/^export /gm, "");
  const callbacks = [];
  const playbackCalls = [];
  const ctx = {
    internals: {
      PlayerController: {},
      Environment: { isTizen: () => true, isWebOS: () => false },
      WEBOS_HLS_REBUFFER_STALL_TIMEOUT_MS: 15000,
      TIZEN_NATIVE_HLS_STARTUP_STALL_TIMEOUT_MS: 12000,
      logEngineFsDebug() {}
    },
    canContinueTizenFirstFrameWait: () => true,
    setTimeout(fn) { callbacks.push(fn); return callbacks.length; },
    clearTimeout() {}
  };
  const methods = vm.runInNewContext(source + "\ncreatePlayerScreenMethods37()", ctx);
  const url = `http://127.0.0.1:2710/${"a".repeat(40)}/0?ticket=x`;
  const sourceCandidate = { id: "test-source", url };
  const screen = Object.assign(methods, {
    currentEngineFsStream: { kind: "tizen-streaming-server", infoHash: "a".repeat(40), fileIdx: 0 },
    activePlaybackUrl: url,
    hasPresentedPlaybackFrame: false,
    tizenFirstFrameStartedAt: 1000,
    getEngineFsStallSnapshot: () => null,
    getCurrentStreamCandidate: () => sourceCandidate,
    getStreamCandidateByUrl: () => sourceCandidate,
    dismissPauseOverlay() {}, updateLoadingVisibility() {},
    updateMediaSessionPlaybackState() {}, setControlsVisible() {},
    schedulePlaybackStallGuard() {},
    playStreamByUrl(target) { playbackCalls.push({ target }); },
    playStreamCandidate(candidate, options) { playbackCalls.push({ candidate, options }); }
  });
  assert.equal(screen.scheduleEngineFsStartupRetry({ mediaErrorCode: 2, stats: null }), true);
  callbacks.shift()();
  assert.equal(playbackCalls[0].target, url, "the first transient failure should reuse the healthy swarm");
  assert.equal(screen.scheduleEngineFsStartupRetry({ mediaErrorCode: 2, stats: null }), true);
  callbacks.shift()();
  assert.equal(playbackCalls[1].candidate, sourceCandidate);
  assert.equal(playbackCalls[1].options.forceEngineFsResolve, true,
    "a missing Tizen torrent needs a newly issued local playback ticket");
});

test("Tizen startup watchdog recovers from a missing torrent service before showing a fatal error", async () => {
  const source = readFileSync(new URL("../js/ui/screens/player/playerScreenMethods-38-schedule-playback-stall-guard.js", import.meta.url), "utf8")
    .replace(/^import [^;]+;\r?\n/gm, "").replace(/^export /gm, "");
  let callback, retries = 0, fatal = 0;
  const ctx = {
    internals: {
      PlayerController: {
        video: { networkState: 0, readyState: 0 },
        playbackEngine: "avplay",
        getPlaybackReadyState: () => 0
      },
      Environment: { isTizen: () => true, isWebOS: () => false },
      logEngineFsDebug() {}
    },
    canContinueTizenFirstFrameWait: () => true,
    tizenFirstFrameRemainingMs: () => 90000,
    setTimeout(fn) { callback = fn; return 1; },
    clearTimeout() {}, console
  };
  const methods = vm.runInNewContext(source + "\ncreatePlayerScreenMethods38()", ctx);
  const screen = Object.assign(methods, {
    isExternalFrameMode: () => false,
    currentEngineFsStream: { kind: "tizen-streaming-server" },
    loadingVisible: true,
    markPlaybackPresentedAfterAdvance: () => false,
    isEngineFsStartupReady: () => false,
    activePlaybackUrl: "http://127.0.0.1:2710/media",
    hasPresentedPlaybackFrame: false,
    tizenFirstFrameStartedAt: 1000,
    getPlaybackStallTimeoutMs: () => 60000,
    clearPlaybackStallGuard() {},
    fetchCurrentEngineFsStats: async () => null,
    shouldRetryEngineFsStartupError: () => true,
    scheduleEngineFsStartupRetry() { retries++; return true; },
    showStartupError() { fatal++; }
  });
  screen.schedulePlaybackStallGuard();
  await callback();
  assert.equal(retries, 1);
  assert.equal(fatal, 0);
});