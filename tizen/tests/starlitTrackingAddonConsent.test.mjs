import assert from "node:assert/strict";
import test from "node:test";

const store = new Map();
globalThis.localStorage = {
  getItem: (key) => store.get(key) ?? null,
  setItem: (key, value) => store.set(key, String(value)),
  removeItem: (key) => store.delete(key),
  clear: () => store.clear(),
};
const delivered = [];
globalThis.fetch = async (url) => {
  delivered.push(String(url));
  return { ok: true };
};

const { addonRepository } = await import("../js/data/repository/addonRepository.js");
const { ProfileManager } = await import("../js/core/profile/profileManager.js");
const {
  ADDON_PLAYER_RESOURCE,
  AddonEventDispatcher,
  AddonPlayerAction,
  AddonPlayerScrobbleService,
  AddonLibraryAction,
  buildAddonEventUrl,
  addonSupportsResource,
  isTrackingAddon,
  libraryVideoIdChunks,
} = await import("../js/data/repository/addonEventDispatcher.js");
const originalProfileId = ProfileManager.getActiveProfileId;
const originalFetch = globalThis.fetch;
const provider = {
  id: "tracker",
  baseUrl: "https://tracker.example/token?apikey=x",
  resources: [{ name: "player" }, { name: "library" }],
};

test.afterEach(() => {
  ProfileManager.getActiveProfileId = originalProfileId;
  delivered.length = 0;
  AddonPlayerScrobbleService.cancel();
  globalThis.fetch = originalFetch;
  store.clear();
});

test("tracking is off by default and is isolated by active profile", () => {
  ProfileManager.getActiveProfileId = () => "1";
  assert.equal(addonRepository.hasTrackingConsent(provider.baseUrl), false);
  addonRepository.grantTrackingConsent(provider.baseUrl);
  assert.equal(addonRepository.hasTrackingConsent(provider.baseUrl), true);
  ProfileManager.getActiveProfileId = () => "2";
  assert.equal(addonRepository.hasTrackingConsent(provider.baseUrl), false);
  ProfileManager.getActiveProfileId = () => "1";
  addonRepository.revokeTrackingConsent(provider.baseUrl);
  assert.equal(addonRepository.hasTrackingConsent(provider.baseUrl), false);
});

test("tracking URL encoding retains tokens and splits episode lists safely", () => {
  const url = buildAddonEventUrl(
    provider.baseUrl, "library", "series", "kitsu:123",
    [["action", "watched"], ["videoId", "kitsu:123:1:1,kitsu:123:1:2"]],
  );
  assert.equal(url, "https://tracker.example/token/library/series/kitsu%3A123/action=watched&videoId=kitsu%3A123%3A1%3A1%2Ckitsu%3A123%3A1%3A2.json?apikey=x");
  assert.deepEqual(libraryVideoIdChunks(Array.from({ length: 201 }, (_, index) => "e" + index)).map(x => x.length), [100, 100, 1]);
  assert.equal(isTrackingAddon(provider), true);
  assert.equal(addonSupportsResource({ resources: [{ name: "library", idPrefixes: ["tt"] }] }, "library", "series", "kitsu:10"), false);
});

test("the approved addon alone receives playback and manual library actions", async () => {
  ProfileManager.getActiveProfileId = () => "1";
  assert.equal(AddonEventDispatcher.hasEnabledAddonFor("player", () => [provider]), false);
  assert.equal(AddonEventDispatcher.player({ type: "series", videoId: "tt1:1:1" }, AddonPlayerAction.START, 120, 3000, () => [provider]), 0);
  addonRepository.grantTrackingConsent(provider.baseUrl);
  assert.equal(AddonEventDispatcher.hasEnabledAddonFor("player", () => [provider]), true);
  assert.equal(AddonEventDispatcher.player({ type: "series", videoId: "tt1:1:1" }, AddonPlayerAction.START, 120, 3000, () => [provider]), 1);
  assert.equal(AddonEventDispatcher.library("movie", "tt2", AddonLibraryAction.LIBRARY_ADD, () => [provider]), 1);
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.equal(delivered.length, 2);
  assert.match(delivered[0], /\/player\/series\/tt1%3A1%3A1\/action=start&currentTime=120&duration=3000/);
  assert.match(delivered[1], /\/library\/movie\/tt2\/action=libraryAdd/);
  addonRepository.revokeTrackingConsent(provider.baseUrl);
  assert.equal(AddonEventDispatcher.library("movie", "tt2", AddonLibraryAction.UNWATCHED, () => [provider]), 0);
});

test("queued tracking is dropped after switching profiles even when the next profile consents", async () => {
  let profileId = "1";
  ProfileManager.getActiveProfileId = () => profileId;
  addonRepository.grantTrackingConsent(provider.baseUrl);
  let releaseFirst;
  let signalFirst;
  const firstStarted = new Promise((resolve) => { signalFirst = resolve; });
  const holdFirst = new Promise((resolve) => { releaseFirst = resolve; });
  globalThis.fetch = async (url) => {
    delivered.push(String(url));
    if (delivered.length === 1) {
      signalFirst();
      await holdFirst;
    }
    return { ok: true };
  };
  try {
    assert.equal(AddonEventDispatcher.library("movie", "first", AddonLibraryAction.WATCHED, () => [provider]), 1);
    await firstStarted;
    assert.equal(AddonEventDispatcher.library("movie", "second", AddonLibraryAction.WATCHED, () => [provider]), 1);
    profileId = "2";
    addonRepository.grantTrackingConsent(provider.baseUrl);
  } finally {
    releaseFirst?.();
  }
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.equal(delivered.length, 1);
  assert.match(delivered[0], /first/);
});

test("start, pause, seek resend, and stop keep the playback target", () => {
  const previous = AddonEventDispatcher.player;
  const out = [];
  AddonEventDispatcher.player = (target, action) => { out.push(target.videoId + ":" + action); return 1; };
  try {
    AddonPlayerScrobbleService.cancel();
    const context = { contentType: "series", videoId: "tt123:1:1", positionMs: 0, durationMs: 100 };
    AddonPlayerScrobbleService.pause(context);
    AddonPlayerScrobbleService.start(context);
    AddonPlayerScrobbleService.start(context);
    AddonPlayerScrobbleService.pause(context);
    AddonPlayerScrobbleService.stop(context);
    AddonPlayerScrobbleService.stop(context);
    assert.deepEqual(out, ["tt123:1:1:start", "tt123:1:1:pause", "tt123:1:1:stop"]);
  } finally {
    AddonEventDispatcher.player = previous;
  }
});
