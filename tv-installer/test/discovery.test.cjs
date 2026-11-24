"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const {
  normalizeSamsungScan, discoverSamsung, parseSsdpResponse, safeDescriptionUrl,
  parseLgDescription, discoverLg, privateInterfaceIps, mergeDevices, discoverTvs,
  discoverSamsungLan, samsungApiDevice, samsungDescriptionDevice, scanInterfaceIps,
  requestDescription
} = require("../src/discovery.cjs");

const LG_XML = [
  '<?xml version="1.0"?><root><device>',
  '<deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>',
  '<manufacturer>LG Electronics</manufacturer>',
  '<friendlyName>Living Room LG webOS TV</friendlyName>',
  '<modelName>OLED55C2</modelName>',
  '</device></root>'
].join("");

test("Samsung scan uses the installed, verified Apps2Samsung command and keeps readiness details", async () => {
  const calls = [];
  const streamed = [];
  const result = await discoverSamsung({
    deepScan: true,
    onDevice: device => streamed.push(device),
    ensureTool: async () => "/tmp/verified-cli",
    run: async (exe, args, options) => {
      calls.push([exe, args, options.timeoutMs]);
      return {
        ok: true, blockedByOs: false,
        tvs: [
          { ip: "192.168.1.24", name: "Living Room", model: "RU7025", ready: true },
          { ip: "10.0.0.42", model: "Samsung Frame", ready: false, notReadyReason: "Host PC IP needs updating" },
          { ip: "8.8.8.8", ready: true },
          { ip: "127.0.0.1", ready: true },
          { ip: "bad;rm", ready: true }
        ]
      };
    }
  });
  assert.deepEqual(calls, [["/tmp/verified-cli", ["scan"], 90000]]);
  assert.equal(result.devices.length, 2);
  assert.deepEqual(result.devices.map(tv => tv.ip), ["192.168.1.24", "10.0.0.42"]);
  assert.equal(result.devices[0].ready, true);
  assert.equal(result.devices[1].ready, false);
  assert.deepEqual(streamed, result.devices);
  assert.match(result.devices[1].detail, /Host PC IP/);
  assert.throws(() => normalizeSamsungScan({ ok: true, tvs: {} }), /valid device list/);
});

test("SSDP parser accepts only bounded HTTP 200 responses and case-insensitive headers", () => {
  const response = Buffer.from(
    "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.55:3000/ssdp/device.xml\r\n" +
    "Server: webOS/6.0 UPnP/1.0\r\nST: urn:schemas-upnp-org:device:Basic:1\r\n\r\n"
  );
  const parsed = parseSsdpResponse(response);
  assert.equal(parsed.location, "http://192.168.1.55:3000/ssdp/device.xml");
  assert.match(parsed.server, /webOS/);
  assert.equal(parseSsdpResponse(Buffer.from("NOTIFY * HTTP/1.1\r\n\r\n")), null);
  assert.equal(parseSsdpResponse(Buffer.alloc(8193)), null);
});

test("Device description fetch URLs cannot escape the SSDP sender's private IP", () => {
  assert.equal(safeDescriptionUrl("http://192.168.1.55:3000/desc.xml", "192.168.1.55").hostname, "192.168.1.55");
  for (const uri of [
    "http://8.8.8.8/ssrf", "http://127.0.0.1/secret", "https://192.168.1.55/a",
    "http://192.168.1.55@evil.example/secret", "file:///etc/passwd", "http://192.168.1.1/other"
  ]) {
    assert.equal(safeDescriptionUrl(uri, "192.168.1.55"), null, uri);
  }
  assert.equal(safeDescriptionUrl("http://192.168.1.55/", "8.8.8.8"), null);
});

test("Only genuine LG manufacturer descriptions are labeled webOS", () => {
  assert.deepEqual(parseLgDescription(LG_XML), { name: "Living Room LG webOS TV", model: "OLED55C2" });
  assert.equal(parseLgDescription(LG_XML.replace("LG Electronics", "Samsung Electronics")), null);
  assert.equal(parseLgDescription("<root><friendlyName>LG webOS TV</friendlyName></root>"), null);
  assert.equal(parseLgDescription("x".repeat(32769)), null);
});

