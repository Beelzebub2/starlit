package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.nuvio.app.core.ui.NuvioTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PlayerTrackPanelUiTest {
    private val releaseAudio = listOf(
        AudioTrack(0, "1", "Japanese / Opus / 2.0 / 48 kHz / 192 kbps / JPBD (Stereo)", "jpn"),
        AudioTrack(1, "2", "English / Opus / 2.0 / 48 kHz / 192 kbps / USBD (unknown, Opus)", "eng"),
        AudioTrack(2, "3", "German / Opus / 2.0 / 48 kHz / 192 kbps / DEBD", "ger"),
    )
    private val fileSubtitles = listOf(
        SubtitleTrack(0, "4", "Full Subtitles (SSA)", "eng"),
        SubtitleTrack(1, "5", "Signs & Songs (SSA)", "eng", isForced = true),
    )

    @Composable
    private fun Panel(
        initialTab: TrackPanelTab = TrackPanelTab.Subtitles,
        localSubtitleName: String? = null,
        onAudio: (Int) -> Unit = {},
        onBuiltIn: (Int) -> Unit = {},
        onChooseLocal: () -> Unit = {},
        onRemoveLocal: () -> Unit = {},
    ) {
        NuvioTheme {
            PlayerTrackPanel(
                visible = true,
                initialTab = initialTab,
                audioTracks = releaseAudio,
                selectedAudioIndex = 0,
                onAudioTrackSelected = onAudio,
                subtitleTracks = fileSubtitles,
                selectedSubtitleIndex = 0,
                addonSubtitles = emptyList(),
                selectedAddonSubtitleId = null,
                isLoadingAddonSubtitles = false,
                preferredSubtitleLanguage = "en",
                secondaryPreferredSubtitleLanguage = null,
                subtitleStyle = SubtitleStyleState(),
                subtitleDelayMs = 0,
                selectedAddonSubtitle = null,
                subtitleAutoSyncState = SubtitleAutoSyncUiState(),
                localSubtitleName = localSubtitleName,
                canChooseLocalSubtitle = true,
                onBuiltInTrackSelected = onBuiltIn,
                onAddonSubtitleSelected = {},
                onChooseLocalSubtitle = onChooseLocal,
                onRemoveLocalSubtitle = onRemoveLocal,
                onFetchAddonSubtitles = {},
                onStyleChanged = {},
                onSubtitleDelayChanged = {},
                onSubtitleDelayReset = {},
                onAutoSyncCapture = {},
                onAutoSyncCueSelected = {},
                onAutoSyncReload = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun widePanelShowsReadableAudioAndSubtitleRowsSideBySide() =
        runDesktopComposeUiTest(width = 1366, height = 768) {
            val audioPicks = mutableListOf<Int>()
            val subtitlePicks = mutableListOf<Int>()
            setContent { Panel(onAudio = { audioPicks += it }, onBuiltIn = { subtitlePicks += it }) }
            waitForIdle()

            onNodeWithText("Audio & subtitles").assertExists()
            onNodeWithText("Opus · Stereo · 192 kbps · JPBD").assertExists()
            onNodeWithText("Japanese / Opus / 2.0 / 48 kHz / 192 kbps / JPBD (Stereo)").assertDoesNotExist()
            onNodeWithText("Built-in · Signs & Songs · ASS").assertExists()
            onNodeWithText("Forced").assertExists()

            onNodeWithText("Opus · Stereo · 192 kbps · USBD").performClick()
            onNodeWithText("Off").performClick()
            assertEquals(listOf(1), audioPicks)
            assertEquals(listOf(-1), subtitlePicks)

            capture("desktop-1366")
        }

    @Test
    fun phoneLandscapeSwitchesBetweenAudioAndSubtitleTabs() =
        runDesktopComposeUiTest(width = 844, height = 390) {
            setContent { Panel(initialTab = TrackPanelTab.Audio) }
            waitForIdle()

            onNodeWithText("Opus · Stereo · 192 kbps · JPBD").assertExists()
            onNodeWithText("Off").assertDoesNotExist()
            capture("phone-audio")

            onNodeWithText("Subtitles").performClick()
            waitForIdle()
            onNodeWithText("Off").assertExists()
            onNodeWithText("Opus · Stereo · 192 kbps · JPBD").assertDoesNotExist()
            capture("phone-subtitles")
        }

    @Test
    fun selectedLocalSubtitleHasOpenAndRemoveActions() =
        runDesktopComposeUiTest(width = 1366, height = 768) {
            var opened = 0
            var removed = 0
            setContent {
                Panel(
                    localSubtitleName = "episode-01.srt",
                    onChooseLocal = { opened++ },
                    onRemoveLocal = { removed++ },
                )
            }
            waitForIdle()
            onNodeWithText("episode-01.srt").assertExists()
            onNodeWithText("Open subtitle file").assertExists().performClick()
            onNodeWithText("Remove file").assertExists().performClick()
            assertEquals(1, opened)
            assertEquals(1, removed)
        }

    @Test
    fun delayLabelShowsSignedSeconds() {
        assertEquals("0.0 s", formatTrackPanelDelay(0))
        assertEquals("+0.3 s", formatTrackPanelDelay(300))
        assertEquals("−1.25 s", formatTrackPanelDelay(-1250))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.capture(name: String) {
        val output = File("build/track-panel-capture").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, "$name.png"))
    }
}
