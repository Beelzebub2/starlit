import test from "node:test";
import assert from "node:assert/strict";
import { detectTizenUsbStorage, TizenUsbTorrentCache } from "./tizenUsbTorrentCache.js";

function withMock(api, run) {
  const previous = globalThis.tizen;
  globalThis.tizen = api;
  return Promise.resolve().then(run).finally(() => { globalThis.tizen = previous; });
}
function mock(units, mounts, paths = {}) {
  return {
    filesystem: {
      listStorages: (good) => good(mounts),
      resolve: (label, good, bad, mode) => {
        assert.equal(mode, "rw");
        if (paths[label]) good({
          path: label,
          toURI: () => String(paths[label]).startsWith("file:")
            ? paths[label] : "file://" + paths[label],
          isDirectory: true, readOnly: false
        });
        else bad(new Error("Read-only or inaccessible"));
      }
    },
    systeminfo: {
      getPropertyValue: (key, good) => {
        assert.equal(key, "STORAGE");
        good({ units });
      }
    }
  };
}
const GiB = 1024 ** 3;
test("select mounted external drives and ignore internal storage", async () => {
  const api = mock(
    [{ type: "INTERNAL", capacity: 32 * GiB, availableCapacity: 20 * GiB },
     { type: "USB_HOST", capacity: 4 * GiB, availableCapacity: 3 * GiB, isRemovable: true }],
    [{ type: "INTERNAL", state: "MOUNTED", label: "internal0" },
     { type: "EXTERNAL", state: "MOUNTED", label: "removable2" },
     { type: "EXTERNAL", state: "REMOVED", label: "removable3" }],
    { removable2: "/opt/storage/usb0" }
  );
  await withMock(api, async () => {
    const { drives } = await detectTizenUsbStorage();
    assert.equal(drives.length, 1);
    assert.equal(drives[0].root, "/opt/storage/usb0");
    assert.equal(drives[0].freeBytes, 3 * GiB);
  });
});
test("multiple storages reserve using conservative capacity", async () => {
  const api = mock(
    [{ type: "USB_HOST", capacity: 8 * GiB, availableCapacity: 6 * GiB, isRemovable: true },
     { type: "USB_HOST", capacity: 4 * GiB, availableCapacity: 2 * GiB, isRemovable: true }],
    [{ type: "EXTERNAL", state: "MOUNTED", label: "A" },
     { type: "EXTERNAL", state: "MOUNTED", label: "B" }],
    { A: "/mnt/a", B: "/mnt/b" }
  );
  await withMock(api, async () => {
    const { drives } = await detectTizenUsbStorage();
    assert.equal(drives.length, 2);
    assert(drives.every((item) => item.totalBytes === 4 * GiB && item.freeBytes === 2 * GiB));
  });
});
test("ignore USB with no safe capacity mapping or writable native path", async () => {
  await withMock(mock([], [{ type: "EXTERNAL", state: "MOUNTED", label: "A" }]), async () => {
    const result = await detectTizenUsbStorage();
    assert.equal(result.drives.length, 0, "A drive without a safe native path is not eligible");
    assert.equal(result.mountedCount, 1, "native mount detection is distinct from cache eligibility");
  });
  await withMock(mock(
    [{ type: "USB_HOST", capacity: 4 * GiB, availableCapacity: 3 * GiB, isRemovable: true }],
    [{ type: "EXTERNAL", state: "MOUNTED", label: "A" }],
    { A: "virtualRoot" }
  ), async () => {
    assert.equal((await detectTizenUsbStorage()).drives.length, 0);
  });
});
test("detect mounted USB even when SystemInfo has no removable unit", async () => {
  await withMock(mock(
    [{ type: "INTERNAL", capacity: 8 * GiB, availableCapacity: 4 * GiB }],
    [{ type: "EXTERNAL", state: "MOUNTED", label: "removable2" }],
    { removable2: "/opt/storage/usb0" }
  ), async () => {
    const result = await detectTizenUsbStorage();
    assert.equal(result.drives.length, 1);
    assert.equal(result.drives[0].root, "/opt/storage/usb0");
    assert.equal(result.drives[0].freeBytes, null, "Never borrow internal capacity for USB");
    assert.match(result.reason, /USB connected/);
  });
});
test("USB enumeration is independent from unsupported or failing STORAGE property", async () => {
  const api = mock([], [{ type: "EXTERNAL", state: "MOUNTED", label: "B" }],
    { B: "/opt/storage/usb1" });
  delete api.systeminfo;
  await withMock(api, async () => {
    const result = await detectTizenUsbStorage();
    assert.equal(result.drives.length, 1);
    assert.equal(result.drives[0].totalBytes, null);
  });
  api.systeminfo = { getPropertyValue: (_, good, bad) => bad(new Error("NotSupportedError")) };
  await withMock(api, async () => {
    assert.equal((await detectTizenUsbStorage()).drives.length, 1);
  });
});
test("Tizen 5 path APIs detect removable storage without deprecated resolve", async () => {
  const api = mock([], [
    { type: "INTERNAL", state: "MOUNTED", label: "internal0" },
    { type: "EXTERNAL", state: "MOUNTED", label: "removable_sda1" }
  ]);
  delete api.filesystem.resolve;
  const paths = [];
  api.filesystem.isDirectory = (label) => {
    paths.push("check:" + label);
    return label === "removable_sda1";
  };
  api.filesystem.toURI = (label) => {
    paths.push("uri:" + label);
    return "file:///opt/storage/USB%20Drive";
  };
  await withMock(api, async () => {
    const result = await detectTizenUsbStorage();
    assert.deepEqual(result.drives.map((drive) => drive.root), ["/opt/storage/USB Drive"]);
    assert.equal(result.drives[0].freeBytes, null);
    assert.deepEqual(paths, ["check:removable_sda1", "uri:removable_sda1"]);
  });
});
test("deprecated Tizen 5 resolve can fail while path-based access works", async () => {
  const api = mock([], [{ type: "EXTERNAL", state: "MOUNTED", label: "removable_sdb1" }]);
  let attempts = 0;
  api.filesystem.resolve = (_, success, fail) => {
    attempts++;
    fail(new Error("NotSupportedError"));
  };
  api.filesystem.isDirectory = (label) => label === "removable_sdb1";
  api.filesystem.toURI = () => "file:///media/usb-drive-b";
  await withMock(api, async () => {
    const result = await detectTizenUsbStorage();
    assert.equal(result.drives.length, 1);
    assert.equal(result.drives[0].root, "/media/usb-drive-b");
    assert.equal(attempts, 0, "valid modern mount must not wait for deprecated resolve");
  });
});
test("legacy Tizen filesystem remains usable when the modern directory probe fails", async () => {
  const api = mock([], [{ type: "EXTERNAL", state: "MOUNTED", label: "removable2" }],
    { removable2: "/mnt/legacy-usb" });
  api.filesystem.isDirectory = () => { throw new Error("NotSupportedError"); };
  api.filesystem.toURI = () => { throw new Error("NotSupportedError"); };
  await withMock(api, async () => {
    assert.equal((await detectTizenUsbStorage()).drives[0].root, "/mnt/legacy-usb");
  });
});
test("array-like native storage collections are handled as mounted USB lists", async () => {
  const api = mock([], []);
  api.filesystem.listStorages = (good) => good({
    0: { type: "INTERNAL", state: "MOUNTED", label: "internal0" },
    1: { type: "EXTERNAL", state: "MOUNTED", label: "removable_sda1" },
    length: 2
  });
  api.filesystem.toURI = () => "file:///mnt/usb1";
  api.filesystem.isDirectory = () => true;
  delete api.filesystem.resolve;
  await withMock(api, async () => {
    const { drives } = await detectTizenUsbStorage();
    assert.equal(drives.length, 1);
    assert.equal(drives[0].label, "removable_sda1");
  });
});
test("a Tizen 5 URI alone never qualifies a missing or unsafe storage directory", async () => {
  const mounts = [{ type: "EXTERNAL", state: "MOUNTED", label: "removable_sda1" }];
  for (const [directory, uri] of [
    [false, "file:///opt/storage/usb0"],
    [true, "https://example.com/a"],
    [true, "file://remote-host/usb"],
    [true, "file:///mnt/../private"],
    [true, "file:///mnt/%2e%2e/private"],
    [true, "file:///mnt/%5cprivate"],
    [true, "file:///mnt/%00drive"],
    [true, "/not-a-file-uri"]
  ]) {
    const api = mock([], mounts);
    delete api.filesystem.resolve;
    api.filesystem.isDirectory = () => directory;
    api.filesystem.toURI = () => uri;
    await withMock(api, async () => {
      assert.equal((await detectTizenUsbStorage()).drives.length, 0);
    });
  }
});
test("an explicit read-only legacy handle is rejected without a usable modern mount", async () => {
  const api = mock([], [{ type: "EXTERNAL", state: "MOUNTED", label: "removable_sda1" }]);
  api.filesystem.resolve = (_, good) => good({
    readOnly: true, isDirectory: true, toURI: () => "file:///opt/storage/usb0"
  });
  let modernChecks = 0;
  api.filesystem.isDirectory = () => { modernChecks++; return false; };
  api.filesystem.toURI = () => "file:///opt/storage/usb0";
  await withMock(api, async () => {
    assert.equal((await detectTizenUsbStorage()).drives.length, 0);
    assert.equal(modernChecks, 1);
  });
});
test("physical file URI is decoded; virtual paths and unsafe file URIs are rejected", async () => {
  const units = [{ type: "USB_HOST", capacity: 4 * GiB, availableCapacity: 3 * GiB, isRemovable: true }];
  const mounts = [{ type: "EXTERNAL", state: "MOUNTED", label: "A" }];
  await withMock(mock(units, mounts, { A: "file:///opt/media/USB%20Drive" }), async () => {
    const { drives } = await detectTizenUsbStorage();
    assert.equal(drives[0].root, "/opt/media/USB Drive");
  });
  await withMock(mock(units, mounts, { A: "file:///opt/media/USB%00Drive" }), async () => {
    assert.equal((await detectTizenUsbStorage()).drives.length, 0);
  });
  await withMock(mock(units, mounts, { A: "http://example.com/file" }), async () => {
    assert.equal((await detectTizenUsbStorage()).drives.length, 0);
  });
});
test("unsupported Tizen API leaves torrent playback on RAM", async () => {
  await withMock({}, async () => {
    const result = await detectTizenUsbStorage();
    assert.equal(result.drives.length, 0);
  });
});
test("USB notifications are available before a cache opt-in and can be unsubscribed", async () => {
  let storageHandler;
  let registrations = 0;
  const seen = [];
  await withMock({
    filesystem: {
      addStorageStateChangeListener(handler) {
        registrations++;
        storageHandler = handler;
        return 1;
      }
    }
  }, async () => {
    const unsubscribe = TizenUsbTorrentCache.subscribeStorageChanges((storage) => seen.push(storage.label));
    assert.equal(registrations, 1);
    storageHandler({ type: "INTERNAL", label: "system", state: "MOUNTED" });
    storageHandler({ type: "EXTERNAL", label: "usb-1", state: "MOUNTED" });
    assert.deepEqual(seen, ["usb-1"]);
    unsubscribe();
    storageHandler({ type: "EXTERNAL", label: "usb-2", state: "MOUNTED" });
    assert.deepEqual(seen, ["usb-1"], "disposed UI must not keep receiving events");
  });
});
