package com.nuvio.app.features.settings

import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val TV_INSTALLER_RELATIVE_PATH = "tools/Starlit TV Installer/Starlit TV Installer.exe"

/**
 * jpackage supplies compose.application.home as <install>/app on Windows.
 * Also check <install> when a custom desktop runtime supplies the app root.
 * Never search PATH, Downloads or arbitrary environment-controlled locations.
 */
internal fun bundledTvInstallerAt(home: Path?, exists: (Path) -> Boolean = Files::isRegularFile): Path? {
    if (home == null) return null
    return sequenceOf(home, home.parent)
        .filterNotNull()
        .map { it.resolve(TV_INSTALLER_RELATIVE_PATH) }
        .firstOrNull(exists)
}

internal actual object TvInstallerPlatform {
    actual val supported: Boolean = DesktopHostOs.current == DesktopHostOs.WINDOWS

    private fun executable(): Path? {
        if (!supported) return null
        val home = runCatching {
            System.getProperty("compose.application.home")?.let(Paths::get)
        }.getOrNull()
        return bundledTvInstallerAt(home)
    }

    actual fun isInstalled(): Boolean = executable() != null

    actual fun launch(): Boolean {
        val exe = executable() ?: return false
        return runCatching {
            ProcessBuilder(exe.toString()).directory(exe.parent.toFile()).start()
            true
        }.getOrDefault(false)
    }
}
