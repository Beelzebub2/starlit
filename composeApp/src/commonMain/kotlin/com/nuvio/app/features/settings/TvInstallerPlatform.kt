package com.nuvio.app.features.settings

/** The optional desktop companion is never downloaded or executed without user action. */
internal expect object TvInstallerPlatform {
    val supported: Boolean
    fun isInstalled(): Boolean
    fun launch(): Boolean
}
