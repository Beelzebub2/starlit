import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import {
  connectLocalControl,
  clearLocalControlTokens,
  trustedEngineFsControlHeaders
} from "../js/platform/tizen/starlitControlBridge.js";

const storage = new Map();
globalThis.localStorage = {
  getItem(key) { return storage.get(key) || null; },
  setItem(key, value) { storage.set(key, String(value)); },
  removeItem(key) { storage.delete(key); }
};
const { createTorrent, buildPeerSearchSources, TizenStreamingServerResolver } =
  await import("../js/core/p2p/tizenStreamingServerResolver.js");
const { Platform } = await import("../js/platform/index.js");
const { tizenAdapter } = await import("../js/platform/adapters/tizenAdapter.js");
const TOKEN = "b".repeat(64);
const HASH = "a".repeat(40);
const SERVICE_ID = "StarlitTV1.EngineFsService";
const BASE = "http://127.0.0.1:2710";

function trustedTizenRuntime() {
  let receive;
  return {
    crypto: { getRandomValues(bytes) { bytes.fill(42); return bytes; } },
    tizen: {
      messageport: {
        requestTrustedLocalMessagePort() {
          return {
            addMessagePortListener(listener) { receive = listener; return 1; },
            removeMessagePortListener() {}
          };
        },
        requestTrustedRemoteMessagePort(id) {
          assert.equal(id, SERVICE_ID);
          return {
            isTrusted: true,
            sendMessage(message) {
              queueMicrotask(() => receive(
                [{ key: "nonce", value: message[0].value }, { key: "token", value: TOKEN }],
                { isTrusted: true, appId: SERVICE_ID }
              ));
            }
          };
        }
      }
    }
  };
}

test("trusted TV bridge token is bound to the correct local EngineFS port", async () => {
  clearLocalControlTokens();
  await connectLocalControl(SERVICE_ID, "2710", trustedTizenRuntime());
  const headers = trustedEngineFsControlHeaders(BASE, { "content-type": "application/json" });
  assert.equal(headers["X-Starlit-Token"], TOKEN);
  assert.equal(headers["X-Starlit-Local"], "1");
  assert.equal(headers["content-type"], "application/json");
  for (const base of ["https://127.0.0.1:2710", "http://192.168.1.20:2710", "http://127.0.0.1:9999"]) {
    assert.throws(() => trustedEngineFsControlHeaders(base), /Untrusted/);
  }
  clearLocalControlTokens();
  assert.throws(() => trustedEngineFsControlHeaders(BASE), /authorization unavailable/);
});

test("large add-on tracker lists are bounded before Tizen service create", async () => {
  clearLocalControlTokens();
  await connectLocalControl(SERVICE_ID, "2710", trustedTizenRuntime());
  const trackers = [
    "invalid entry",
    "http://user:pass@tracker.example/announce",
    ...Array.from({ length: 24 }, (_, i) => `https://tracker-${i}.example/announce`)
  ];
  const sources = buildPeerSearchSources(HASH, trackers);
  assert.equal(sources.length, 9);
  assert.equal(sources[0], `dht:${HASH}`);
  assert.deepEqual(sources.slice(1), trackers.slice(2, 10).map(t => `tracker:${t}`));
  assert.deepEqual(buildPeerSearchSources(HASH, ["https://a.example/announce", "https://a.example/announce"]), [
    `dht:${HASH}`,
    "tracker:https://a.example/announce"
  ]);
  const originalFetch = globalThis.fetch;
  const requests = [];
  try {
    globalThis.fetch = async (url, options) => {
      requests.push({ url, options });
      return { ok: true, status: 200, json: async () => ({ files: [{ name: "synthetic.mp4" }] }) };
    };
    await createTorrent(BASE, { infoHash: HASH, fileIdx: 0, trackerSources: trackers });
    assert.equal(requests.length, 1);
    assert.equal(requests[0].options.headers["X-Starlit-Token"], TOKEN);
    const body = JSON.parse(requests[0].options.body);
    assert.equal(body.peerSearch.sources.length, 9);
    assert.deepEqual(body.peerSearch.sources, sources);
  } finally {
    globalThis.fetch = originalFetch;
    clearLocalControlTokens();
  }
});

test("remove requires the same trusted token and rejects other loopback ports", async () => {
  clearLocalControlTokens();
  await connectLocalControl(SERVICE_ID, "2710", trustedTizenRuntime());
  const originalFetch = globalThis.fetch;
  const previousPlatform = Platform.current;
  const requests = [];
  try {
    Platform.current = tizenAdapter;
    globalThis.fetch = async (url, options) => {
      requests.push({ url, options });
      return { ok: true, status: 200 };
    };
    const result = await TizenStreamingServerResolver.remove(HASH);
    assert.equal(result.status, "success");
    assert.equal(requests[0].options.headers["X-Starlit-Token"], TOKEN);
    assert.equal(requests[0].options.headers["X-Starlit-Local"], "1");
    requests.length = 0;
    const fallback = await TizenStreamingServerResolver.remove(HASH, { baseUrl: "http://127.0.0.1:9999" });
    assert.equal(fallback.status, "success");
    assert.equal(requests.length, 1, "untrusted ports must be skipped");
    assert.equal(new URL(requests[0].url).port, "2710");
  } finally {
    Platform.current = previousPlatform;
    globalThis.fetch = originalFetch;
    clearLocalControlTokens();
  }
});

