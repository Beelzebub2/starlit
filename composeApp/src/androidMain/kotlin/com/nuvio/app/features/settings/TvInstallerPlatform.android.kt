package com.nuvio.app.features.settings

internal actual object TvInstallerPlatform {
    actual val supported: Boolean = false
    actual fun isInstalled(): Boolean = false
    actual fun launch(): Boolean = false
}
