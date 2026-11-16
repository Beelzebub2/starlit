"use strict";

const dgram = require("node:dgram");
const http = require("node:http");
const os = require("node:os");
const { isLanIPv4 } = require("./platforms.cjs");
const { ensureSamsungCli, runSamsungCli } = require("./samsung.cjs");
const { devices: savedWebosDevices } = require("./installer.cjs");

const SSDP_ADDRESS = "239.255.255.250";
const SSDP_PORT = 1900;
const SEARCH_TARGETS = ["ssdp:all", "urn:schemas-upnp-org:device:Basic:1"];
const MAX_CANDIDATES = 24;
const MAX_DESCRIPTION_BYTES = 32768;
const SAMSUNG_PROBE_TIMEOUT_MS = 400;
const SAMSUNG_SCAN_DURATION_MS = 1900;

function safeLabel(value, fallback = "", limit = 80) {
  const cleaned = String(value == null ? "" : value)
    .replace(/[\x00-\x1f\x7f]/g, " ").replace(/\s+/g, " ").trim();
  return (cleaned || fallback).slice(0, limit);
}

function normalizeSamsungScan(scan) {
  if (!scan || scan.ok !== true || !Array.isArray(scan.tvs)) {
    throw new Error("Samsung TV scanner did not return a valid device list");
  }
  return scan.tvs.slice(0, 64).filter(tv => tv && isLanIPv4(tv.ip)).map(tv => ({
    platform: "tizen",
    ip: tv.ip,
    name: safeLabel(tv.name, safeLabel(tv.model, "Samsung TV")),
    model: safeLabel(tv.model),
    ready: tv.ready === true,
    detail: tv.ready === true
      ? "Developer Mode is ready for this computer"
      : safeLabel(tv.notReadyReason, "Check Samsung Developer Mode and Host PC IP", 240)
  }));
}

async function discoverSamsung({
  ensureTool = ensureSamsungCli,
  run = runSamsungCli,
  toolsDir,
  onStatus = () => {},
  onDevice = () => {},
  deepScan = false,
  ...lanOptions
} = {}) {
  if (!deepScan) {
    onStatus("Checking nearby Samsung TVs");
    return discoverSamsungLan({ ...lanOptions, onDevice });
  }
  const executable = await ensureTool({ toolsDir, onStatus });
  onStatus("Running extended Samsung scan using Apps2Samsung");
  const scan = await run(executable, ["scan"], { timeoutMs: 90000 });
  const devices = normalizeSamsungScan(scan);
  for (const device of devices) onDevice(device);
  return {
    devices,
    blocked: scan.blockedByOs === true
  };
}

function parseSsdpResponse(message) {
  if (!Buffer.isBuffer(message) || message.length > 8192) return null;
  const lines = message.toString("utf8").split(/\r?\n/);
  if (!/^HTTP\/1\.[01]\s+200(?:\s|$)/i.test(lines.shift() || "")) return null;
  const headers = Object.create(null);
  for (const line of lines) {
    if (!line) break;
    const colon = line.indexOf(":");
    if (colon <= 0) continue;
    const key = line.slice(0, colon).trim().toLowerCase();
    if (!/^[a-z0-9-]{1,40}$/.test(key) || headers[key]) continue;
    headers[key] = line.slice(colon + 1).trim().slice(0, 512);
  }
  return headers;
}

function safeDescriptionUrl(location, senderIp) {
  if (!isLanIPv4(senderIp) || typeof location !== "string" || location.length > 512) return null;
  try {
    const url = new URL(location);
    if (url.protocol !== "http:" || url.hostname !== senderIp ||
        url.username || url.password || url.hash || !["", "80"].includes(url.port) &&
        !/^\d{1,5}$/.test(url.port)) return null;
    return url;
  } catch {
    return null;
  }
}

function xmlField(xml, field) {
  const match = String(xml).match(new RegExp(
    "<(?:[a-zA-Z0-9_-]+:)?" + field + "(?:\\s[^>]*)?>([^<]{1,200})<\\/(?:[a-zA-Z0-9_-]+:)?" + field + "\\s*>", "i"
  ));
  return match ? safeLabel(match[1].replace(/&amp;/gi, "&").replace(/&quot;/gi, '"')) : "";
}

