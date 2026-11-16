package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable

/** Label for the keyboard shortcut that jumps to settings search, or empty where there is none. */
internal expect fun settingsSearchShortcutLabel(): String

/**
 * Calls [onTrigger] when the settings search shortcut (Ctrl K, or Cmd K on macOS) is pressed
 * while [enabled]. Only desktop has one; elsewhere this does nothing.
 */
@Composable
internal expect fun SettingsSearchShortcutEffect(enabled: Boolean, onTrigger: () -> Unit)
