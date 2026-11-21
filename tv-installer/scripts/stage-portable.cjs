"use strict";
const fs = require("node:fs");
const path = require("node:path");
const { createHash } = require("node:crypto");

const PLATFORMS = Object.freeze({
  linux: Object.freeze({ label: "Linux", suffixes: [".AppImage", ".tar.gz"], arch: ["x64", "x86_64", "arm64", "aarch64"] }),
  mac: Object.freeze({ label: "macOS", suffixes: [".zip"], arch: ["x64", "arm64"] })
});

function artifactNames(files, platform, version) {
  const spec = PLATFORMS[platform];
  if (!spec) throw new Error("Unsupported TV installer preview platform");
  if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error("Invalid Starlit release version");
  const names = files.filter(name => {
    const prefix = "Starlit-TV-Installer-" + spec.label + "-";
    if (!name.startsWith(prefix)) return false;
    return spec.arch.some(arch => spec.suffixes.some(ext =>
      name === prefix + arch + "-" + version + ext));
  });
  if (!names.length) throw new Error("Missing packaged " + spec.label + " TV installer preview");
  const unique = new Set(names);
  if (unique.size !== names.length) throw new Error("Duplicate TV installer artifact");
  if (platform === "linux" && !names.some(n => n.endsWith(".AppImage")))
    throw new Error("Missing Linux AppImage");
  if (platform === "mac" && !spec.arch.every(arch =>
    names.some(name => name.startsWith("Starlit-TV-Installer-macOS-" + arch + "-")))) {
    throw new Error("Both Intel and Apple Silicon macOS packages are required");
  }
  return names.sort();
}
function stagePortable({ platform, version, directory = path.join(__dirname, "..", "dist") }) {
  const names = artifactNames(fs.readdirSync(directory), platform, version);
  const out = path.join(directory, "release");
  fs.mkdirSync(out, { recursive: true });
  const checksums = [];
  for (const name of names) {
    const from = path.join(directory, name);
    const stat = fs.statSync(from);
    if (!stat.isFile() || stat.size < 20 * 1024 * 1024) {
      throw new Error("Missing or truncated installer artifact: " + name);
    }
    fs.copyFileSync(from, path.join(out, name), fs.constants.COPYFILE_EXCL);
    checksums.push(createHash("sha256").update(fs.readFileSync(from)).digest("hex") + "  " + name);
  }
  fs.writeFileSync(path.join(out, "SHA256SUMS-TV-Installer-" + PLATFORMS[platform].label),
    checksums.join("\n") + "\n", { flag: "wx" });
  return names;
}
if (require.main === module) {
  const [, , platform, version] = process.argv;
  try {
    const names = stagePortable({ platform, version });
    process.stdout.write("Verified portable Starlit TV installer: " + names.join(", ") + "\n");
  } catch (error) {
    process.stderr.write(error.message + "\n");
    process.exitCode = 1;
  }
}
module.exports = { artifactNames, stagePortable };
