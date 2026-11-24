"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { gzipSync } = require("node:zlib");
const { safeTarEntries, platformToolArchive } = require("../src/samsung.cjs");
const { parseArgs, execute } = require("../src/cli.cjs");

function packTar(entries) {
  const pieces = [];
  for (const { name, data = "", type = "0" } of entries) {
    const contents = Buffer.from(data);
    const header = Buffer.alloc(512);
    header.write(name, 0, Math.min(Buffer.byteLength(name), 100), "utf8");
    header.write("0000700\0", 100, "ascii");
    header.write("0000000\0", 108, "ascii");
    header.write("0000000\0", 116, "ascii");
    header.write(contents.length.toString(8).padStart(11, "0") + "\0", 124, "ascii");
    header.write("00000000000\0", 136, "ascii");
    header.write(type, 156, "ascii");
    pieces.push(header, contents);
    pieces.push(Buffer.alloc((512 - (contents.length % 512)) % 512));
  }
  pieces.push(Buffer.alloc(1024));
  return gzipSync(Buffer.concat(pieces));
}

test("platform CLI archives use pinned v3.3.0 digests only", () => {
  for (const platform of ["linux", "darwin"]) for (const arch of ["x64", "arm64"]) {
    const x = platformToolArchive(platform, arch);
    assert.match(x.sha256, /^[a-f0-9]{64}$/);
    assert.match(x.url, /^https:\/\/github\.com\/Apps2Samsung\/Apps2Samsung\/releases\/download\/v3\.3\.0\//);
    assert.match(x.filename, /\.tar\.gz$/);
  }
  assert.throws(() => platformToolArchive("freebsd", "x64"), /not available/);
});

test("tar extractor accepts a nested CLI, rejects traversal, links, and malformed bytes", () => {
  const archive = packTar([{ name: "publish/apps2samsung-cli", data: "binary" }, { name: "publish/config.json", data: "{}" }]);
  const entries = safeTarEntries(archive);
  assert.deepEqual(entries.map(x => x.name), ["publish/apps2samsung-cli", "publish/config.json"]);
  assert.equal(entries[0].bytes.toString(), "binary");
  const rooted = packTar([{ name: "./", type: "5" }, { name: "./apps2samsung-cli", data: "binary" }]);
  assert.deepEqual(safeTarEntries(rooted).map(x => x.name), ["apps2samsung-cli"]);
  assert.throws(() => safeTarEntries(packTar([{ name: "../", type: "5" }, { name: "apps2samsung-cli", data: "binary" }])), /Unsafe/);
  assert.throws(() => safeTarEntries(packTar([{ name: "../apps2samsung-cli", data: "evil" }])), /Unsafe/);
  assert.throws(() => safeTarEntries(packTar([{ name: "apps2samsung-cli", type: "2" }])), /Unexpected/);
  assert.throws(() => safeTarEntries(packTar([{ name: "not-an-installer", data: "data" }])), /expected executable/);
  assert.throws(() => safeTarEntries(Buffer.from("not gzip")), /gzip|header|incorrect/i);
});

test("CLI parses portable Samsung and LG operations and rejects bad/duplicate options", () => {
  assert.equal(parseArgs(["--help"]).help, true);
  assert.equal(parseArgs(["samsung", "install", "--ip", "192.168.0.13"]).platform, "tizen");
  assert.equal(parseArgs(["lg", "pair", "--ip", "192.168.0.9", "--passphrase", "abc"]).passphrase, "abc");
  assert.equal(parseArgs(["lg", "install", "--ip", "192.168.0.9", "--file", "test.ipk"]).source, undefined);
  assert.throws(() => parseArgs(["lg", "install", "--ip", "192.168.0.9", "--ip", "192.168.0.8"]), /repeated/i);
  assert.throws(() => parseArgs(["samsung", "install", "--ip", "192.168.0.13", "--passphrase", "secret"]), /LG/);
  assert.throws(() => parseArgs(["lg", "launch", "--ip", "192.168.0.9", "--file", "abc"]), /install only/);
  assert.throws(() => parseArgs(["samsung", "install", "--ip", "192.168.0.13", "--unknown", "x"]), /Unknown/);
});

test("LG install with passphrase pairs once and then installs the selected package", async () => {
  const calls = [];
  const x = parseArgs(["lg", "install", "--ip", "192.168.1.12", "--device-name", "tv", "--passphrase", "secret"]);
  await execute(x, { operate: async (request) => {
    calls.push(request);
    return request.action === "pair" ? { paired: true } : { installed: true };
  }});
  assert.equal(calls.length, 2);
  assert.deepEqual(calls.map(x => x.action), ["pair", "install"]);
  assert.equal(calls[1].platform, "webos");
  assert.equal(calls[1].target.ip, "192.168.1.12");
  assert.equal(calls[1].source, "release");
});
