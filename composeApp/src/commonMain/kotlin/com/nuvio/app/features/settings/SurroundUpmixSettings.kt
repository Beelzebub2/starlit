package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Experimental PCM-only upmix. Never modify native multichannel sources. */
internal object SurroundUpmixSettings {
    val isSupported: Boolean get() = SurroundUpmixPlatform.isSupported

    private val enabledState = MutableStateFlow(
        if (isSupported) runCatching { SurroundUpmixPlatform.loadEnabled() }.getOrDefault(false)
        else false,
    )
    val enabled = enabledState.asStateFlow()

    // Automatic, virtual 5.1, virtual 7.1 and physical 5.1 are mutually exclusive.
    private val automaticEnabledState = MutableStateFlow(
        if (isSupported) runCatching { SurroundUpmixPlatform.loadAutomaticEnabled() }.getOrDefault(false)
        else false,
    )
    val automaticEnabled = automaticEnabledState.asStateFlow()
    private val preferAutomaticState = MutableStateFlow(
        if (isSupported) runCatching { SurroundUpmixPlatform.loadPreferAutomatic() }.getOrDefault(false)
        else false,
    )
    // Retain the existing 7.1 preference key for migration from the beta.
    private val headsetEnabledState = MutableStateFlow(
        if (isSupported) runCatching { SurroundUpmixPlatform.loadHeadsetEnabled() }.getOrDefault(false)
        else false,
    )
    val headsetEnabled = headsetEnabledState.asStateFlow()
    private val headsetFivePointOneEnabledState = MutableStateFlow(
        if (isSupported) runCatching { SurroundUpmixPlatform.loadHeadsetFivePointOneEnabled() }
            .getOrDefault(false)
        else false,
    )
    val headsetFivePointOneEnabled = headsetFivePointOneEnabledState.asStateFlow()
    private val preferHeadsetState = MutableStateFlow(
        if (isSupported) runCatching { SurroundUpmixPlatform.loadPreferHeadset() }.getOrDefault(false)
        else false,
    )
    val preferHeadset = preferHeadsetState.asStateFlow()
    private val preferHeadsetFivePointOneState = MutableStateFlow(
        if (isSupported) runCatching { SurroundUpmixPlatform.loadPreferHeadsetFivePointOne() }
            .getOrDefault(false)
        else false,
    )
    val preferHeadsetFivePointOne = preferHeadsetFivePointOneState.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        if (!isSupported) return
        if (enabled) {
            SurroundUpmixPlatform.saveAutomaticEnabled(false)
            automaticEnabledState.value = false
            SurroundUpmixPlatform.savePreferAutomatic(false)
            preferAutomaticState.value = false
            SurroundUpmixPlatform.saveHeadsetEnabled(false)
            headsetEnabledState.value = false
            SurroundUpmixPlatform.saveHeadsetFivePointOneEnabled(false)
            headsetFivePointOneEnabledState.value = false
            SurroundUpmixPlatform.savePreferHeadset(false)
            preferHeadsetState.value = false
        }
        SurroundUpmixPlatform.saveEnabled(enabled)
        enabledState.value = enabled
    }

    fun setHeadsetEnabled(enabled: Boolean) {
        if (!isSupported) return
        if (enabled) {
            SurroundUpmixPlatform.saveAutomaticEnabled(false)
            automaticEnabledState.value = false
            SurroundUpmixPlatform.savePreferAutomatic(false)
            preferAutomaticState.value = false
            SurroundUpmixPlatform.saveEnabled(false)
            enabledState.value = false
            SurroundUpmixPlatform.saveHeadsetFivePointOneEnabled(false)
            headsetFivePointOneEnabledState.value = false
            SurroundUpmixPlatform.savePreferHeadset(true)
            preferHeadsetState.value = true
            SurroundUpmixPlatform.savePreferHeadsetFivePointOne(false)
            preferHeadsetFivePointOneState.value = false
        }
        SurroundUpmixPlatform.saveHeadsetEnabled(enabled)
        headsetEnabledState.value = enabled
    }

    fun setHeadsetFivePointOneEnabled(enabled: Boolean) {
        if (!isSupported) return
        if (enabled) {
            SurroundUpmixPlatform.saveAutomaticEnabled(false)
            automaticEnabledState.value = false
            SurroundUpmixPlatform.savePreferAutomatic(false)
            preferAutomaticState.value = false
            SurroundUpmixPlatform.saveEnabled(false)
            enabledState.value = false
            SurroundUpmixPlatform.saveHeadsetEnabled(false)
            headsetEnabledState.value = false
            SurroundUpmixPlatform.savePreferHeadset(true)
            preferHeadsetState.value = true
            SurroundUpmixPlatform.savePreferHeadsetFivePointOne(true)
            preferHeadsetFivePointOneState.value = true
        }
        SurroundUpmixPlatform.saveHeadsetFivePointOneEnabled(enabled)
        headsetFivePointOneEnabledState.value = enabled
    }

    /** Explicitly opted-in auto selection; never rewrites existing manual preferences. */
    fun setAutomaticEnabled(enabled: Boolean) {
        if (!isSupported) return
        if (enabled) {
            SurroundUpmixPlatform.saveEnabled(false)
            enabledState.value = false
            SurroundUpmixPlatform.saveHeadsetEnabled(false)
            headsetEnabledState.value = false
            SurroundUpmixPlatform.saveHeadsetFivePointOneEnabled(false)
            headsetFivePointOneEnabledState.value = false
            SurroundUpmixPlatform.savePreferAutomatic(true)
            preferAutomaticState.value = true
        }
        SurroundUpmixPlatform.saveAutomaticEnabled(enabled)
        automaticEnabledState.value = enabled
    }

    /** Mode IDs are shared with Windows native policy; 4 selects automatic fallback. */
    val activeMode: Int get() = when {
        automaticEnabledState.value -> 4
        headsetFivePointOneEnabledState.value -> 3
        headsetEnabledState.value -> 2
        enabledState.value -> 1
        else -> 0
    }

    val preferredMode: Int get() = when {
        preferAutomaticState.value -> 4
        !preferHeadsetState.value -> 1
        preferHeadsetFivePointOneState.value -> 3
        else -> 2
    }

    /** In-player toggle remembers the last selected target when switched off. */
    fun setPlayerEnabled(enabled: Boolean) {
        if (!isSupported) return
        if (!enabled) {
            setEnabled(false)
            setHeadsetEnabled(false)
            setHeadsetFivePointOneEnabled(false)
            setAutomaticEnabled(false)
        } else {
            when (preferredMode) {
                4 -> setAutomaticEnabled(true)
                3 -> setHeadsetFivePointOneEnabled(true)
                2 -> setHeadsetEnabled(true)
                else -> setEnabled(true)
            }
        }
    }
}

internal expect object SurroundUpmixPlatform {
    val isSupported: Boolean
    fun loadAutomaticEnabled(): Boolean
    fun saveAutomaticEnabled(enabled: Boolean)
    fun loadPreferAutomatic(): Boolean
    fun savePreferAutomatic(prefer: Boolean)
    fun loadEnabled(): Boolean
    fun saveEnabled(enabled: Boolean)
    fun loadHeadsetEnabled(): Boolean
    fun saveHeadsetEnabled(enabled: Boolean)
    fun loadHeadsetFivePointOneEnabled(): Boolean
    fun saveHeadsetFivePointOneEnabled(enabled: Boolean)
    fun loadPreferHeadset(): Boolean
    fun savePreferHeadset(prefer: Boolean)
    fun loadPreferHeadsetFivePointOne(): Boolean
    fun savePreferHeadsetFivePointOne(prefer: Boolean)
}
