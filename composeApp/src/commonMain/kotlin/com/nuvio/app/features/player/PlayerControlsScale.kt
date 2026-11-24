package com.nuvio.app.features.player

import kotlin.math.roundToInt

/** Size of the player's control icons, as a percentage of the built-in size. */
const val DEFAULT_PLAYER_CONTROLS_SCALE_PERCENT = 100
const val MIN_PLAYER_CONTROLS_SCALE_PERCENT = 75
const val MAX_PLAYER_CONTROLS_SCALE_PERCENT = 160
const val PLAYER_CONTROLS_SCALE_STEP_PERCENT = 5

/** Clamps to the supported range and snaps to the slider step. */
fun normalizedPlayerControlsScalePercent(percent: Int): Int {
    val clamped = percent.coerceIn(MIN_PLAYER_CONTROLS_SCALE_PERCENT, MAX_PLAYER_CONTROLS_SCALE_PERCENT)
    val steps = (clamped.toFloat() / PLAYER_CONTROLS_SCALE_STEP_PERCENT).roundToInt()
    return (steps * PLAYER_CONTROLS_SCALE_STEP_PERCENT)
        .coerceIn(MIN_PLAYER_CONTROLS_SCALE_PERCENT, MAX_PLAYER_CONTROLS_SCALE_PERCENT)
}
