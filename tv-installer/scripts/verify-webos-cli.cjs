"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { createRequire } = require("node:module");

const args = process.argv.slice(2);
assert.ok(args.length === 0 || (args.length === 2 && args[0] === "--windows-bundle"));
const app = path.resolve(__dirname, "..");
const bundle = args[1] && path.resolve(args[1]);
const deps = bundle
  ? path.join(bundle, "resources", "app.asar.unpacked", "node_modules")
  : path.join(app, "node_modules");
const executable = bundle ? path.join(bundle, "Starlit TV Installer.exe") : process.execPath;
const manifest = path.join(deps, "@webos-tools", "cli", "package.json");
const cli = createRequire(manifest);
const pkg = JSON.parse(fs.readFileSync(manifest, "utf8"));
assert.equal(pkg.name, "@webos-tools/cli");
assert.equal(pkg.version, "3.2.6");
assert.equal(pkg.scripts?.postinstall, undefined);
assert.ok(fs.existsSync(path.join(path.dirname(manifest), "LICENSE")));
assert.ok(fs.existsSync(path.join(path.dirname(manifest), "STARLIT-REPACK-NOTICE.txt")));
assert.ok(!fs.existsSync(path.join(path.dirname(manifest), "npm-shrinkwrap.json")));

function installed(from, name, version) {
  let dir = path.dirname(from.resolve(name));
  for (;;) {
    const marker = path.join(dir, "package.json");
    if (fs.existsSync(marker)) {
      const found = JSON.parse(fs.readFileSync(marker, "utf8"));
      if (found.name === name) {
        assert.equal(found.version, version, marker);
        assert.ok(path.relative(deps, marker).split(path.sep)[0] !== "..", name + " resolved outside bundled dependencies");
        return createRequire(marker);
      }
    }
    const prior = dir;
    dir = path.dirname(dir);
    assert.notEqual(dir, prior, "Missing package metadata: " + name);
  }
}
const express = installed(cli, "express", "5.3.0");
const parser = installed(cli, "body-parser", "2.4.0");
installed(express, "body-parser", "2.4.0");
installed(express, "proxy-addr", "2.0.8");
installed(express, "qs", "6.16.1");
installed(parser, "qs", "6.16.1");
const glob14 = installed(installed(cli, "rimraf", "6.1.4"), "glob", "14.0.6");
installed(installed(glob14, "minimatch", "11.0.9"), "brace-expansion", "5.0.12");
for (const [name, version, rimraf] of [["fstream", "1.0.12", "2.6.3"], ["temp", "0.9.4", "2.6.3"]]) {
  const glob7 = installed(installed(installed(cli, name, version), "rimraf", rimraf), "glob", "7.2.3");
  installed(installed(glob7, "minimatch", "3.1.5"), "brace-expansion", "1.1.21");
}

const home = fs.mkdtempSync(path.join(os.tmpdir(), "starlit-lg-cli-check-"));
try {
  const commands = ["ares-setup-device", "ares-novacom", "ares-install", "ares-launch"];
  if (!bundle) commands.push("ares-package");
  for (const command of commands) {
    assert.equal(pkg.bin[command], "bin/" + command + ".js");
    const result = spawnSync(executable, [path.join(path.dirname(manifest), pkg.bin[command]), "--help"], {
      cwd: home,
      env: { ...process.env, HOME: home, USERPROFILE: home, APPDATA: home, XDG_CONFIG_HOME: home,
        ...(bundle ? { ELECTRON_RUN_AS_NODE: "1" } : {}) },
      encoding: "utf8", shell: false, windowsHide: true, timeout: 20_000, maxBuffer: 2 ** 18,
    });
    if (result.error) throw result.error;
    assert.equal(result.status, 0, command + ": " + result.stderr);
    assert.match(result.stdout || result.stderr, /usage|help/i, command);
  }
} finally {
  fs.rmSync(home, { recursive: true, force: true });
}
console.log(bundle ? "Verified packaged Windows LG dependency graph and CLI help" : "Verified LG dependency graph and CLI help");
