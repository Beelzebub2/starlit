import assert from "node:assert/strict";
import { test } from "node:test";
import {
  isLocalTizenTorrentPlaybackUrl,
  probeTizenTorrentFirstByte,
  waitForTizenTorrentFirstByte
} from "./tizenTorrentPlaybackReadiness.js";

const hash = "a".repeat(40);
const playable = "http://127.0.0.1:2710/" + hash + "/0?ticket=" + "b".repeat(64);
const createXHR = (status, bytes) => () => ({
  status: 0,
  response: null,
  open(method, url, async) {
    assert.equal(method, "GET");
    assert.equal(url, playable);
    assert.equal(async, true);
  },
  setRequestHeader(key, value) {
    assert.equal(key, "Range");
    assert.equal(value, "bytes=0-0");
  },
  send() {
    queueMicrotask(() => {
      this.status = status;
      this.response = new ArrayBuffer(bytes);
      this.onload();
    });
  },
  abort() {}
});

test("readiness probes are limited to loopback torrent playback URLs", () => {
  assert.equal(isLocalTizenTorrentPlaybackUrl(playable), true);
  assert.equal(isLocalTizenTorrentPlaybackUrl("http://localhost:2710/" + hash + "/0?ticket=x"), true);
  assert.equal(isLocalTizenTorrentPlaybackUrl("http://127.0.0.1:2711/" + hash + "/0"), false);
  assert.equal(isLocalTizenTorrentPlaybackUrl("http://203.0.113.1:2710/" + hash + "/0"), false);
  assert.equal(isLocalTizenTorrentPlaybackUrl("https://127.0.0.1:2710/" + hash + "/0"), false);
  assert.equal(isLocalTizenTorrentPlaybackUrl("magnet:?xt=urn:btih:abc"), false);
});

test("only a nonempty one-byte 206 response proves media readiness", async () => {
  assert.equal(await probeTizenTorrentFirstByte(playable, 500, createXHR(206, 1)), "ready");
  assert.equal(await probeTizenTorrentFirstByte(playable, 500, createXHR(206, 0)), "retry");
  assert.equal(await probeTizenTorrentFirstByte(playable, 500, createXHR(206, 2)), "retry");
  assert.equal(await probeTizenTorrentFirstByte(playable, 500, createXHR(200, 20)), "retry");
  assert.equal(await probeTizenTorrentFirstByte(playable, 500, createXHR(403, 1)), "session-lost");
  assert.equal(await probeTizenTorrentFirstByte(playable, 500, createXHR(404, 1)), "session-lost");
});

test("a cold torrent waits for actual bytes without discarding its peer connections", async () => {
  let probes = 0;
  const ready = await waitForTizenTorrentFirstByte(playable, {
    timeoutMs: 100,
    retryMs: 1,
    probe: async () => (++probes === 3 ? "ready" : "retry")
  });
  assert.equal(ready, "ready");
  assert.equal(probes, 3);
});

test("a cold first range keeps its download window open instead of aborting every five seconds", async () => {
  let probeTimeout = 0;
  assert.equal(await waitForTizenTorrentFirstByte(playable, {
    timeoutMs: 15_000,
    probe: async (_url, timeoutMs) => { probeTimeout = timeoutMs; return "ready"; }
  }), "ready");
  assert.ok(probeTimeout > 10_000, "The initial piece must retain its peer-download window");
});

test("invalid playback tickets fail promptly instead of retrying", async () => {
  let probes = 0;
  const ready = await waitForTizenTorrentFirstByte(playable, {
    timeoutMs: 100,
    retryMs: 1,
    probe: async () => { probes++; return "session-lost"; }
  });
  assert.equal(ready, "session-lost");
  assert.equal(probes, 1);
});

test("an unavailable stream times out without probing unrelated hosts", async () => {
  assert.equal(await waitForTizenTorrentFirstByte(playable, {
    timeoutMs: 20, retryMs: 1, probe: async () => "retry"
  }), "unavailable");
  let probes = 0;
  assert.equal(await waitForTizenTorrentFirstByte("http://example.com/" + hash + "/0", {
    probe: async () => { probes++; return "ready"; }
  }), "unavailable");
  assert.equal(probes, 0);
});

test("a newer source cancels stale first-byte readiness before the next probe", async () => {
  let current = true;
  let probes = 0;
  const ready = await waitForTizenTorrentFirstByte(playable, {
    timeoutMs: 250,
    retryMs: 1,
    isCurrent: () => current,
    probe: async () => { probes++; current = false; return "ready"; }
  });
  assert.equal(ready, "cancelled", "a stale response must not start the old episode");
  assert.equal(probes, 1);
});

test("superseding a source aborts the outstanding range without waiting for peer data", async () => {
  let cancel = null;
  let sent = false;
  let aborted = false;
  const pending = probeTizenTorrentFirstByte(playable, 35_000, () => ({
    open() {},
    setRequestHeader() {},
    send() { sent = true; },
    abort() { aborted = true; }
  }), (abort) => { cancel = abort; });
  assert.equal(sent, true);
  cancel();
  assert.equal(await pending, "cancelled");
  assert.equal(aborted, true);
  assert.equal(cancel, null);
});