test("a stale local playback ticket must be re-resolved after the torrent service restarts", async () => {
  const { TizenCapabilities } = await import("../js/platform/tizen/tizenCapabilities.js");
  const { TizenEngineFsService, tizenServiceStartupWaitMs } = await import("../js/platform/tizen/tizenEngineFsService.js");
  assert.equal(tizenServiceStartupWaitMs("p2p"), 25_000, "Cold P2P boot may take longer than a warm service probe");
  assert.equal(tizenServiceStartupWaitMs("playback-proxy"), 12_000);
  const previousPlatform = Platform.current;
  const previousCanUseP2p = TizenCapabilities.canUseP2p;
  const previousEnsureStarted = TizenEngineFsService.ensureStarted;
  let attemptedLocalResolve = 0;
  try {
    Platform.current = tizenAdapter;
    TizenCapabilities.canUseP2p = () => true;
    TizenEngineFsService.ensureStarted = async () => {
      attemptedLocalResolve++;
      return { status: "error", detail: "Service is restarting" };
    };
    const priorPlayback = { infoHash: HASH, fileIdx: 0,
      url: `${BASE}/${HASH}/0?ticket=${"c".repeat(64)}` };
    const local = await TizenStreamingServerResolver.resolve(priorPlayback);
    assert.equal(local.status, "error", "A stale ticket must not be treated as a direct playable URL");
    assert.equal(attemptedLocalResolve, 1);
    const cancelled = await TizenStreamingServerResolver.resolve({ infoHash: HASH },
      { isCurrentRequest: () => false });
    assert.equal(cancelled.status, "cancelled");
    assert.equal(attemptedLocalResolve, 1, "Stale source requests must not restart the service");
    const external = await TizenStreamingServerResolver.resolve({ infoHash: HASH,
      url: "https://cdn.example/video.mp4" });
    assert.equal(external.status, "success", "Ordinary HTTP sources must still bypass torrent creation");
    assert.equal(attemptedLocalResolve, 1);
  } finally {
    Platform.current = previousPlatform;
    TizenCapabilities.canUseP2p = previousCanUseP2p;
    TizenEngineFsService.ensureStarted = previousEnsureStarted;
  }
});
test("player mount must not bypass Tizen torrent resolution using a cached engine ticket", async () => {
  const { isLocalTizenTorrentPlaybackUrl } = await import("../js/core/p2p/tizenTorrentPlaybackReadiness.js");
  const source = readFileSync(new URL("../js/ui/screens/player/playerScreenHelpers-10-format-subtitle-track-display.js", import.meta.url), "utf8");
  const start = source.indexOf("export function isMagnetUrl(");
  const end = source.indexOf("export function streamDebridIdentity(", start);
  assert.ok(start >= 0 && end > start);
  const helpers = source.slice(start, end).replace(/^export /gm, "");
  const oldTicket = `${BASE}/${HASH}/0?ticket=${"d".repeat(64)}`;
  const tizenHelpers = vm.runInNewContext(helpers + "\n({ directPlaybackUrl, streamDirectPlaybackUrl })", {
    Environment: { isTizen: () => true }, isLocalTizenTorrentPlaybackUrl
  });
  assert.equal(tizenHelpers.directPlaybackUrl(oldTicket), "",
    "a cached engine ticket must never be used as a first-play direct media URL");
  assert.equal(tizenHelpers.streamDirectPlaybackUrl({ url: oldTicket }), "");
  assert.equal(tizenHelpers.streamDirectPlaybackUrl({ url: "https://cdn.example/episode.mp4" }),
    "https://cdn.example/episode.mp4", "real direct playback sources stay unchanged");
  const webosHelpers = vm.runInNewContext(helpers + "\n({ directPlaybackUrl })", {
    Environment: { isTizen: () => false }, isLocalTizenTorrentPlaybackUrl
  });
  assert.equal(webosHelpers.directPlaybackUrl(oldTicket), oldTicket,
    "Tizen-specific route detection must not change webOS behavior");
});
test("cold service range probes must time out instead of hanging the first start", async () => {
  const { TizenEngineFsService } = await import("../js/platform/tizen/tizenEngineFsService.js");
  const originalFetch = globalThis.fetch;
  const pending = new Promise(() => {});
  let rangeAttempts = 0;
  try {
    globalThis.fetch = async (url) => {
      if (String(url).endsWith("/settings")) return {
        ok: true, clone() { return { json: async () => ({ capabilities: { torrent: true } }) }; }
      };
      if (String(url).endsWith("/range-probe")) { rangeAttempts++; return pending; }
      throw new Error("Unexpected URL");
    };
    await assert.rejects(TizenEngineFsService.probeBaseUrl(BASE, 25), /range probe timed out/);
    assert.equal(rangeAttempts, 1);
  } finally {
    globalThis.fetch = originalFetch;
  }
});