import assert from "node:assert/strict";
import test from "node:test";

globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
const { Environment } = await import("../js/platform/environment.js");
const { PlayerController } = await import("../js/core/player/playerController.js");
const { createPlayerScreenMethods30 } =
  await import("../js/ui/screens/player/playerScreenMethods-30-schedule-buffering-spinner-refresh.js");
const { createPlayerScreenMethods31 } =
  await import("../js/ui/screens/player/playerScreenMethods-31-seek-playback-seconds.js");

const { createPlayerScreenMethods29 } =
  await import("../js/ui/screens/player/playerScreenMethods-29-sync-post-play-player-surface.js");

function harness(t, { state = "READY", nativeReady = true, isTizen = true, avplay = true, paused = false } = {}) {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: 10_000 });
  const calls = [];
  const native = {
    state, currentMs: 0, seek: null,
    getState() { return this.state; },
    getCurrentTime() { return this.currentMs; },
    getDuration() { return 3_600_000; },
    seekTo(ms, ok, error) {
      assert.notEqual(this.state, "IDLE");
      assert.equal(this.seek, null, "no overlapping native seeks");
      calls.push(["seek", ms]);
      this.seek = { ok, error };
    },
    play() {
      assert.equal(this.seek, null, "no native play while the seek callback is pending");
      assert.notEqual(this.state, "IDLE");
      calls.push(["play"]);
      this.state = "PLAYING";
    },
    settle(success) {
      const { ok, error } = this.seek;
      this.seek = null;
      // Older firmware's clock remains at zero until playback, even on success.
      if (success) ok();
      else error("PLAYER_ERROR_SEEK_FAILED");
    }
  };
  const noOp = () => {};
  Environment.isTizen = () => isTizen;
  Environment.isWebOS = () => !isTizen;
  Object.assign(PlayerController, {
    isUsingAvPlay: () => avplay,
    getAvPlay: () => native,
    avplaySeekInFlight: false, avplaySeekSettledAt: 0, avplaySeekRequestToken: 21,
    playRequestToken: 4, avplayDeferredSeekMs: 0, avplayStartAfterSeek: null,
    avplayLastSeekOutcome: null, avplayCurrentTimeMs: 0, avplayTimelinePolledAt: 0,
    avplayReady: nativeReady, startupAudioGateActive: true, startupAudioGatePausesNativePlayback: true,
    isPlaying: false, video: { readyState: 4, paused: true },
    applyStartupAudioGateToVideo: noOp,
    pause: () => calls.push(["pause"]),
    retryPendingAvPlayStartupAudioTrackSelection: noOp, reapplyAvPlayPlaybackRate: noOp,
    applyPendingAvPlayAudioTrackSelection: noOp, applyPendingAvPlaySubtitleTrackSelection: noOp,
    applyAvPlayExternalSubtitleDelay: noOp, reapplyTizenAvPlayDisplayRect: noOp,
    startAvPlayTickTimer: noOp, stopAvPlayTickTimer: noOp, syncAvPlayTrackInfo: noOp,
    syncWebOsPlaybackKeepAwake: noOp, emitVideoEvent: noOp
  });
  const screen = {
    ...createPlayerScreenMethods29(), ...createPlayerScreenMethods30(), ...createPlayerScreenMethods31(),
    pendingPlaybackRestore: { timeSeconds: 900, paused, attempts: 0, lastAttemptAt: 0 },
    startupAudioGateActive: true, startupAudioGateAllowsNativePlayback: false,
    startupAudioGateDeadline: 0, startupAudioPreferenceApplied: true, loadingVisible: true,
    hasPresentedPlaybackFrame: false, paused,
    isExternalFrameMode: () => false,
    hasAudioTracksAvailable: () => true,
    getPlaybackDurationSeconds: () => 3600,
    getPlaybackCurrentSeconds: () => PlayerController.avplayCurrentTimeMs / 1000,
    seekPlaybackSeconds(seconds) {
      calls.push(["interactiveSeek", seconds]);
      return true;
    }
  };
  screen.scheduleLoadingCompletionCheck(250);
  return { native, screen, calls };
}

test("an initial Tizen resume does not start playback or publish an optimistic position until its native seek settles", (t) => {
  const { native, screen, calls } = harness(t);
  t.mock.timers.tick(250);
  assert.deepEqual(calls, [["seek", 900_000]]);
  assert.equal(PlayerController.avplayCurrentTimeMs, 0);
  assert.ok(screen.pendingPlaybackRestore);
  t.mock.timers.tick(250);
  assert.deepEqual(calls, [["seek", 900_000]]);
  native.settle(true);
  t.mock.timers.tick(250);
  assert.deepEqual(calls, [["seek", 900_000], ["play"]]);
  assert.equal(screen.pendingPlaybackRestore, null);
  assert.equal(screen.startupAudioGateActive, false);
});

