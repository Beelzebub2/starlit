"use strict";
const fs = require("node:fs/promises");
const path = require("node:path");
const os = require("node:os");
const { spawn } = require("node:child_process");
const { createHash } = require("node:crypto");
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
  if (process.platform !== "win32") {
    throw new Error("The bundled Starlit Samsung installer currently supports Windows");
  }
  if (!path.isAbsolute(String(toolsDir || ""))) throw new Error("Missing private Samsung tools directory");
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
  VERSION, ZIP_SHA256, EXE_SHA256, validateSamsungTarget, validateSigning,
  ensureSamsungCli, runSamsungCli, operateSamsung, cliFailure
};
