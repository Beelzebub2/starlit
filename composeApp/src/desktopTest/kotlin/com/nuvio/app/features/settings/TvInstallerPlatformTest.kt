package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.nio.file.Paths

class TvInstallerPlatformTest {
    @Test
    fun findsBundledCompanionBesideJpackageAppDirectory() {
        val home = Paths.get("Starlit", "app")
        val expected = Paths.get("Starlit", "tools", "Starlit TV Installer", "Starlit TV Installer.exe")
        assertEquals(expected, bundledTvInstallerAt(home) { it == expected })
    }

    @Test
    fun findsBundledCompanionWhenHomeIsInstallationRoot() {
        val home = Paths.get("Starlit")
        val expected = home.resolve("tools/Starlit TV Installer/Starlit TV Installer.exe")
        assertEquals(expected, bundledTvInstallerAt(home) { it == expected })
    }

    @Test
    fun findsDownloadedCompanionWhenComposeHomeIsMissing() {
        val root = Paths.get("Starlit")
        val expected = root.resolve("tools/Starlit TV Installer/Starlit TV Installer.exe")
        val config = root.resolve("app/Starlit.cfg")
        val launcher = root.resolve("Starlit.exe")
        assertEquals(
            expected,
            bundledTvInstallerAt(home = null, launcherCommand = launcher) {
                it == expected || it == config
            },
        )
    }

    @Test
    fun fallsBackToBundledRuntimeWhenProcessCommandIsUnavailable() {
        val root = Paths.get("Starlit")
        val expected = root.resolve("tools/Starlit TV Installer/Starlit TV Installer.exe")
        val config = root.resolve("app/Starlit.cfg")
        val launcher = root.resolve("Starlit.exe")
        assertEquals(
            expected,
            bundledTvInstallerAt(home = null, runtimeHome = root.resolve("runtime")) {
                it == expected || it == config || it == launcher
            },
        )
    }

    @Test
    fun ignoresUnrelatedLaunchersAndJavaRuntimes() {
        val root = Paths.get("unrelated")
        val expected = root.resolve("tools/Starlit TV Installer/Starlit TV Installer.exe")
        assertNull(
            bundledTvInstallerAt(
                home = null,
                launcherCommand = root.resolve("other.exe"),
                runtimeHome = root.resolve("jdk-21"),
            ) { it == expected },
        )
        assertNull(
            bundledTvInstallerAt(home = null, launcherCommand = root.resolve("Starlit.exe")) {
                it == expected
            },
        )
        assertNull(
            bundledTvInstallerAt(home = null, runtimeHome = root.resolve("runtime")) {
                it == expected
            },
        )
    }

    @Test
    fun doesNotSearchOutsideInstallationOrGuessOtherExecutables() {
        val home = Paths.get("Starlit", "app")
        assertNull(bundledTvInstallerAt(home) { it.toString().contains("Downloads") })
        assertNull(bundledTvInstallerAt(null))
    }
}
