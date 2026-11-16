package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage

internal actual object SurroundUpmixPlatform {
    private val store by lazy { DesktopStorage.store("starlit_surround_upmix") }
    private const val key = "surround_upmix_windows_beta_enabled_v1"
    private const val headsetKey = "surround_upmix_windows_virtual_headset_7_1_v1"
    private const val headsetFivePointOneKey = "surround_upmix_windows_virtual_headset_5_1_v1"
    private const val preferredKey = "surround_upmix_windows_last_headset_target_v1"
    private const val preferredFivePointOneKey = "surround_upmix_windows_last_headset_5_1_v1"
    private const val automaticKey = "surround_upmix_windows_automatic_v1"
    private const val preferredAutomaticKey = "surround_upmix_windows_last_automatic_v1"

    actual val isSupported: Boolean =
        System.getProperty("os.name", "").startsWith("windows", ignoreCase = true) &&
            System.getProperty("os.arch", "").lowercase() in setOf("amd64", "x64", "x86_64")

    actual fun loadEnabled(): Boolean = store.getBoolean(key) ?: false

    actual fun loadAutomaticEnabled(): Boolean = store.getBoolean(automaticKey) ?: false

    actual fun saveAutomaticEnabled(enabled: Boolean) {
        store.putBoolean(automaticKey, enabled)
    }

    actual fun loadPreferAutomatic(): Boolean = store.getBoolean(preferredAutomaticKey) ?: false

    actual fun savePreferAutomatic(prefer: Boolean) {
        store.putBoolean(preferredAutomaticKey, prefer)
    }

    actual fun saveEnabled(enabled: Boolean) {
        store.putBoolean(key, enabled)
    }

    actual fun loadHeadsetEnabled(): Boolean = store.getBoolean(headsetKey) ?: false

    actual fun saveHeadsetEnabled(enabled: Boolean) {
        store.putBoolean(headsetKey, enabled)
    }

    actual fun loadHeadsetFivePointOneEnabled(): Boolean =
        store.getBoolean(headsetFivePointOneKey) ?: false

    actual fun saveHeadsetFivePointOneEnabled(enabled: Boolean) {
        store.putBoolean(headsetFivePointOneKey, enabled)
    }

    actual fun loadPreferHeadset(): Boolean = store.getBoolean(preferredKey) ?: false

    actual fun savePreferHeadset(prefer: Boolean) {
        store.putBoolean(preferredKey, prefer)
    }

    actual fun loadPreferHeadsetFivePointOne(): Boolean =
        store.getBoolean(preferredFivePointOneKey) ?: false

    actual fun savePreferHeadsetFivePointOne(prefer: Boolean) {
        store.putBoolean(preferredFivePointOneKey, prefer)
    }
}
