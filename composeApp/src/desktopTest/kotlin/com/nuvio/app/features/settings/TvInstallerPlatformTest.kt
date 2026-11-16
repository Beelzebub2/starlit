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
    fun doesNotSearchOutsideInstallationOrGuessOtherExecutables() {
        val home = Paths.get("Starlit", "app")
        assertNull(bundledTvInstallerAt(home) { it.toString().contains("Downloads") })
        assertNull(bundledTvInstallerAt(null))
    }
}
