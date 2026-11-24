package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackLabelPartsTest {
    private fun audio(label: String?, code: String? = null, name: String? = null) =
        describeTrackLabel(label, code, name, TrackKind.Audio)

    private fun subtitle(label: String?, code: String? = null, name: String? = null, forced: Boolean = false) =
        describeTrackLabel(label, code, name, TrackKind.Subtitle, isForced = forced)

    @Test
    fun releaseGroupTitleBecomesLanguageAndOrderedFacts() {
        val parts = audio("Japanese / Opus / 2.0 / 48 kHz / 192 kbps / JPBD (Stereo)", "jpn", "Japanese")
        assertEquals("Japanese", parts.title)
        assertEquals("Opus · Stereo · 192 kbps · JPBD", parts.detail)
        assertEquals(emptyList(), parts.badges)
    }

    @Test
    fun unknownNativeSuffixIsDroppedAndLanguageComesFromLabelWhenCodeIsMissing() {
        val parts = audio("English / Opus / 2.0 / 48 kHz / 192 kbps / USBD (unknown, Opus)")
        assertEquals("English", parts.title)
        assertEquals("Opus · Stereo · 192 kbps · USBD", parts.detail)
    }

    @Test
    fun codecsUseFamiliarNamesAndAtmosIsKept() {
        assertEquals("Dolby Digital Plus · 5.1 · 640 kbps", audio("English / E-AC-3 / 5.1 / 640 kbps", "en", "English").detail)
        assertEquals("Dolby TrueHD Atmos · 7.1", audio("TrueHD Atmos 7.1", "en", "English").detail)
        assertEquals("Dolby Digital Plus Atmos · 5.1", audio("English (5.1, E-AC-3-JOC)", "en", "English").detail)
        assertEquals("DTS-HD MA · 5.1", audio("DTS-HD MA 5.1", "en", "English").detail)
        assertEquals("AAC · Stereo", audio("AAC 2.0", "en", "English").detail)
        assertEquals("Dolby Digital · 5.1", audio("ac3 / 6 ch", "en", "English").detail)
        // The TV player joins codec and layout with a space.
        assertEquals("Opus · Stereo · JPBD", audio("Japanese / JPBD (Opus Stereo)", null, "Japanese").detail)
    }

    @Test
    fun keywordsBecomeBadgesWithoutRepeatingThemOnLineTwo() {
        val commentary = audio("English / Commentary / AAC / 2.0", "en", "English")
        assertEquals(listOf(TrackBadge.Commentary), commentary.badges)
        assertEquals("AAC · Stereo", commentary.detail)

        val director = audio("Director's Commentary", "en", "English")
        assertEquals(listOf(TrackBadge.Commentary), director.badges)
        assertEquals("Director's Commentary", director.detail)

        val sdh = subtitle("English [SDH]", "en", "English")
        assertEquals(listOf(TrackBadge.Sdh), sdh.badges)
        assertEquals("", sdh.detail)
    }

    @Test
    fun subtitleFactsPutEditionBeforeFormatAndHonourForcedFlag() {
        val signs = subtitle("Signs & Songs (SSA)", "en", "English", forced = true)
        assertEquals("English", signs.title)
        assertEquals(listOf(TrackBadge.Forced), signs.badges)
        assertEquals("Signs & Songs · ASS", signs.detail)

        assertEquals("PGS", subtitle("hdmv_pgs_subtitle", "de", "German").detail)
    }

    @Test
    fun languageRepeatsAndCodesNeverReachLineTwo() {
        assertEquals("", audio("English", "en", "English").detail)
        assertEquals("", subtitle("Portuguese (Brazil)", "pt-BR", "Portuguese (Brazil)").detail)
        assertEquals("", audio("eng", "eng", "English").detail)
    }

    @Test
    fun generatedNamesAndEmptyLabelsLeaveTheTitleToTheCaller() {
        assertNull(audio("Track 3").title)
        assertNull(audio(null).title)
        assertNull(audio("", "und", null).title)
        assertEquals("AAC · Stereo", audio("Track 3 (Stereo, AAC)").detail)
    }

    @Test
    fun anUnknownCodeEchoedAsTheNameIsNotTreatedAsALanguage() {
        val parts = audio("Hindi", "hin", "hin")
        assertEquals("Hindi", parts.title)
        assertEquals("", parts.detail)
    }
}
