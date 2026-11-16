package com.nuvio.app.features.settings

import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val TV_INSTALLER_RELATIVE_PATH = "tools/Starlit TV Installer/Starlit TV Installer.exe"
private const val STABLE_LAUNCHER_NAME = "Starlit.exe"
private const val STABLE_APP_CONFIG = "app/Starlit.cfg"

/**
 * Windows jpackage places the launcher, app/ and runtime/ together.
 * compose.application.home is not set by every native launcher build, so
 * resolve the companion through the launcher or its bundled Java runtime too.
 * Only trusted package roots are searched, never PATH or Downloads.
 */
internal fun bundledTvInstallerAt(
    home: Path?,
    launcherCommand: Path? = null,
    runtimeHome: Path? = null,
    exists: (Path) -> Boolean = Files::isRegularFile,
): Path? {
    val launcherRoot = launcherCommand
        ?.takeIf { it.fileName?.toString().equals(STABLE_LAUNCHER_NAME, ignoreCase = true) }
        ?.parent
        ?.takeIf { exists(it.resolve(STABLE_APP_CONFIG)) }
    val runtimeRoot = runtimeHome
        ?.takeIf { it.fileName?.toString().equals("runtime", ignoreCase = true) }
        ?.parent
        ?.takeIf { exists(it.resolve(STABLE_LAUNCHER_NAME)) && exists(it.resolve(STABLE_APP_CONFIG)) }

    return sequenceOf(home, home?.parent, launcherRoot, runtimeRoot)
        .filterNotNull()
        .distinct()
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
        val launcherCommand = runCatching {
            ProcessHandle.current().info().command().orElse(null)?.let(Paths::get)
        }.getOrNull()
        val runtimeHome = runCatching {
            System.getProperty("java.home")?.let(Paths::get)
        }.getOrNull()
        return bundledTvInstallerAt(home, launcherCommand, runtimeHome)
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
