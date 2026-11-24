package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class AssSubtitleSizingTest {
    @Test
    fun defaultsPreserveAuthoredPositionAndStyles() {
        val result = resolveMpvSubtitleSizing("ASS", SubtitleStyleState.DEFAULT.assFontScale, "force", 80)
        assertEquals(MpvSubtitleSizing("no", 1.0, 100), result)
    }

    @Test
    fun resizesAssAndSsaWithoutMovingAuthoredPositions() {
        val scale = SubtitleStyleState.DEFAULT.copy(fontSizeSp = 27).assFontScale
        assertEquals(MpvSubtitleSizing("scale", 1.5, 100), resolveMpvSubtitleSizing("ass", scale, "force", 80))
        assertEquals(MpvSubtitleSizing("scale", 1.5, 100), resolveMpvSubtitleSizing("ssa", scale, "no", 80))
    }

    @Test
    fun customStylingIsOptInAndRestoresAuthoredSizingWhenSwitchedOff() {
        assertEquals(
            MpvSubtitleSizing("force", 1.0, 75, customAss = true),
            resolveMpvSubtitleSizing("ass", 1.5, "no", 100, customizeAss = true, customAssVerticalPosition = 75),
        )
        assertEquals(
            MpvSubtitleSizing("scale", 1.5, 100),
            resolveMpvSubtitleSizing("ass", 1.5, "no", 100, customizeAss = false, customAssVerticalPosition = 75),
        )
        assertEquals(
            MpvSubtitleSizing("no", 1.0, 100),
            resolveMpvSubtitleSizing("subrip", 1.5, "no", 100, customizeAss = true, customAssVerticalPosition = 75),
        )
    }

    @Test
    fun plainTextAndNoTrackResetAssScale() {
        assertEquals(MpvSubtitleSizing("force", 1.0, 80), resolveMpvSubtitleSizing("subrip", 1.5, "force", 80))
        assertEquals(MpvSubtitleSizing("no", 1.0, 80), resolveMpvSubtitleSizing(null, 1.5, "force", 80))
    }
}
