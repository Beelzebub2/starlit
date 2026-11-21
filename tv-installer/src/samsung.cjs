"use strict";
const fs = require("node:fs/promises");
const path = require("node:path");
const os = require("node:os");
const { spawn } = require("node:child_process");
const { createHash } = require("node:crypto");
const { gunzipSync } = require("node:zlib");
const JSZip = require("jszip");
const { platformInfo, isLanIPv4 } = require("./platforms.cjs");
const { inspectWgt, MAX_WGT } = require("./wgt.cjs");
const { fetchBounded, fetchVerifiedRelease } = require("./releases.cjs");
const { extractSamsungSignInUrl } = require("./samsung-auth.cjs");

// Pinned independent GPL-3.0-only tool. Starlit does not redistribute it:
// the user initiates downloading the verified upstream release on first use.
const VERSION = "v3.3.0";
const DOWNLOAD_URL = "https://github.com/Apps2Samsung/Apps2Samsung/releases/download/v3.3.0/Apps2Samsung.Cli-v3.3.0-win-x64.zip";
const ZIP_SHA256 = "68b9d032d8ea3f96a3e8cb4f47abfb8fb5b5e0cb712625f946a58aeb8f105593";
const EXE_SHA256 = "9f16d0f699c68843ee4fdc72b8614f4a6d6eeab95a1dadde894eba952e6f9b9e";
const TOOL_FILE = "apps2samsung-cli.exe";
const UPSTREAM_RELEASE = "https://github.com/Apps2Samsung/Apps2Samsung/releases/download/" + VERSION + "/";
const MAX_ARCHIVE_BYTES = 45 * 1024 * 1024;
const MAX_EXTRACTED_BYTES = 256 * 1024 * 1024;
// Official GitHub Release asset digests; this GPL-3-only program is fetched on
// demand and is NOT redistributed inside Starlit's proprietary packages.
const PLATFORM_ARCHIVES = Object.freeze({
  "linux-x64": Object.freeze({ filename: "Apps2Samsung.Cli-v3.3.0-linux-x64.tar.gz", sha256: "a5ac49562c311cc5665f717480ef12952a0fa8eb87912ae430b83495499ed0b8" }),
  "linux-arm64": Object.freeze({ filename: "Apps2Samsung.Cli-v3.3.0-linux-arm64.tar.gz", sha256: "03372a88781828126f9084b48e949bb3314eaac972c95cdd1fd89f8adc3176fa" }),
  "darwin-x64": Object.freeze({ filename: "Apps2Samsung.Cli-v3.3.0-osx-x64.tar.gz", sha256: "39ee3a5d4b0b6c3da46a3821abc2cbeaf39dd827255c42a8d4ef4144a0ab86fc" }),
  "darwin-arm64": Object.freeze({ filename: "Apps2Samsung.Cli-v3.3.0-osx-arm64.tar.gz", sha256: "d4f4c7cfea4db4c51edd81a287c4302d90a5dbb9091b21a960f3f974225b6035" })
});
function platformToolArchive(platform = process.platform, architecture = process.arch) {
  const spec = PLATFORM_ARCHIVES[platform + "-" + architecture];
  if (!spec) throw new Error("The verified Apps2Samsung CLI is not available for this OS/CPU");
  return { ...spec, url: UPSTREAM_RELEASE + spec.filename };
}
function safeTarEntries(archive) {
  const tar = gunzipSync(archive, { maxOutputLength: MAX_EXTRACTED_BYTES });
  const entries = [];
  let cursor = 0;
  let paxPath = "";
  while (cursor + 512 <= tar.length) {
    const header = tar.subarray(cursor, cursor + 512);
    if (header.every(b => b === 0)) break;
    const field = (start, len) => header.toString("utf8", start, start + len).split("\0")[0];
    const rawSize = field(124, 12).trim();
    if (!/^[0-7]+$/.test(rawSize)) throw new Error("Invalid Samsung CLI TAR entry size");
    const size = parseInt(rawSize, 8);
    if (!Number.isSafeInteger(size) || size > MAX_EXTRACTED_BYTES) throw new Error("Oversized TAR entry");
    const type = header[156];
    let name = paxPath || [field(345, 155), field(0, 100)].filter(Boolean).join("/");
    paxPath = "";
    const padded = Math.ceil(size / 512) * 512;
    const end = cursor + 512 + size;
    if (end > tar.length || cursor + 512 + padded > tar.length) throw new Error("Truncated Samsung CLI archive");
    const bytes = tar.subarray(cursor + 512, end);
    if (type === 120) {
      // PAX metadata can carry a long path for the following archive entry.
      const meta = bytes.toString("utf8");
      const match = /(?:^|\n)\d+ path=([^\n]+)\n/.exec(meta);
      paxPath = match?.[1] || "";
    } else if (type === 0 || type === 48 || type === 53) {
      name = name.replace(/^\.\//, "").replace(/\/$/, "");
      if (!name || name.startsWith("/") || name.includes("\\") ||
          name.split("/").some(part => part === ".." || part === "")) {
        throw new Error("Unsafe Samsung CLI archive entry");
      }
      entries.push({ name, bytes, directory: type === 53 });
    } else {
      // Never extract symlinks, hardlinks, devices or executable scripts from
      // an untrusted archive, even if the outer asset checksum is pinned.
      throw new Error("Unexpected Samsung CLI archive entry type");
    }
    cursor += 512 + padded;
  }
  if (!entries.length || !entries.some(entry => !entry.directory &&
    path.posix.basename(entry.name).toLowerCase() === "apps2samsung-cli")) {
    throw new Error("Samsung CLI archive does not contain its expected executable");
  }
  return entries;
}
async function ensureUnixSamsungCli({ toolsDir, platform = process.platform, architecture = process.arch,
  fetchImpl = globalThis.fetch, onStatus = () => {}
} = {}) {
  const spec = platformToolArchive(platform, architecture);
  const directory = path.join(toolsDir, "apps2samsung", VERSION, platform + "-" + architecture);
  const marker = path.join(directory, ".verified.json");
  try {
    const metadata = JSON.parse(await fs.readFile(marker, "utf8"));
    const exePath = path.resolve(directory, metadata.executable || "");
    if (!exePath.startsWith(directory + path.sep) || !metadata.sha256 ||
        !/^[a-f0-9]{64}$/.test(metadata.sha256) || metadata.archiveSha256 !== spec.sha256) {
      throw new Error("Invalid cached Samsung tool metadata");
    }
    const stat = await fs.lstat(exePath);
    if (!stat.isFile() || stat.isSymbolicLink() ||
        CHECKSUM(await fs.readFile(exePath)) !== metadata.sha256) {
      throw new Error("Cached Samsung tool failed integrity verification");
    }
    return exePath;
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  onStatus("Downloading the verified Samsung installation CLI for this OS (first use)");
  const bytes = await fetchBounded(fetchImpl, spec.url, MAX_ARCHIVE_BYTES, 120000);
  if (CHECKSUM(bytes) !== spec.sha256) throw new Error("Official Samsung tool asset checksum mismatch");
  const entries = safeTarEntries(bytes);
  await fs.mkdir(path.dirname(directory), { recursive: true });
  const stage = await fs.mkdtemp(path.join(path.dirname(directory), "starlit-samsung-"));
  try {
    for (const entry of entries) {
      const destination = path.resolve(stage, entry.name);
      if (!destination.startsWith(stage + path.sep)) throw new Error("Unsafe Samsung CLI path");
      if (entry.directory) {
        await fs.mkdir(destination, { recursive: true });
      } else {
        await fs.mkdir(path.dirname(destination), { recursive: true });
        await fs.writeFile(destination, entry.bytes, { flag: "wx", mode: 0o700 });
      }
    }
    const entry = entries.find(item => !item.directory &&
      path.posix.basename(item.name).toLowerCase() === "apps2samsung-cli");
    const executable = path.resolve(stage, entry.name);
    await fs.chmod(executable, 0o700);
    await fs.writeFile(path.join(stage, ".verified.json"), JSON.stringify({
      archiveSha256: spec.sha256, executable: entry.name, sha256: CHECKSUM(entry.bytes)
    }), { flag: "wx", mode: 0o600 });
    await fs.rename(stage, directory); // Never replace an existing cache.
  } catch (error) {
    await fs.rm(stage, { recursive: true, force: true });
    throw error;
  }
  onStatus("Verified Apps2Samsung " + VERSION + " is ready for " + platform);
  return path.resolve(directory, entries.find(item => !item.directory &&
    path.posix.basename(item.name).toLowerCase() === "apps2samsung-cli").name);
}
const CHECKSUM = data => createHash("sha256").update(data).digest("hex");

function validateSamsungTarget(target = {}) {
  if (!isLanIPv4(target.ip)) throw new Error("Enter your Samsung TV's private LAN IPv4 address");
  return { ip: target.ip };
}

function validateSigning(value) {
  if (value === "samsung" || value === "tizen-public") return value;
  throw new Error("Choose a Samsung account certificate or a Tizen public signing certificate");
}

async function ensureSamsungCli({
  toolsDir, fetchImpl = globalThis.fetch, onStatus = () => {}
} = {}) {
  if (!path.isAbsolute(String(toolsDir || ""))) throw new Error("Missing private Samsung tools directory");
  if (process.platform !== "win32") {
    return ensureUnixSamsungCli({ toolsDir, fetchImpl, onStatus });
  }
  const directory = path.join(toolsDir, "apps2samsung", VERSION);
  const destination = path.join(directory, TOOL_FILE);
  try {
    const existing = await fs.readFile(destination);
    if (CHECKSUM(existing) !== EXE_SHA256) {
      throw new Error("Cached Samsung installation tool failed its integrity check; remove the invalid tool before retrying");
    }
    return destination;
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  onStatus("Downloading the official Samsung installation tool (first use only)");
  const zipBytes = await fetchBounded(fetchImpl, DOWNLOAD_URL, 40 * 1024 * 1024, 120000);
  if (CHECKSUM(zipBytes) !== ZIP_SHA256) {
    throw new Error("Samsung tool checksum did not match the pinned official release");
  }
  const archive = await JSZip.loadAsync(zipBytes);
  const entry = archive.file(TOOL_FILE);
  if (!entry || Number(entry._data?.uncompressedSize) !== 83338359) {
    throw new Error("Samsung installation tool archive has unexpected contents");
  }
  const executable = await entry.async("nodebuffer");
  if (CHECKSUM(executable) !== EXE_SHA256) {
    throw new Error("Samsung installation tool executable failed SHA-256 verification");
  }
  await fs.mkdir(directory, { recursive: true });
  const temporary = await fs.mkdtemp(path.join(directory, "download-"));
  try {
    const tempFile = path.join(temporary, TOOL_FILE);
    await fs.writeFile(tempFile, executable, { flag: "wx", mode: 0o700 });
    // Never replace an unexpected existing tool. The checksum is checked on every use.
    await fs.rename(tempFile, destination);
  } finally {
    await fs.rm(temporary, { recursive: true, force: true });
  }
  onStatus("Verified Apps2Samsung " + VERSION + " is ready");
  return destination;
}

function cliFailure(exitCode, output) {
  const text = String(output || "");
  if (exitCode === 3) {
    return new Error("The TV failed a Samsung pre-install check. Enable Developer Mode, set Host PC IP to this computer's LAN IP, reboot the TV and retry without bypassing safety checks.");
  }
  if (/port\s+4794\s+is in use/i.test(text)) {
    return new Error("Samsung sign-in cannot receive the browser response because port 4794 is already in use. Close another Apps2Samsung sign-in session and retry.");
  }
  if (/no sign-in arrived within|sign.in.*timed out|timed out.*sign.in/i.test(text)) {
    return new Error("Samsung account sign-in timed out. Finish signing in through the browser on this PC, then retry. The login window is valid for five minutes.");
  }
  if (/certificate|sign-in|signing|authorization|login/i.test(text)) {
    return new Error("Samsung signing did not complete. Check the Samsung account sign-in in your browser, then retry. Keep the same certificate for future updates.");
  }
  if (/refused|timeout|unreachable|could not connect|network/i.test(text)) {
    return new Error("Could not reach the Samsung TV. Check the TV's IP, Developer Mode, Host PC IP and local network.");
  }
  return new Error("Samsung TV command failed (exit " + exitCode + "). Check the TV's Developer Mode connection and the Apps2Samsung certificate settings.");
}

async function runSamsungCli(executable, args, {
  timeoutMs = 420000, onStatus = () => {}, onSignInUrl = () => {},
  spawnImpl = spawn
} = {}) {
  if (!path.isAbsolute(executable) || !Array.isArray(args) || args.some(a => typeof a !== "string")) {
    throw new Error("Invalid Samsung CLI invocation");
  }
  return new Promise((resolve, reject) => {
    const child = spawnImpl(executable, [...args, "--json", "--plain"], {
      shell: false, windowsHide: true, stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env, NO_COLOR: "1" }
    });
    let stdout = "", stderr = "", finished = false, pendingProgress = "";
    let signInShown = false;
    const handleLine = line => {
      const url = extractSamsungSignInUrl(line);
      if (url && !signInShown) {
        signInShown = true;
        onSignInUrl(url);
        onStatus("Samsung sign-in is ready. Use Open Samsung sign-in in this installer, then return when finished.");
        return;
      }
      if (/a samsung account sign-in is needed|open this address in a browser/i.test(line)) {
        onStatus("A Samsung account sign-in is needed to create the certificate.");
      } else if (/creating.*certificate|signing.*package|connecting.*tv|installing.*package/i.test(line)) {
        onStatus(line.trim().slice(0, 240));
      }
    };
    function end(error, result) {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      if (error) reject(error);
      else resolve(result);
    }
    const timer = setTimeout(() => {
      child.kill();
      end(new Error("Samsung installation took too long. Check the TV and certificate sign-in before retrying."));
    }, timeoutMs);
    child.stdout.on("data", data => { stdout = (stdout + data).slice(-262144); });
    child.stderr.on("data", data => {
      const chunk = String(data);
      stderr = (stderr + chunk).slice(-262144);
      pendingProgress += chunk;
      const lines = pendingProgress.split(/\r?\n/);
      pendingProgress = lines.pop().slice(-4096);
      for (const line of lines) handleLine(line);
    });
    child.once("error", err => end(new Error("Could not start the Samsung installer: " + err.message)));
    child.once("close", code => {
      if (pendingProgress) handleLine(pendingProgress);
      if (code !== 0) return end(cliFailure(code, stderr + "\n" + stdout));
      let parsed = {};
      try { parsed = JSON.parse(stdout.trim()); } catch { /* CLI versions may produce text. */ }
      end(null, parsed);
    });
  });
}

async function operateSamsung({
  action, target, source = "release", filePath = "", signing = "samsung"
} = {}, {
  run = runSamsungCli, ensureTool = ensureSamsungCli, fetchRelease = fetchVerifiedRelease,
  toolsDir = path.join(process.env.APPDATA || os.homedir(), "Starlit TV Installer", "tools"),
  onStatus = () => {}, onSignInUrl = () => {}
} = {}) {
  const { ip } = validateSamsungTarget(target);
  if (!["pair", "install", "launch", "uninstall"].includes(action)) {
    throw new Error("Unsupported Samsung TV operation");
  }
  const executable = await ensureTool({ toolsDir, onStatus });
  const cliOptions = { onStatus, onSignInUrl };
  if (action === "pair") {
    onStatus("Checking Samsung TV Developer Mode and connection");
    await run(executable, ["tv", ip], cliOptions);
    return { connected: true, platform: "tizen" };
  }
  const appId = platformInfo("tizen").appId;
  if (action === "launch") {
    await run(executable, ["launch", appId, "--tv", ip], cliOptions);
    return { launched: true, appId };
  }
  if (action === "uninstall") {
    await run(executable, ["uninstall", appId, "--tv", ip], cliOptions);
    return { uninstalled: true, appId };
  }
  validateSigning(signing);
  if (!["release", "local"].includes(source)) throw new Error("Choose a verified release or a local WGT");
  let packagePath = filePath, temporary = "";
  try {
    if (source === "release") {
      const download = await fetchRelease({ platform: "tizen", onStatus });
      temporary = await fs.mkdtemp(path.join(os.tmpdir(), "starlit-tizen-install-"));
      packagePath = path.join(temporary, download.filename);
      await fs.writeFile(packagePath, download.bytes, { flag: "wx", mode: 0o600 });
      onStatus("The published Tizen WGT checksum is verified");
    } else if (!path.isAbsolute(String(filePath)) || path.extname(filePath).toLowerCase() !== ".wgt") {
      throw new Error("Choose a local Starlit .wgt package");
    }
    const stat = await fs.stat(packagePath);
    if (!stat.isFile() || stat.size <= 0 || stat.size > MAX_WGT) throw new Error("Invalid or oversized Samsung WGT");
    const manifest = await inspectWgt(await fs.readFile(packagePath));
    onStatus(signing === "samsung"
      ? "Signing Starlit " + manifest.version + " with Samsung. If sign-in is required, a button will appear here."
      : "Signing Starlit " + manifest.version + " with a Tizen public certificate.");
    const args = ["install", packagePath, "--tv", ip];
    if (signing === "samsung") args.push("--samsung-cert", "--no-browser");
    else args.push("--tizen-cert", "public");
    // Deliberately omit --yes, --allow-remove, --remove-first and --new-id.
    // Updates must keep the same author certificate and application identity.
    const outcome = await run(executable, args, cliOptions);
    if (outcome && outcome.via === "tvstore") {
      return { pendingTvConfirmation: true, version: manifest.version, appId };
    }
    return { installed: true, version: manifest.version, appId };
  } finally {
    if (temporary) await fs.rm(temporary, { recursive: true, force: true });
  }
}
module.exports = {
  VERSION, ZIP_SHA256, EXE_SHA256, PLATFORM_ARCHIVES, platformToolArchive, safeTarEntries,
  validateSamsungTarget, validateSigning,
  ensureSamsungCli, runSamsungCli, operateSamsung, cliFailure
};
