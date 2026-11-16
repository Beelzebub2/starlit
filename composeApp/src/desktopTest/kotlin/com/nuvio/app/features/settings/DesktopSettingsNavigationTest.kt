package com.nuvio.app.features.settings

import java.awt.event.KeyEvent
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSettingsNavigationTest {
    private fun press(keyCode: Int, modifiers: Int): KeyEvent =
        KeyEvent(JPanel(), KeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED)

    @Test
    fun searchShortcutIsCtrlKOnWindowsAndLinuxAndCmdKOnMac() {
        assertTrue(press(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK).isSettingsSearchShortcut(macOs = false))
        assertTrue(press(KeyEvent.VK_K, KeyEvent.META_DOWN_MASK).isSettingsSearchShortcut(macOs = true))

        assertFalse(press(KeyEvent.VK_K, 0).isSettingsSearchShortcut(macOs = false))
        assertFalse(press(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK).isSettingsSearchShortcut(macOs = true))
        assertFalse(press(KeyEvent.VK_K, KeyEvent.META_DOWN_MASK).isSettingsSearchShortcut(macOs = false))
        assertFalse(
            press(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK)
                .isSettingsSearchShortcut(macOs = false),
        )
        assertFalse(press(KeyEvent.VK_J, KeyEvent.CTRL_DOWN_MASK).isSettingsSearchShortcut(macOs = false))
    }

    @Test
    fun playbackSearchResultsOpenTheTabThatHoldsThem() {
        assertEquals(PlaybackSettingsTab.Player, playbackTabForSearchKey("playback-loading-overlay"))
        assertEquals(PlaybackSettingsTab.SubtitlesAudio, playbackTabForSearchKey("playback-preferred-subtitles"))
        assertEquals(PlaybackSettingsTab.Streams, playbackTabForSearchKey("playback-reuse-last-link"))
        assertEquals(PlaybackSettingsTab.NextEpisode, playbackTabForSearchKey("playback-auto-play-next"))
        assertEquals(PlaybackSettingsTab.Video, playbackTabForSearchKey("playback-rtx-vsr"))
        assertNull(playbackTabForSearchKey("playback-something-new"))
        assertNull(playbackTabForSearchKey(null))
    }

    @Test
    fun mergedPagesKeepTheirSidebarEntrySelected() {
        assertEquals(DesktopSettingsEntry.Playback, SettingsPage.Streams.desktopEntry())
        assertEquals(DesktopSettingsEntry.PostersAndPreviews, SettingsPage.HoverPreview.desktopEntry())
        assertEquals(DesktopSettingsEntry.PostersAndPreviews, SettingsPage.PosterCustomization.desktopEntry())
        assertEquals(DesktopSettingsEntry.MetadataAndRatings, SettingsPage.MdbListRatings.desktopEntry())
        assertEquals(DesktopSettingsEntry.MetadataAndRatings, SettingsPage.TmdbEnrichment.desktopEntry())
    }

    @Test
    fun sidebarStartsWithOverviewAndHasFiveLabelledGroups() {
        val groups = desktopSettingsGroups()
        assertEquals(listOf(DesktopSettingsEntry.Overview), groups.first().entries)
        assertNull(groups.first().labelRes)
        assertEquals(5, groups.drop(1).count { it.labelRes != null })
        val listed = groups.flatMap { it.entries }
        assertEquals(listed.distinct(), listed)
    }
}
