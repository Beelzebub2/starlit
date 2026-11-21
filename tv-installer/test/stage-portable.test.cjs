"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { artifactNames } = require("../scripts/stage-portable.cjs");

test("installer previews are selected by exact version and supported platform", () => {
  const files = [
    "Starlit-TV-Installer-Linux-x86_64-1.33.0.AppImage",
    "Starlit-TV-Installer-Linux-x64-1.33.0.tar.gz",
    "Starlit-TV-Installer-Linux-x86_64-1.32.0.AppImage",
    "Starlit-TV-Installer-macOS-x64-1.33.0.zip",
    "Starlit-TV-Installer-macOS-arm64-1.33.0.zip",
    "Starlit-TV-Installer-Setup-Windows-1.33.0.exe",
    "untrusted-installer-1.33.0.zip",
  ];
  assert.deepEqual(artifactNames(files, "linux", "1.33.0"), files.slice(0, 2));
  assert.deepEqual(artifactNames(files, "mac", "1.33.0"), files.slice(3, 5).sort());
  assert.throws(() => artifactNames(files, "win", "1.33.0"), /Unsupported/);
  assert.throws(() => artifactNames(files, "mac", "../1.33.0"), /Invalid/);
});

test("missing required macOS architectures or Linux AppImage blocks publication", () => {
  assert.throws(() => artifactNames(["Starlit-TV-Installer-macOS-x64-1.33.0.zip"], "mac", "1.33.0"), /both/i);
  assert.throws(() => artifactNames(["Starlit-TV-Installer-Linux-x64-1.33.0.tar.gz"], "linux", "1.33.0"), /AppImage/);
  assert.throws(() => artifactNames([], "linux", "1.33.0"), /Missing packaged/);
});