test("LG discovery listens on private interfaces, deduplicates and validates descriptions", async () => {
  const sent = [];
  const visited = [];
  const factory = () => {
    const socket = new EventEmitter();
    socket.bind = (_port, ip, callback) => { visited.push(ip); queueMicrotask(callback); };
    socket.setMulticastInterface = () => {};
    socket.send = packet => {
      sent.push(String(packet));
      queueMicrotask(() => {
        socket.emit("message", Buffer.from(
          "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.55:3000/desc.xml\r\n" +
          "ST: urn:schemas-upnp-org:device:Basic:1\r\n\r\n"
        ), { address: "192.168.1.55" });
        socket.emit("message", Buffer.from(
          "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.60:3000/desc.xml\r\n" +
          "SERVER: Generic UPnP\r\n\r\n"
        ), { address: "192.168.1.60" });
        socket.emit("message", Buffer.from(
          "HTTP/1.1 200 OK\r\nLOCATION: http://8.8.8.8/ssrf\r\n\r\n"
        ), { address: "8.8.8.8" });
      });
    };
    socket.close = () => {};
    return socket;
  };
  const result = await discoverLg({
    interfaces: {
      Ethernet: [{ family: "IPv4", address: "192.168.1.50", internal: false }],
      VPN: [{ family: "IPv4", address: "127.0.0.1", internal: true }]
    },
    socketFactory: factory,
    durationMs: 5,
    listSavedDevices: async () => [{ name: "living-room-lg", host: "192.168.1.55" }],
    readDescription: async url => url.hostname === "192.168.1.55"
      ? LG_XML : "<root><manufacturer>Acme Corp</manufacturer></root>"
  });
  assert.deepEqual(visited, ["192.168.1.50"]);
  assert.equal(sent.length, 2);
  assert.ok(sent[0].includes("M-SEARCH * HTTP/1.1"));
  assert.deepEqual(result.devices.map(tv => tv.ip), ["192.168.1.55"]);
  assert.equal(result.devices[0].platform, "webos");
  assert.equal(result.devices[0].name, "Living Room LG webOS TV");
  assert.equal(result.devices[0].savedDeviceName, "living-room-lg");
  assert.equal(result.devices[0].ready, false);
});

test("Private adapter selection excludes public and loopback interfaces", () => {
  assert.deepEqual(privateInterfaceIps({
    a: [{ family: "IPv4", address: "192.168.0.7", internal: false }],
    b: [{ family: 4, address: "10.2.3.4", internal: false }],
    c: [{ family: "IPv4", address: "8.8.8.8", internal: false }],
    d: [{ family: "IPv4", address: "127.0.0.1", internal: true }]
  }), ["192.168.0.7", "10.2.3.4"]);
  assert.deepEqual(mergeDevices([
    [{ ip: "192.168.1.55", platform: "webos", name: "LG TV" }],
    [{ ip: "192.168.1.55", platform: "webos", name: "Same TV" }]
  ]).map(tv => tv.name), ["LG TV"]);
});

test("A selected LG scan never starts Samsung, even when Samsung is unavailable", async () => {
  const streamed = [];
  const result = await discoverTvs({
    platform: "webos",
    samsung: async () => { throw new Error("Unavailable"); },
    lg: async ({ onDevice }) => {
      const device = { platform: "webos", ip: "192.168.1.55", name: "LG TV" };
      onDevice(device);
      return { devices: [device] };
    },
    onDevice: device => streamed.push(device)
  });
  assert.equal(result.devices.length, 1);
  assert.equal(result.devices[0].platform, "webos");
  assert.deepEqual(streamed, result.devices);
  assert.deepEqual(result.warnings, []);
});

test("Samsung-only scans skip LG and warn on scanner failure", async () => {
  const result = await discoverTvs({
    platform: "tizen",
    samsung: async () => { throw new Error("Unavailable"); },
    lg: async () => { throw new Error("LG must not run"); }
  });
  assert.deepEqual(result.devices, []);
  assert.match(result.warnings[0], /Samsung discovery unavailable/);
  await assert.rejects(() => discoverTvs({ platform: "both" }), /Choose Samsung or LG/);
});

