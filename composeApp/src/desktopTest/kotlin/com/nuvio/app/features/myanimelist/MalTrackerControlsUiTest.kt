package com.nuvio.app.features.myanimelist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MalTrackerControlsUiTest {
    private val previousLocale = Locale.getDefault()

    // The bar uses the translated rewatch strings; pin English so the assertions are stable.
    @BeforeTest fun useEnglish() = Locale.setDefault(Locale.US)
    @AfterTest fun restoreLocale() = Locale.setDefault(previousLocale)

    private val heroBackdrop = Brush.linearGradient(listOf(Color(0xFF3B4A5C), Color(0xFF1B2230), Color(0xFF0E1117)))

    @Composable
    private fun Tracker(
        entry: MalListStatus?,
        connected: Boolean = true,
        linked: Boolean = true,
        working: Boolean = false,
        error: String? = null,
        onStatus: (MalStatus) -> Unit = {},
        onScore: (Int) -> Unit = {},
        onToggleRewatching: () -> Unit = {},
        onFinishRewatch: () -> Unit = {},
    ) = MalTrackerControls(
        connected = connected,
        connectionBusy = false,
        working = working,
        linked = linked,
        entry = entry,
        error = error,
        onConnect = {},
        onChooseEntry = {},
        onStatus = onStatus,
        onScore = onScore,
        onToggleRewatching = onToggleRewatching,
        onFinishRewatch = onFinishRewatch,
        onRetry = {},
    )

    private fun ComposeUiTest.capture(name: String) {
        waitForIdle()
        val directory = File("build/mal-tracker-capture").apply { mkdirs() }
        // An open menu is its own popup root; the last root captures the scene with the menu on top.
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test
    fun rendersEveryTrackerState() = runDesktopComposeUiTest(width = 900, height = 560) {
        setContent {
            NuvioTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(heroBackdrop)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        Tracker(MalListStatus(MalStatus.WATCHING, score = 8, watchedEpisodes = 7))
                        Tracker(MalListStatus(MalStatus.COMPLETED, score = 9, watchedEpisodes = 12, isRewatching = true, numTimesRewatched = 2))
                        Tracker(MalListStatus(MalStatus.COMPLETED, score = 0, watchedEpisodes = 12, numTimesRewatched = 1))
                        Tracker(MalListStatus(MalStatus.PLAN_TO_WATCH, score = 0, watchedEpisodes = 0), working = true)
                        Tracker(entry = null)
                        Tracker(entry = null, linked = false)
                        Tracker(entry = null, connected = false)
                        Tracker(MalListStatus(MalStatus.ON_HOLD, score = 6, watchedEpisodes = 3), error = "MyAnimeList did not respond in time.")
                    }
                }
            }
        }
        onAllNodesWithText("Rewatches: 0").assertCountEquals(3)
        onNodeWithText("Rewatches: 2").assertExists()
        onNodeWithText("Rewatches: 1").assertExists()
        capture("tracker-states")
    }

    @Test
    fun completedRewatchMenuTogglesAndFinishes() = runDesktopComposeUiTest(width = 900, height = 520) {
        var toggles = 0
        var finishes = 0
        setContent {
            NuvioTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(heroBackdrop).padding(24.dp)) {
                    Tracker(
                        MalListStatus(MalStatus.COMPLETED, score = 9, watchedEpisodes = 12, isRewatching = true, numTimesRewatched = 2),
                        onToggleRewatching = { toggles++ },
                        onFinishRewatch = { finishes++ },
                    )
                }
            }
        }
        onNodeWithContentDescription("Rewatches: 2, Rewatching").assertHasClickAction().performClick()
        capture("rewatch-menu")
        onNodeWithText("Finish rewatch").performClick()
        assertEquals(1, finishes)
        onNodeWithContentDescription("Rewatches: 2, Rewatching").performClick()
        // The bar's "Rewatching" tag plus the menu's toggle row.
        onAllNodesWithText("Rewatching", useUnmergedTree = true).onLast().performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun rewatchCountIsInformationalUntilTheTitleIsCompleted() = runDesktopComposeUiTest(width = 900, height = 200) {
        setContent {
            NuvioTheme(darkTheme = true) {
                Tracker(MalListStatus(MalStatus.WATCHING, score = 8, watchedEpisodes = 7))
            }
        }
        onNodeWithContentDescription("Rewatches: 0").assertHasNoClickAction()
        onNodeWithContentDescription("Change MAL entry").assertHasClickAction()
    }

    @Test
    fun statusAndRatingMenusReportTheChosenValue() = runDesktopComposeUiTest(width = 900, height = 760) {
        var chosenStatus: MalStatus? = null
        var chosenScore: Int? = null
        setContent {
            NuvioTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(heroBackdrop).padding(24.dp)) {
                    Tracker(
                        MalListStatus(MalStatus.WATCHING, score = 8, watchedEpisodes = 7),
                        onStatus = { chosenStatus = it },
                        onScore = { chosenScore = it },
                    )
                }
            }
        }
        onNodeWithContentDescription("MyAnimeList status: Watching").performClick()
        capture("status-menu")
        onNodeWithText("Completed").performClick()
        assertEquals(MalStatus.COMPLETED, chosenStatus)
        onNodeWithContentDescription("MyAnimeList rating: 8 out of 10").performClick()
        capture("rating-menu")
        onNodeWithText("Clear rating").performClick()
        assertEquals(0, chosenScore)
    }
}