function parseLgDescription(xml) {
  if (typeof xml !== "string" || xml.length > MAX_DESCRIPTION_BYTES) return null;
  if (!/^LG(?:\s+Electronics)?\b/i.test(xmlField(xml, "manufacturer"))) return null;
  const model = xmlField(xml, "modelName") || xmlField(xml, "modelNumber");
  return {
    name: xmlField(xml, "friendlyName") || model || "LG webOS TV",
    model
  };
}

function lgHeaderHint(headers) {
  return /\bwebos\b|lge-com|LG (?:Smart )?TV|LG Electronics/i.test(
    [headers.server, headers.st, headers.usn].join(" ")
  );
}

function requestDescription(url, {
  httpGet = http.get, timeoutMs = 1500, accept = "text/xml, application/xml"
} = {}) {
  return new Promise(resolve => {
    let finished = false;
    let request;
    const timer = setTimeout(() => {
      try { request?.destroy(); } catch { /* already closed */ }
      done(null);
    }, timeoutMs);
    function done(value) {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      resolve(value);
    }
    try {
      request = httpGet(url, {
        timeout: timeoutMs,
        headers: { Accept: accept }
      }, response => {
        if (response.statusCode !== 200) {
          response.resume();
          return done(null);
        }
        let body = "", bytes = 0;
        response.setEncoding("utf8");
        response.on("data", chunk => {
          bytes += Buffer.byteLength(chunk);
          if (bytes > MAX_DESCRIPTION_BYTES) {
            request.destroy();
            done(null);
            return;
          }
          body += chunk;
        });
        response.once("end", () => done(body));
        response.once("error", () => done(null));
      });
      request.once("timeout", () => {
        request.destroy();
        done(null);
      });
      request.once("error", () => done(null));
    } catch {
      done(null);
    }
  });
}

function privateInterfaceIps(interfaces = os.networkInterfaces()) {
  return [...new Set(Object.values(interfaces).flat().filter(info =>
    info && (info.family === "IPv4" || info.family === 4) &&
    !info.internal && isLanIPv4(info.address)
  ).map(info => info.address))];
}

// Virtual LAN adapters add unrelated subnets and increase scan latency.
function scanInterfaceIps(interfaces = os.networkInterfaces()) {
  const entries = Object.entries(interfaces).flatMap(([name, addresses]) =>
    (addresses || []).filter(info => info &&
      (info.family === "IPv4" || info.family === 4) &&
      !info.internal && isLanIPv4(info.address)
    ).map(info => ({ name, ip: info.address }))
  );
  const physical = entries.filter(entry =>
    !/vEthernet|VMware|VMnet|VirtualBox|Hyper-V|Docker|WSL|Tailscale|ZeroTier|VPN/i.test(entry.name)
  );
  return [...new Set((physical.length ? physical : entries).map(entry => entry.ip))].slice(0, 2);
}

function samsungApiDevice(body, ip) {
  if (!isLanIPv4(ip) || typeof body !== "string" || body.length > MAX_DESCRIPTION_BYTES) return null;
  try {
    const data = JSON.parse(body);
    const device = data?.device;
    if (!device || !/^Samsung(?:\s+Smart)?\s*TV$/i.test(String(device.type || ""))) return null;
    return {
      platform: "tizen", ip,
      name: safeLabel(device.name, "Samsung TV"),
      model: safeLabel(device.modelName || device.model),
      ready: false,
      detail: "TV detected. Check Developer Mode and Host PC IP before installing"
    };
  } catch {
    return null;
  }
}

function samsungDescriptionDevice(xml, ip) {
  if (!isLanIPv4(ip) || typeof xml !== "string" || xml.length > MAX_DESCRIPTION_BYTES ||
      !/^Samsung(?:\s+Electronics)?\b/i.test(xmlField(xml, "manufacturer"))) return null;
  return {
    platform: "tizen", ip,
    name: xmlField(xml, "friendlyName") || "Samsung TV",
    model: xmlField(xml, "modelName") || xmlField(xml, "modelNumber"),
    ready: false,
    detail: "TV detected. Check Developer Mode and Host PC IP before installing"
  };
}

