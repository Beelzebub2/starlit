package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerControlsScaleTest {
    @Test
    fun keepsSupportedSteps() {
        assertEquals(100, normalizedPlayerControlsScalePercent(100))
        assertEquals(125, normalizedPlayerControlsScalePercent(125))
    }

    @Test
    fun clampsToSupportedRange() {
        assertEquals(MIN_PLAYER_CONTROLS_SCALE_PERCENT, normalizedPlayerControlsScalePercent(10))
        assertEquals(MAX_PLAYER_CONTROLS_SCALE_PERCENT, normalizedPlayerControlsScalePercent(400))
    }

    @Test
    fun snapsSyncedValuesToTheSliderStep() {
        assertEquals(110, normalizedPlayerControlsScalePercent(112))
        assertEquals(115, normalizedPlayerControlsScalePercent(113))
    }
}
