package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable

internal actual fun settingsSearchShortcutLabel(): String = ""

@Composable
internal actual fun SettingsSearchShortcutEffect(enabled: Boolean, onTrigger: () -> Unit) = Unit