function queueWithLimit(limit) {
  const waiting = [];
  let running = 0;
  function pump() {
    while (running < limit && waiting.length) {
      const { task, resolve } = waiting.shift();
      running++;
      Promise.resolve().then(task).then(resolve, () => resolve(null)).finally(() => {
        running--;
        pump();
      });
    }
  }
  return task => new Promise(resolve => {
    waiting.push({ task, resolve });
    pump();
  });
}

// SSDP supplies early devices. A bounded /24 HTTP check also finds Samsung
// TVs on LANs that filter multicast, without the 90-second CLI-wide scan.
async function discoverSamsungLan({
  interfaces = os.networkInterfaces(),
  socketFactory = dgram.createSocket,
  readDescription = requestDescription,
  probeApi = async ip => samsungApiDevice(await requestDescription(
    new URL("http://" + ip + ":8001/api/v2/"),
    { timeoutMs: SAMSUNG_PROBE_TIMEOUT_MS, accept: "application/json" }
  ), ip),
  durationMs = SAMSUNG_SCAN_DURATION_MS,
  onDevice = () => {}
} = {}) {
  const ips = scanInterfaceIps(interfaces);
  if (!ips.length) return { devices: [], noLan: true };
  const found = new Map();
  const candidates = new Map();
  const descriptorTasks = [];
  const checkedDescriptors = new Set();
  const enqueueDescription = queueWithLimit(4);
  const report = device => {
    if (!device || device.platform !== "tizen" || !isLanIPv4(device.ip) || found.has(device.ip)) return;
    found.set(device.ip, device);
    onDevice(device);
  };
  const ssdp = ips.map(ip => listenOnInterface(ip, candidates, {
    socketFactory, durationMs,
    onCandidate: entry => {
      if (checkedDescriptors.has(entry.ip)) return;
      checkedDescriptors.add(entry.ip);
      descriptorTasks.push(enqueueDescription(async () => {
        const xml = await readDescription(entry.url, { timeoutMs: 800 });
        report(samsungDescriptionDevice(xml, entry.ip));
      }));
    }
  }));
  const prefixes = [...new Set(ips.map(ip => ip.split(".").slice(0, 3).join(".")))];
  const hosts = prefixes.flatMap(prefix =>
    Array.from({ length: 254 }, (_, index) => prefix + "." + (index + 1))
  ).filter(ip => !ips.includes(ip));
  let cursor = 0;
  const workers = Array.from({ length: 32 }, async () => {
    while (cursor < hosts.length) {
      const ip = hosts[cursor++];
      try { report(await probeApi(ip)); } catch { /* unreachable host */ }
    }
  });
  await Promise.all([...ssdp, ...workers]);
  await Promise.all(descriptorTasks);
  return { devices: [...found.values()], noLan: false };
}

function listenOnInterface(interfaceIp, candidates, {
  socketFactory = dgram.createSocket,
  durationMs = 3200,
  onCandidate = () => {}
} = {}) {
  return new Promise(resolve => {
    let socket;
    let finished = false;
    let timer;
    function finish() {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      try { socket?.close(); } catch { /* already closed */ }
      resolve();
    }
    try {
      socket = socketFactory("udp4");
      socket.on("error", finish);
      socket.on("message", (message, sender) => {
        if (!sender || !isLanIPv4(sender.address) || candidates.size >= MAX_CANDIDATES) return;
        const headers = parseSsdpResponse(message);
        const descriptionUrl = headers && safeDescriptionUrl(headers.location, sender.address);
        if (!descriptionUrl) return;
        const previous = candidates.get(sender.address);
        const hinted = lgHeaderHint(headers);
        if (!previous || (!previous.hinted && hinted)) {
          const entry = { ip: sender.address, url: descriptionUrl, hinted };
          candidates.set(sender.address, entry);
          onCandidate(entry);
        }
      });
      timer = setTimeout(finish, durationMs + 200);
      socket.bind(0, interfaceIp, () => {
        if (finished) return;
        try {
          socket.setMulticastInterface(interfaceIp);
          for (const st of SEARCH_TARGETS) {
            const packet = Buffer.from(
              "M-SEARCH * HTTP/1.1\r\nHOST: " + SSDP_ADDRESS + ":" + SSDP_PORT +
              "\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n"
            );
            socket.send(packet, SSDP_PORT, SSDP_ADDRESS);
          }
        } catch {
          finish();
        }
      });
    } catch {
      finish();
    }
  });
}

