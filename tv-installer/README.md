# Starlit TV Installer — Samsung Tizen and LG webOS

Windows desktop installer for managing **Starlit TV on both Samsung Tizen and LG webOS**. It installs only Starlit apps and never silently removes or replaces another TV app.

**Status: developer preview.** CI builds and tests are available, but both platforms still require actual-TV acceptance before the installer is advertised as a stable end-user release.

## Optional Windows desktop integration

The desktop app exposes **Settings → Updates → Starlit TV Installer** on Windows.
If the companion was selected during Windows setup, this opens the local tool.
Release builds download and verify a version-matched ZIP of the unpacked
developer-preview app, extracting it once during installation. Settings launches
the installed executable directly without unpacking Electron on every launch.
Setup **never runs the downloaded executable**. Otherwise Settings opens GitHub's release list
for manual installation; it does not automatically pair or sign a TV.

The Windows setup page enables the optional TV Installer (developer preview)
component whenever the package carries a version suitable for matching release
downloads. It remains **off by default**; selecting it downloads the unpacked
application archive from the matching `tv-preview-vMAJOR.MINOR.PATCH` release over HTTPS
and checks its SHA-256 against the release's `SHA256SUMS.txt`, with strict size
and time limits. The downloaded component is retained inside the Starlit
installation and can be opened from Settings. Failed or missing downloads
prevent replacing an existing installation; the setup screen offers retry or
the option can be deselected. Internet is required for the download.
Updating an existing installation retains the opt-in choice, including via
silent desktop updates. Silent *new* installations require
`--with-tv-installer` to opt in. The companion is not installed as a separate
Windows service, does not add its own autorun entry, and is removed when the
desktop app is uninstalled.

Release CI rebuilds the TV installer for each tagged Starlit version and publishes
the separately labeled **developer-preview** Setup and portable executables, plus
the SHA-256-verified unpacked-runtime ZIP used by the desktop installer, in
the matching `tv-preview-vMAJOR.MINOR.PATCH` prerelease, linked from the normal
release notes, with a matching SHA-256 checksum.
This does not mean the installer has passed hardware or dependency-security
acceptance. The desktop release may bundle the unpacked
`tv-installer/dist/win-unpacked` folder only when the repository variable
`STARLIT_TV_INSTALLER_PREVIEW_APPROVED=true` is explicitly set following security
review and real-device acceptance. A successful CI run alone must not flip this gate.
The on-demand option keeps the unverified TV tool out of **stable download
assets and the normal desktop installer payload**, while making an explicitly
selected preview available from its separate prerelease. The TV installer is
still **not** a hardware-accepted stable component.

## Run

Requires Node.js 24 and a Windows PC on the same private LAN as the TV. Run from the tv-installer directory:

    npm ci
    npm run lint
    npm test
    npm start

`npm start` and `npm run dist:win` first copy Starlit's JetBrains Sans fonts from `composeApp` into `src/fonts` (ignored by Git), so run them from a full Starlit checkout.

Switch between Samsung and LG in the same window. Each platform supports a validated local package or a checksum-verified official Starlit TV developer-preview asset; launching and uninstalling target only Starlit. Installing an update does not first uninstall the existing version.

## Find TVs automatically

Choose **Samsung** or **LG**, then select **Find TVs**. Only the selected TV platform is scanned. Devices appear as they are verified rather than waiting for the whole scan to end, so you can select a TV immediately to fill in its address. Multiple TVs are listed separately and nothing is paired, installed or removed during discovery.

- **Samsung:** Uses SSDP identity checks alongside bounded HTTP probes of the local /24 on Samsung's device-info endpoint. Non-Samsung responses are rejected; virtual network adapters are excluded when a physical LAN is available. This fast, read-only scan does not download any CLI or assume Developer Mode readiness. If no TV is found, **Try extended Samsung scan** runs the official checksum-pinned Apps2Samsung CLI with its full Developer Mode / Host PC IP diagnostics; that optional scan can take substantially longer.
- **LG:** Uses local SSDP/UPnP discovery with bounded, concurrent description checks. Verified LG results appear during discovery, not after the entire SSDP window. This does not require a webOS account or grant installation permissions.
- **Fallback:** Keep the TV switched on and the PC on the same LAN. If the TV does not appear, allow local network discovery through your Windows firewall/router, check Wi-Fi client isolation, or enter its private IPv4 address manually. VPN and guest networks can interfere with discovery.

Discovery is read-only. **Samsung Developer Mode with a matching Host PC IP** and **LG Developer Mode / Key Server pairing** remain necessary before sideloading Starlit. Selecting a result does not auto-pair, skip certificate validation or remove existing apps.

## Samsung Tizen (4.0 and newer)

