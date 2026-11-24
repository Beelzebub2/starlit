"use strict";
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { ensureSamsungCli } = require("../src/samsung.cjs");

async function main() {
  if (!["darwin", "linux"].includes(process.platform)) {
    throw new Error("This smoke test runs on macOS or Linux");
  }
  const workspace = await fs.mkdtemp(path.join(os.tmpdir(), "starlit-samsung-ci-"));
  try {
    const command = await ensureSamsungCli({
      toolsDir: path.join(workspace, "tools"),
      onStatus: text => process.stdout.write(text + "\n")
    });
    const result = spawnSync(command, ["--help"], {
      encoding: "utf8", timeout: 30_000, windowsHide: true,
      env: { ...process.env, DOTNET_NOLOGO: "1", DOTNET_CLI_TELEMETRY_OPTOUT: "1" }
    });
    if (result.error || result.status !== 0) {
      throw new Error("Pinned Samsung installation CLI did not run on this host");
    }
    process.stdout.write("Verified Samsung CLI runs on " + process.platform + "\n");
  } finally {
    await fs.rm(workspace, { recursive: true, force: true });
  }
}
if (require.main === module) {
  void main().catch(e => { process.stderr.write(e.message + "\n"); process.exitCode = 1; });
}
module.exports = { main };
