import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { crc32, gunzipSync } from "node:zlib";

const upstreamUrl = "https://registry.npmjs.org/@webos-tools/cli/-/cli-3.2.6.tgz";
const upstreamLength = 5_901_117;
const upstreamSha256 = "721147485138b42f2c955fb25d2ba4045b5494c9da463bd69431ae84ff432543";
const upstreamSha512 = "VkCV8oBycgd0UTT3n8xOCUKjzIFaJM5MzJWr9BqmHfplUemtMsTBx+1GNOyxio6Ky1mj/iyqAUx4VcUy2nQu3Q==";
const repackSha256 = "f7dee9a078acdf1d437c070d35a4faa3eec62685c6a374c9370f94d0f9f88ec4";
const output = new URL("../.generated/webos-cli-3.2.6-starlit.1.tgz", import.meta.url);
const updates = { "body-parser": "2.4.0", express: "5.3.0", rimraf: "6.1.4" };
const notice = Buffer.from(
  "Starlit qualification repack of LG webos-tools/cli ed7a6994be443207f3427b19f210f6606f004eb6.\n" +
  "Apache-2.0 license and existing third-party notices preserved.\n" +
  "Removed the upstream nested shrinkwrap and its POSIX chmod postinstall hook.\n" +
  "Changed package metadata to use patched dependency versions; see the Starlit installer lock.\n" +
  "This internally generated archive is not an official LG distribution.\n"
);

const hash = (algorithm, value, encoding = "hex") => createHash(algorithm).update(value).digest(encoding);
const aligned = size => Math.ceil(size / 512) * 512;
const field = (block, start, end) => block.subarray(start, end).toString("utf8").split("\0")[0];

async function upstreamBytes(sourcePath) {
  if (sourcePath) return await readFile(sourcePath);
  const response = await fetch(upstreamUrl, { redirect: "error", signal: AbortSignal.timeout(30_000) });
  if (!response.ok || !response.body) throw new Error(`LG source download failed: ${response.status}`);
  const parts = [];
  let length = 0;
  for await (const chunk of response.body) {
    length += chunk.length;
    if (length > upstreamLength) throw new Error("LG source archive exceeded its pinned size");
    parts.push(chunk);
  }
  return Buffer.concat(parts, length);
}

function entry(name, content, inputHeader) {
  const header = inputHeader ? Buffer.from(inputHeader) : Buffer.alloc(512);
  if (!inputHeader) {
    header.write(name);
    header.write("0000644\0", 100);
    header.write("0000000\0", 108);
    header.write("0000000\0", 116);
    header.write("00000000000\0", 136);
    header.write("0", 156);
    header.write("ustar\0", 257);
    header.write("00", 263);
  }
  header.write(content.length.toString(8).padStart(11, "0") + "\0", 124);
  header.fill(32, 148, 156);
  const sum = header.reduce((a, b) => a + b, 0);
  header.write(sum.toString(8).padStart(6, "0") + "\0 ", 148);
  return Buffer.concat([header, content, Buffer.alloc(aligned(content.length) - content.length)]);
}

function transform(gz) {
  assert.equal(gz.length, upstreamLength, "LG source size changed");
  assert.equal(hash("sha256", gz), upstreamSha256, "LG source checksum changed");
  assert.equal(hash("sha512", gz, "base64"), upstreamSha512, "LG source npm integrity changed");
  const input = gunzipSync(gz, { maxOutputLength: 40 * 1024 * 1024 });
  const output = [];
  let manifest = false, shrinkwrap = false, license = false;
  let offset = 0;
  for (; offset + 512 <= input.length; ) {
    const block = input.subarray(offset, offset + 512);
    if (block.every(b => b === 0)) break;
    const name = field(block, 0, 100);
    const sizeText = field(block, 124, 136).trim();
    assert.match(sizeText, /^[0-7]+$/);
    const size = Number.parseInt(sizeText, 8);
    const end = offset + 512 + aligned(size);
    assert.ok(end <= input.length);
    const data = input.subarray(offset + 512, offset + 512 + size);
    const type = field(block, 156, 157);
    assert.ok(type === "0" || (type === "x" && name === "PaxHeader/LicenseRef-3rd_party_licenses.txt"));
    if (name === "package/package.json") {
      assert.equal(manifest, false);
      manifest = true;
      const pkg = JSON.parse(data.toString("utf8"));
      assert.equal(pkg.name, "@webos-tools/cli");
      assert.equal(pkg.version, "3.2.6");
      assert.equal(pkg.license, "Apache-2.0");
      for (const name of ["ares-setup-device", "ares-novacom", "ares-install", "ares-launch", "ares-package"]) {
        assert.equal(pkg.bin[name], `bin/${name}.js`);
      }
      assert.deepEqual(Object.keys(pkg.scripts), ["postinstall"]);
      delete pkg.scripts;
      Object.assign(pkg.dependencies, updates);
      output.push(entry(name, Buffer.from(JSON.stringify(pkg, null, 2) + "\n"), block));
    } else if (name === "package/npm-shrinkwrap.json") {
      assert.equal(shrinkwrap, false);
      shrinkwrap = true;
    } else {
      if (name === "package/LICENSE") license = true;
      output.push(input.subarray(offset, end));
    }
    offset = end;
  }
  assert.ok(manifest && shrinkwrap && license && input.length - offset >= 1024);
  assert.ok(input.subarray(offset).every(b => b === 0));
  output.push(entry("package/STARLIT-REPACK-NOTICE.txt", notice), Buffer.alloc(1024));
  return Buffer.concat(output);
}

// Stored DEFLATE blocks keep the lock's archive bytes identical across OS/zlib builds.
// The larger generated file is ignored by Git and never shipped as an extra archive.
function deterministicGzip(tar) {
  const bytes = [Buffer.from([0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 255])];
  for (let offset = 0; offset < tar.length; offset += 65_535) {
    const part = tar.subarray(offset, offset + 65_535);
    const header = Buffer.alloc(5);
    header[0] = offset + part.length === tar.length ? 1 : 0;
    header.writeUInt16LE(part.length, 1);
    header.writeUInt16LE(part.length ^ 0xffff, 3);
    bytes.push(header, part);
  }
  const footer = Buffer.alloc(8);
  footer.writeUInt32LE(crc32(tar), 0);
  footer.writeUInt32LE(tar.length, 4);
  return Buffer.concat([...bytes, footer]);
}

const args = process.argv.slice(2);
assert.ok(args.length === 0 || (args.length === 2 && args[0] === "--source"), "Usage: prepare-webos-cli.mjs [--source pinned.tgz]");
const existing = await readFile(output).catch(error => {
  if (error.code !== "ENOENT") throw error;
  return null;
});
if (existing) {
  assert.equal(hash("sha256", existing), repackSha256, "Invalid generated LG archive; remove it and prepare again");
} else {
  const repack = deterministicGzip(transform(await upstreamBytes(args[1])));
  assert.equal(hash("sha256", repack), repackSha256, "Rebuilt LG package checksum changed");
  await mkdir(new URL("./", output), { recursive: true });
  await writeFile(output, repack, { flag: "wx" });
}
console.log(`Verified generated LG CLI: ${fileURLToPath(output)} (${repackSha256})`);