1. On the TV open Apps and enter **12345** with the remote. Enable Developer Mode, enter the computer's LAN IP as **Host PC IP** and restart the TV. The installer displays the computer's private IP addresses.
2. Select **Find TVs**, choose your Samsung television and select **Check Samsung TV**. If discovery is blocked, enter its private IP manually.
3. Select a Starlit WGT file. The app ID must be **StarlitTV1.StarlitTV** with package ID **StarlitTV1**.
4. Choose the signing method. **Samsung account certificate** is recommended; if a new certificate is needed, select **Open Samsung sign-in** when the installer displays it. Sign in through the system browser on this same PC and return to Starlit; it will continue after the loopback callback arrives. Leave Starlit open for up to five minutes. If authentication fails, close any other Apps2Samsung sign-in session using local port 4794 and retry. **Tizen public certificate** avoids the Samsung account but can be refused by some firmware.
5. Select **Install or update Starlit**. The WGT is signed for the selected TV through Developer Mode, preserving the application identity and TV-local data when compatible. Keep the **same author certificate** for future updates.

Samsung support runs the official independent [Apps2Samsung CLI](https://github.com/Apps2Samsung/Apps2Samsung) **v3.3.0**, a GPL-3.0-only tool. On first Samsung action, Starlit downloads that exact release from GitHub, verifies the ZIP and executable SHA-256, and caches the CLI privately. **Starlit does not redistribute or modify the CLI.** Its GPL source, license and third-party notices are available [at v3.3.0](https://github.com/Apps2Samsung/Apps2Samsung/tree/v3.3.0). No Tizen Studio installation is needed.

The Starlit WGT is intentionally unsigned when built by CI; signing is per TV. If Developer Mode, Host PC IP or certificate checks fail, the installer provides recovery guidance and **does not override safety checks or uninstall the old app**. See the [Samsung developer installation guide](../tizen/docs/INSTALL.md).

## LG webOS (5.0 and newer)

1. Install LG's **Developer Mode** app on the TV and enable Developer Mode and Key Server.
2. Select **Find TVs** and choose the LG television, or enter its private IP manually. Confirm the saved device name and Developer Mode passphrase, then click **Pair with LG TV**.
3. Select a Starlit IPK and click **Install or update Starlit**. Its app ID is **app.starlit.webos**, distinct from Samsung and other TV apps.

LG uses the official pinned webOS CLI. Starlit does not save the Key Server passphrase. For details see the [LG webOS guide](../tizen/docs/WEBOS.md).

## Downloading and updating

The installer reads the latest stable Starlit version, then fetches that exact version's separate **`tv-preview-vMAJOR.MINOR.PATCH` developer prerelease**. It uses **Starlit-WebOS-version.ipk** for LG or **Starlit-Tizen-version.wgt** for Samsung, together with the preview's **SHA256SUMS.txt**. The package must match its expected filename, SHA-256, trusted official Starlit release URL, app identity and stable release version. If the matching preview is missing or fails verification, nothing is installed; a local developer package may be selected instead. Preview packages remain subject to real-device and dependency-security acceptance.

Updates are **manual** in the desktop installer. LG sideloads do not automatically update. Samsung Store auto-update metadata applies only after Seller Office distribution; it does not silently update Developer Mode sideloads. **Uninstall** requires explicit confirmation and may erase local app data.

## Windows portable build

    npm ci
    npm run lint
    npm test
    npm run dist:win

The dist directory contains **Starlit-TV-Installer-Setup-Windows-*.exe**, a conventional per-user Windows installer with shorter subsequent launches, and **Starlit-TV-Installer-Windows-*.exe**, the self-extracting portable version that can take longer to open on each run. Both embed the Starlit Windows executable icon. PR CI publishes **unverified** development artifacts. Electron runs the UI in a sandbox with context isolation and denied external navigation; the Samsung sign-in button can open only the pinned Apps2Samsung OAuth endpoint, and credentials are handled by the system browser, not the app. Temporary package downloads are removed after use while user-selected local files and legitimate saved signing credentials remain intact.

**Hardware validation still needed:** test LG and Samsung TV connections, signing, installation, in-place updates, D-pad navigation, media playback, audio/subtitles, account sync and standby recovery. Record the actual TV model, firmware, results and exact package checksum; CI success is not a device compatibility guarantee.

### Security and runtime limitations

The installer uses a patched Electron runtime, but the official `@webos-tools/cli` still publishes an independently shrinkwrapped dependency tree with unresolved npm advisories (including critical and high severity). Root-level npm overrides do not replace that shrinkwrap. The installer only invokes the allowlisted `ares-setup-device`, `ares-novacom`, `ares-install`, and `ares-launch` commands; it does not intentionally expose the CLI's HTTP server. This limits known entry points but **does not prove the transitive advisories unexploitable**. The stable installer remains blocked pending the dependency review and physical-device acceptance.

The CLI's `ssh2` dependency includes optional native CPU detection which cannot currently be rebuilt against the patched Electron V8 ABI. The portable installer therefore skips dependency rebuilding and relies on SSH's non-native fallback. Basic Windows packaging and unit tests do not replace a real LG pairing/install test.