test("Samsung HTTP and SSDP responses require Samsung identity", () => {
  const body = JSON.stringify({ device: {
    type: "Samsung SmartTV", name: "Living Room", modelName: "TEST-SERIES"
  } });
  assert.deepEqual(samsungApiDevice(body, "192.168.1.85"), {
    platform: "tizen", ip: "192.168.1.85",
    name: "Living Room", model: "TEST-SERIES",
    ready: false,
    detail: "TV detected. Check Developer Mode and Host PC IP before installing"
  });
  assert.equal(samsungApiDevice(body.replace("Samsung SmartTV", "Generic TV"), "192.168.1.85"), null);
  assert.equal(samsungApiDevice(body, "8.8.8.8"), null);
  const xml = LG_XML.replace("LG Electronics", "Samsung Electronics");
  assert.equal(samsungDescriptionDevice(xml, "192.168.1.85").platform, "tizen");
  assert.equal(samsungDescriptionDevice(LG_XML, "192.168.1.85"), null);
});

test("Fast Samsung discovery uses real LAN adapters, bounded probes and streams devices", async () => {
  const interfaces = {
    Ethernet: [{ family: "IPv4", address: "192.168.1.40", internal: false }],
    "VMware Network Adapter VMnet8": [{ family: "IPv4", address: "192.168.33.1", internal: false }],
    "vEthernet (WSL)": [{ family: "IPv4", address: "172.24.18.1", internal: false }]
  };
  assert.deepEqual(scanInterfaceIps(interfaces), ["192.168.1.40"]);
  const reported = [];
  let finished = false;
  const socketFactory = () => {
    const socket = new EventEmitter();
    socket.bind = (_port, _ip, callback) => queueMicrotask(callback);
    socket.setMulticastInterface = () => {};
    socket.send = () => queueMicrotask(() => socket.emit("message", Buffer.from(
      "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.85:9197/desc.xml\r\n" +
      "SERVER: Samsung-Linux/4.1 UPnP/1.0\r\n\r\n"
    ), { address: "192.168.1.85" }));
    socket.close = () => {};
    return socket;
  };
  let probes = 0;
  const scan = discoverSamsungLan({
    interfaces, socketFactory, durationMs: 75,
    readDescription: async () => LG_XML.replace("LG Electronics", "Samsung Electronics"),
    probeApi: async ip => {
      probes++;
      return ip === "192.168.1.80"
        ? samsungApiDevice(JSON.stringify({ device: { type: "Samsung SmartTV", name: "Second TV" } }), ip)
        : null;
    },
    onDevice: device => {
      assert.equal(finished, false, "A device must be available before the scan ends");
      reported.push(device.ip);
    }
  });
  const result = await scan;
  finished = true;
  assert.equal(probes, 253);
  assert.deepEqual(reported.sort(), ["192.168.1.80", "192.168.1.85"]);
  assert.equal(result.devices.length, 2);
  assert.equal(result.noLan, false);
});

test("LG reports discovered devices before all SSDP sockets close", async () => {
  const seen = [];
  const factory = () => {
    const socket = new EventEmitter();
    socket.bind = (_port, _ip, callback) => queueMicrotask(callback);
    socket.setMulticastInterface = () => {};
    socket.send = () => queueMicrotask(() => socket.emit("message", Buffer.from(
      "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.55:3000/desc.xml\r\n" +
      "SERVER: webOS/6.0 UPnP/1.0\r\n\r\n"
    ), { address: "192.168.1.55" }));
    socket.close = () => {};
    return socket;
  };
  let completed = false;
  const result = await discoverLg({
    interfaces: { Ethernet: [{ family: "IPv4", address: "192.168.1.50", internal: false }] },
    socketFactory: factory, durationMs: 75,
    readDescription: async () => LG_XML, listSavedDevices: async () => [],
    onDevice: device => {
      assert.equal(completed, false);
      seen.push(device.ip);
    }
  });
  completed = true;
  assert.deepEqual(seen, ["192.168.1.55"]);
  assert.deepEqual(result.devices.map(device => device.ip), seen);
});

test("HTTP device info probing enforces wall-clock timeouts", async () => {
  let destroyed = false;
  const body = await requestDescription(new URL("http://192.168.1.80:8001/api/v2/"), {
    timeoutMs: 15, httpGet: () => {
      const request = new EventEmitter();
      request.destroy = () => { destroyed = true; };
      return request;
    }
  });
  assert.equal(body, null);
  assert.equal(destroyed, true);
});
