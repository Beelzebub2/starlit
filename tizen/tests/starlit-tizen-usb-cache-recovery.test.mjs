import test from "node:test";
import assert from "node:assert/strict";
import { connectLocalControl, clearLocalControlTokens } from "../js/platform/tizen/starlitControlBridge.js";
import { TizenEngineFsService } from "../js/platform/tizen/tizenEngineFsService.js";
import { TizenUsbTorrentCache } from "../js/platform/tizen/tizenUsbTorrentCache.js";
import { P2pCacheService } from "../js/core/p2p/p2pCacheService.js";
import { Platform } from "../js/platform/index.js";
import { tizenAdapter } from "../js/platform/adapters/tizenAdapter.js";

const BASE = "http://127.0.0.1:2710";
const SERVICE_ID = "StarlitTV1.EngineFsService";
const TOKEN = "a".repeat(64);
const STORAGE_KEY = "starlit.tizen.usbTorrentCache.enabled.v1";
const bytes = 1024 ** 3;

function localControlRuntime() {
  let receive;
  return {
    crypto: { getRandomValues(buffer) { buffer.fill(9); return buffer; } },
    tizen: {
      messageport: {
        requestTrustedLocalMessagePort() {
          return {
            addMessagePortListener(callback) { receive = callback; return 1; },
            removeMessagePortListener() {}
          };
        },
        requestTrustedRemoteMessagePort(id) {
          assert.equal(id, SERVICE_ID);
          return {
            isTrusted: true,
            sendMessage(message) {
              queueMicrotask(() => receive([
                { key: "nonce", value: message[0].value },
                { key: "token", value: TOKEN }
              ], { appId: SERVICE_ID, isTrusted: true }));
            }
          };
        }
      }
    }
  };
}
function mountedUsbApi() {
  return {
    filesystem: {
      listStorages(success) {
        success([{ label: "usb0", type: "EXTERNAL", state: "MOUNTED" }]);
      },
      resolve(label, success, failure, mode) {
        assert.equal(label, "usb0");
        assert.equal(mode, "rw");
        success({ readOnly: false, isDirectory: true, toURI: () => "file:///opt/storage/usb0" });
      }
    },
    systeminfo: {
      getPropertyValue(name, success) {
        assert.equal(name, "STORAGE");
        success({ units: [
          { type: "USB_HOST", isRemovable: true, capacity: 4 * bytes, availableCapacity: 3 * bytes }
        ] });
      }
    }
  };
}
function response(body, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}
async function withFixture(run) {
  const previous = {
    tizen: globalThis.tizen,
    fetch: globalThis.fetch,
    localStorage: globalThis.localStorage,
    currentPlatform: Platform.current,
    ensureStarted: TizenEngineFsService.ensureStarted
  };
  const preferences = new Map();
  globalThis.localStorage = {
    getItem(key) { return preferences.get(key) || null; },
    setItem(key, value) { preferences.set(key, String(value)); }
  };
  globalThis.tizen = mountedUsbApi();
  Platform.current = tizenAdapter;
  clearLocalControlTokens();
  await connectLocalControl(SERVICE_ID, "2710", localControlRuntime());
  try {
    await TizenUsbTorrentCache.setEnabled(false);
    return await run({ preferences });
  } finally {
    await TizenUsbTorrentCache.setEnabled(false).catch(() => {});
    globalThis.tizen = previous.tizen;
    globalThis.fetch = previous.fetch;
    globalThis.localStorage = previous.localStorage;
    Platform.current = previous.currentPlatform;
    TizenEngineFsService.ensureStarted = previous.ensureStarted;
    clearLocalControlTokens();
  }
}

test("a dropped local fetch reconnects EngineFS once and configures the USB cache", async () => {
  await withFixture(async () => {
    let reconnects = 0, probes = 0;
    TizenEngineFsService.ensureStarted = async ({ purpose }) => {
      assert.equal(purpose, "p2p");
      reconnects++;
      return { status: "success", baseUrl: BASE };
    };
    globalThis.fetch = async (url, options) => {
      assert.equal(options.headers["X-Starlit-Token"], TOKEN);
      assert.equal(new URL(url).origin, BASE);
      if (url.endsWith("/cache/probe")) {
        probes++;
        assert.equal(JSON.parse(options.body).root, "/opt/storage/usb0");
        if (probes === 1) throw new TypeError("Failed to fetch");
        return response({ mode: "usb", cacheBytes: 512 * 1024 ** 2, profile: "balanced", writeMBps: 24 });
      }
      if (url.endsWith("/cache/status")) return response({ configured: true, profile: "balanced" });
      return response({ configured: false });
    };
    const enabled = await TizenUsbTorrentCache.setEnabled(true, BASE);
    assert.equal(enabled.mode, "usb");
    assert.equal(probes, 2);
    assert.equal(reconnects, 1, "one bounded reconnect, not a restart loop");
    const state = await TizenUsbTorrentCache.getState(BASE);
    assert.equal(state.issue, undefined);
    assert.equal(state.configured, true);
  });
});