async function discoverLg({
  interfaces = os.networkInterfaces(),
  socketFactory = dgram.createSocket,
  readDescription = requestDescription,
  listSavedDevices = savedWebosDevices,
  durationMs = 2500,
  onDevice = () => {}
} = {}) {
  const ips = scanInterfaceIps(interfaces);
  if (!ips.length) return { devices: [], noLan: true };
  const candidates = new Map();
  const devices = new Map();
  const scheduled = new Set();
  const tasks = [];
  const enqueueDescription = queueWithLimit(4);
  const savedPromise = Promise.resolve().then(listSavedDevices).then(
    entries => Array.isArray(entries) ? entries : [], () => []
  );
  const onCandidate = entry => {
    if (scheduled.has(entry.ip)) return;
    scheduled.add(entry.ip);
    tasks.push(enqueueDescription(async () => {
      let description = null;
      try { description = parseLgDescription(await readDescription(entry.url)); } catch { /* unreachable */ }
      // Some webOS firmwares announce their identity but block the descriptor.
      // A strong, TV-specific SSDP signature is still useful for manual selection.
      if (!description && !candidates.get(entry.ip)?.hinted) return;
      const saved = await savedPromise;
      const previous = saved.find(item => item && item.host === entry.ip &&
        /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,49}$/.test(item.name));
      const device = {
        platform: "webos",
        ip: entry.ip,
        name: safeLabel(description?.name, "LG webOS TV"),
        model: safeLabel(description?.model),
        savedDeviceName: previous ? previous.name : "",
        ready: false,
        detail: previous ? "Saved LG developer device found; verify the connection" :
          "Enable Developer Mode and Key Server to pair"
      };
      if (!devices.has(device.ip)) {
        devices.set(device.ip, device);
        onDevice(device);
      }
    }));
  };
  await Promise.all(ips.map(ip => listenOnInterface(ip, candidates, { socketFactory, durationMs, onCandidate })));
  await Promise.all(tasks);
  return { devices: [...devices.values()], noLan: false };
}

function mergeDevices(groups) {
  const byIdentity = new Map();
  for (const devices of groups) {
    for (const tv of devices || []) {
      if (!tv || !["tizen", "webos"].includes(tv.platform) || !isLanIPv4(tv.ip)) continue;
      const key = tv.platform + ":" + tv.ip;
      if (!byIdentity.has(key)) byIdentity.set(key, tv);
    }
  }
  return [...byIdentity.values()].sort((a, b) =>
    a.platform.localeCompare(b.platform) || a.ip.localeCompare(b.ip, "en", { numeric: true })
  ).slice(0, 32);
}

async function discoverTvs({
  platform, samsung = discoverSamsung, lg = discoverLg,
  samsungOptions = {}, lgOptions = {}, onStatus = () => {}, onDevice = () => {}
} = {}) {
  if (!["tizen", "webos"].includes(platform)) throw new Error("Choose Samsung or LG before scanning");
  onStatus("Searching the local network for " + (platform === "tizen" ? "Samsung" : "LG") + " TVs");
  const warnings = [];
  try {
    const result = platform === "tizen"
      ? await samsung({ ...samsungOptions, onStatus, onDevice })
      : await lg({ ...lgOptions, onDevice });
    if (result.blocked) warnings.push("Samsung scan was blocked by Windows network permissions.");
    if (result.noLan) warnings.push("No private LAN interface available for TV discovery.");
    return { devices: mergeDevices([result.devices]), warnings };
  } catch (error) {
    warnings.push((platform === "tizen" ? "Samsung" : "LG") +
      " discovery unavailable: " + safeLabel(error?.message, "Scanner error", 180));
    return { devices: [], warnings };
  }
}

module.exports = {
  safeLabel, normalizeSamsungScan, discoverSamsung, discoverSamsungLan,
  samsungApiDevice, samsungDescriptionDevice, scanInterfaceIps, parseSsdpResponse,
  safeDescriptionUrl, parseLgDescription, requestDescription, privateInterfaceIps,
  listenOnInterface, discoverLg, mergeDevices, discoverTvs
};
