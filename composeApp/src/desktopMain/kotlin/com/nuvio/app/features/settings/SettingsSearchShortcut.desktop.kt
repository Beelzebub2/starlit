package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

private val isMacOs: Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

internal actual fun settingsSearchShortcutLabel(): String = if (isMacOs) "⌘K" else "Ctrl K"

@Composable
internal actual fun SettingsSearchShortcutEffect(enabled: Boolean, onTrigger: () -> Unit) {
    val currentOnTrigger by rememberUpdatedState(onTrigger)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        // A window-wide dispatcher, like the fullscreen shortcut, so the shortcut works even
        // when nothing inside the settings screen has keyboard focus yet.
        val dispatcher = KeyEventDispatcher { event ->
            if (!event.isSettingsSearchShortcut()) return@KeyEventDispatcher false
            currentOnTrigger()
            true
        }
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        manager.addKeyEventDispatcher(dispatcher)
        onDispose { manager.removeKeyEventDispatcher(dispatcher) }
    }
}

internal fun KeyEvent.isSettingsSearchShortcut(macOs: Boolean = isMacOs): Boolean {
    if (id != KeyEvent.KEY_PRESSED || keyCode != KeyEvent.VK_K) return false
    val modifiers = modifiersEx
    val required = if (macOs) KeyEvent.META_DOWN_MASK else KeyEvent.CTRL_DOWN_MASK
    val others = KeyEvent.SHIFT_DOWN_MASK or KeyEvent.ALT_DOWN_MASK or
        (if (macOs) KeyEvent.CTRL_DOWN_MASK else KeyEvent.META_DOWN_MASK)
    return modifiers and required != 0 && modifiers and others == 0
}