test("a rejected resume starts once from the actual position without an interactive retry", (t) => {
  const { native, screen, calls } = harness(t);
  t.mock.timers.tick(250);
  native.settle(false);
  t.mock.timers.tick(250);
  assert.deepEqual(calls, [["seek", 900_000], ["play"]]);
  assert.equal(screen.startupPlaybackBaselineSeconds, 0);
  assert.equal(screen.pendingPlaybackRestore, null);
});

test("a premature prepared callback in IDLE waits for READY and then resumes", (t) => {
  const { native, screen, calls } = harness(t, { state: "IDLE" });
  t.mock.timers.tick(250);
  assert.deepEqual(calls, []);
  assert.ok(screen.pendingPlaybackRestore);
  native.state = "READY";
  t.mock.timers.tick(250);
  assert.deepEqual(calls, [["seek", 900_000]]);
  native.settle(true);
  t.mock.timers.tick(250);
  assert.deepEqual(calls, [["seek", 900_000], ["play"]]);
});

test("an overdue IDLE restore never calls native APIs in IDLE and starts without seeking once READY", (t) => {
  const { native, screen, calls } = harness(t, { state: "IDLE" });
  t.mock.timers.tick(250);
  t.mock.timers.setTime(15_500);
  assert.deepEqual(calls, []);
  native.state = "READY";
  t.mock.timers.tick(250);
  assert.deepEqual(calls, [["play"]]);
  assert.equal(screen.pendingPlaybackRestore, null);
});

test("slow preparation does not consume the resume-seek budget before a prepared callback", (t) => {
  const { native, screen, calls } = harness(t, { state: "IDLE", nativeReady: false });
  t.mock.timers.tick(250);
  t.mock.timers.setTime(19_000);
  assert.deepEqual(calls, []);
  native.state = "READY";
  PlayerController.avplayReady = true;
  screen.attemptPendingPlaybackRestore();
  assert.deepEqual(calls, [["seek", 900_000]]);
});

test("a stale callback cannot release a replacement restore even for the same position", (t) => {
  const { native, screen, calls } = harness(t);
  t.mock.timers.tick(250);
  const old = native.seek;
  native.seek = null;
  PlayerController.playRequestToken++;
  PlayerController.avplaySeekRequestToken++;
  PlayerController.avplaySeekInFlight = false;
  const newRestore = { timeSeconds: 900, paused: false };
  screen.pendingPlaybackRestore = newRestore;
  t.mock.timers.tick(250);
  assert.deepEqual(calls, [["seek", 900_000], ["seek", 900_000]]);
  old.ok();
  t.mock.timers.tick(250);
  assert.equal(screen.pendingPlaybackRestore, newRestore);
  assert.equal(PlayerController.avplaySeekInFlight, true);
  assert.equal(calls.length, 2);
  native.settle(true);
  t.mock.timers.tick(250);
  assert.deepEqual(calls.at(-1), ["play"]);
});

test("a live channel starts without restoring stale watch progress", (t) => {
  const { screen, calls } = harness(t);
  screen.params = { itemType: "channel" };
  t.mock.timers.tick(250);
  assert.deepEqual(calls, [["play"]]);
  assert.equal(screen.pendingPlaybackRestore, null);
});

test("a lost native seek callback retains the existing timeout and teardown path", (t) => {
  const { native, screen, calls } = harness(t);
  PlayerController.teardownAvPlay = () => {
    calls.push(["teardown"]);
    PlayerController.avplaySeekInFlight = false;
    PlayerController.avplaySeekRequestToken++;
  };
  PlayerController.emitVideoEvent = (name, data) => calls.push([name, data.seekTimeout]);
  t.mock.timers.tick(250);
  const late = native.seek;
  t.mock.timers.tick(30_000);
  assert.deepEqual(calls, [["seek", 900_000], ["teardown"], ["error", true]]);
  assert.ok(screen.pendingPlaybackRestore, "the existing playback error path owns source recovery");
  late.ok();
  assert.deepEqual(calls, [["seek", 900_000], ["teardown"], ["error", true]]);
});

test("non-AVPlay and paused restores still use the interactive engine-specific path", (t) => {
  const { screen, calls } = harness(t, { isTizen: false, avplay: false });
  screen.attemptPendingPlaybackRestore({ force: true });
  assert.deepEqual(calls, [["interactiveSeek", 900]]);
  assert.ok(screen.pendingPlaybackRestore);
  Environment.isTizen = () => true;
  PlayerController.isUsingAvPlay = () => true;
  screen.pendingPlaybackRestore.paused = true;
  screen.attemptPendingPlaybackRestore({ force: true });
  assert.deepEqual(calls, [["interactiveSeek", 900], ["interactiveSeek", 900]]);
  assert.equal(screen.isStartupGateReleaseReady(), false);
});
