#!/usr/bin/env node
"use strict";
const path = require("node:path");
const os = require("node:os");
const { operate } = require("./installer.cjs");

const HELP = `Starlit TV Installer (Samsung Tizen / LG webOS)
Usage:
  starlit-tv-installer <samsung|lg> <pair|install|launch|uninstall> --ip <private LAN IPv4> [options]
Options:
  --device-name <name>  LG Developer Mode device profile (default: starlit-tv)
  --passphrase <secret> LG Developer Mode pairing key (never logged)
  --file <package>     Install a local, validated Starlit .wgt or .ipk instead of verified release
  --signing <mode>     Samsung certificate mode: samsung (default) or tizen-public
  --json               Emit structured status, success and failure events
  --help               Print this help text
Samsung account certificate sign-in opens in your own browser at localhost:4794.
A verified Starlit TV developer preview release is used unless --file is given.`;

function parseArgs(argv = []) {
  const args = Array.isArray(argv) ? [...argv] : [];
  if (args.length === 0 || args.includes("--help") || args.includes("-h")) return { help: true };
  const provider = args.shift();
  const action = args.shift();
  const platform = provider === "samsung" ? "tizen" : provider === "lg" ? "webos" : null;
  if (!platform || !["pair", "install", "launch", "uninstall"].includes(action)) {
    throw new Error("Specify samsung or lg, then pair, install, launch or uninstall");
  }
  const options = { platform, action, ip: "", deviceName: "starlit-tv",
    passphrase: "", filePath: "", signing: "samsung", json: false };
  const flags = new Set();
  while (args.length) {
    const key = args.shift();
    if (key === "--json") {
      if (flags.has(key)) throw new Error("Repeated option: " + key);
      options.json = true;
      flags.add(key);
      continue;
    }
    if (!["--ip", "--device-name", "--passphrase", "--file", "--signing"].includes(key) ||
        flags.has(key) || !args.length || args[0].startsWith("--")) {
      throw new Error("Unknown, repeated or incomplete command-line option");
    }
    flags.add(key);
    const value = args.shift();
    if (key === "--ip") options.ip = value;
    if (key === "--device-name") options.deviceName = value;
    if (key === "--passphrase") options.passphrase = value;
    if (key === "--file") options.filePath = path.resolve(value);
    if (key === "--signing") options.signing = value;
  }
  if (!options.ip) throw new Error("A TV LAN IP is required (--ip)");
  if (options.filePath && action !== "install") throw new Error("--file applies to install only");
  if (platform === "webos" && flags.has("--signing")) throw new Error("--signing is for Samsung only");
  if (platform === "tizen" && flags.has("--passphrase")) throw new Error("--passphrase is for LG only");
  return options;
}

async function execute(config, deps = {}) {
  const { platform, action, ip, deviceName, passphrase, filePath, signing, json } = config;
  const emit = (type, value) => {
    if (json) process.stdout.write(JSON.stringify({ type, value }) + "\n");
    else if (type === "status") process.stderr.write(String(value) + "\n");
    else process.stdout.write(type + ": " + (typeof value === "string" ? value : JSON.stringify(value)) + "\n");
  };
  const target = { ip, deviceName, passphrase };
  const samsungOps = {
    toolsDir: path.join(deps.dataDir || process.env.STARLIT_TV_INSTALLER_DATA_DIR ||
      path.join(os.homedir(), ".starlit-tv-installer"), "tools"),
    onSignInUrl: url => emit("samsung_sign_in", url)
  };
  const common = { onStatus: msg => emit("status", String(msg)), samsungOps };
  const operation = deps.operate || operate;
  if (platform === "webos" && action === "install" && passphrase) {
    await operation({ platform, action: "pair", target }, common);
  }
  const result = await operation({
    platform, action, target, signing,
    source: filePath ? "local" : "release",
    filePath
  }, common);
  emit("result", result);
  return result;
}

async function main(argv = process.argv.slice(2)) {
  try {
    const args = parseArgs(argv);
    if (args.help) {
      process.stdout.write(HELP);
      return 0;
    }
    await execute(args);
    return 0;
  } catch (error) {
    // Never reflect CLI inputs (notably pairing passphrases) in errors.
    process.stderr.write("Starlit TV installer: " + String(error?.message || "Operation failed") + "\n");
    return 1;
  }
}

if (require.main === module) {
  void main().then(code => { process.exitCode = code; });
}
module.exports = { HELP, parseArgs, execute, main };