test("permanent Failed to fetch keeps RAM fallback and does not expose browser text", async () => {
  await withFixture(async () => {
    let fetchCount = 0, reconnects = 0;
    globalThis.fetch = async () => {
      fetchCount++;
      throw new TypeError("Failed to fetch");
    };
    TizenEngineFsService.ensureStarted = async () => {
      reconnects++;
      return { status: "error", detail: "EngineFS unavailable" };
    };
    const enabled = await TizenUsbTorrentCache.setEnabled(true, BASE);
    assert.equal(enabled.mode, "memory");
    assert.equal(enabled.issue, "service-unavailable");
    assert.equal(enabled.reason, "", "do not render raw browser errors");
    const countAfterEnable = fetchCount;
    assert.equal(fetchCount, 1, "a failed probe never sends a destructive disable request");
    const state = await TizenUsbTorrentCache.getState(BASE);
    assert.equal(state.enabled, true, "user opt-in remains saved");
    assert.equal(state.configured, false);
    assert.equal(state.issue, "service-unavailable");
    assert.equal(fetchCount, countAfterEnable, "status does not trigger a second service restart");
    assert.equal(reconnects, 1, "service recovery is bounded");
  });
});

test("multiple disconnected USB drives share one bounded service reconnect", async () => {
  await withFixture(async () => {
    globalThis.tizen.filesystem.listStorages = (success) => success([
      { label: "usb0", type: "EXTERNAL", state: "MOUNTED" },
      { label: "usb1", type: "EXTERNAL", state: "MOUNTED" }
    ]);
    globalThis.tizen.filesystem.resolve = (label, success) => success({
      readOnly: false, isDirectory: true,
      toURI: () => "file:///opt/storage/" + label
    });
    let reconnects = 0, probes = 0, disables = 0;
    globalThis.fetch = async (url) => {
      if (url.endsWith("/cache/probe")) {
        probes++;
        throw new TypeError("Failed to fetch");
      }
      if (url.endsWith("/cache/disable")) disables++;
      return response({});
    };
    TizenEngineFsService.ensureStarted = async () => {
      reconnects++;
      return { status: "error" };
    };
    const result = await TizenUsbTorrentCache.setEnabled(true, BASE);
    assert.equal(result.issue, "service-unavailable");
    assert.equal(probes, 2);
    assert.equal(reconnects, 1);
    assert.equal(disables, 0);
  });
});

test("missing EngineFS startup shows the same localized service issue", async () => {
  await withFixture(async () => {
    TizenEngineFsService.ensureStarted = async () => ({ status: "error", detail: "Not started" });
    await TizenUsbTorrentCache.setEnabled(true);
    const state = await P2pCacheService.getState();
    assert.equal(state.usbEnabled, true);
    assert.equal(state.usbMounted, true, "native USB inventory works without EngineFS");
    assert.equal(state.usbIssue, "service-unavailable");
    assert.equal(state.available, false);
    assert.equal(state.error, undefined);
  });
});
test("thrown EngineFS startup still reports mounted USB independently", async () => {
  await withFixture(async () => {
    TizenEngineFsService.ensureStarted = async () => {
      throw new TypeError("Failed to fetch");
    };
    await TizenUsbTorrentCache.setEnabled(true);
    const state = await P2pCacheService.getState();
    assert.equal(state.available, false);
    assert.equal(state.usbEnabled, true);
    assert.equal(state.usbMounted, true);
    assert.equal(state.usbIssue, "service-unavailable");
  });
});

test("busy torrent service is never disabled by a USB detection probe", async () => {
  await withFixture(async () => {
    let disables = 0;
    globalThis.fetch = async (url) => {
      if (url.endsWith("/cache/probe")) return response({ error: "Busy" }, 409);
      if (url.endsWith("/cache/disable")) disables++;
      return response({});
    };
    const result = await TizenUsbTorrentCache.setEnabled(true, BASE);
    assert.equal(result.mode, "memory");
    assert.match(result.reason, /HTTP 409/);
    assert.equal(disables, 0, "an active stream must keep its existing cache");
  });
});

test("a failed localhost cache status does not falsely claim USB is configured", async () => {
  await withFixture(async () => {
    TizenEngineFsService.ensureStarted = async () => ({ status: "error", detail: "Service unavailable" });
    globalThis.fetch = async (url) => {
      if (url.endsWith("/cache/probe")) return response({ mode: "usb", cacheBytes: 512 * 1024 ** 2 });
      if (url.endsWith("/cache/status")) throw new TypeError("Failed to fetch");
      return response({});
    };
    await TizenUsbTorrentCache.setEnabled(true, BASE);
    const state = await TizenUsbTorrentCache.getState(BASE);
    assert.equal(state.configured, false);
    assert.equal(state.issue, "service-unavailable");
    assert.equal(state.mode, "memory");
  });
});
