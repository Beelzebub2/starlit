package com.nuvio.app.features.myanimelist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class MalListUiTest {
    @get:Rule val compose = createComposeRule()

    private val sent = mutableListOf<MalListPatch>()

    private fun showCard(entry: MalAnimeListEntry, editing: Boolean = false) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val editor = remember {
                var server = entry.listStatus
                MalListEditor(scope, send = { _, patch ->
                    sent += patch
                    server = MalListStatus(
                        patch.status ?: server.status,
                        patch.score ?: server.score,
                        patch.watchedEpisodes ?: server.watchedEpisodes,
                        patch.isRewatching ?: server.isRewatching,
                    )
                    server
                }, delete = {}, debounceMillis = 0).also { it.show(listOf(entry), append = false) }
            }
            val dialog = remember { mutableStateOf(editing) }
            NuvioTheme {
                Surface {
                    Box(Modifier.size(720.dp, 760.dp)) {
                        Column(Modifier.padding(16.dp).width(560.dp)) {
                            editor.entries.forEach { current ->
                                MalEntryCard(
                                    entry = current,
                                    saving = editor.isSaving(current.anime.id),
                                    onChange = { change -> editor.edit(current.anime.id, change) },
                                    onEdit = { dialog.value = true },
                                    onOpen = {},
                                    onRemove = {},
                                )
                                if (dialog.value) {
                                    MalEntryEditorDialog(
                                        entry = current,
                                        onDismiss = { dialog.value = false },
                                        onSave = { draft -> dialog.value = false; editor.edit(current.anime.id) { draft } },
                                        onRemove = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private val tempal = MalAnimeListEntry(
        anime = MalAnime(41, "Tempal: Item no Chikara"),
        imageUrl = null,
        totalEpisodes = 12,
        listStatus = MalListStatus(MalStatus.WATCHING, 0, 2),
        mediaType = "tv",
        startYear = 2025,
        startSeason = "fall",
    )

    @Test fun stepperAddsAnEpisodeAndSavesIt() {
        showCard(tempal)
        compose.onNodeWithText("2 / 12").assertIsDisplayed()
        compose.onNodeWithText("2 of 12 episodes").assertIsDisplayed()
        compose.onNodeWithContentDescription("One more episode").performClick()
        compose.onNodeWithText("3 / 12").assertIsDisplayed()
        compose.waitUntil(5_000) { sent.isNotEmpty() }
        assertEquals(listOf(MalListPatch(watchedEpisodes = 3)), sent)
    }

    @Test fun statusAndScoreMenusSaveTheChoice() {
        showCard(tempal)
        compose.onNodeWithContentDescription("Status: Watching. Change status").performClick()
        compose.onNodeWithText("On Hold").performClick()
        compose.onNodeWithContentDescription("Status: On Hold. Change status").assertIsDisplayed()
        compose.waitUntil(5_000) { sent.size == 1 }

        compose.onNodeWithContentDescription("Score this title").performClick()
        compose.onNodeWithText("8  ·  Very good").performClick()
        compose.onNodeWithContentDescription("Your score: 8 out of 10. Change score").assertIsDisplayed()
        compose.waitUntil(5_000) { sent.size == 2 }
        assertEquals(listOf(MalListPatch(status = MalStatus.ON_HOLD), MalListPatch(score = 8)), sent)
    }

    @Test fun editorTypingTheLastEpisodeCompletesTheTitle() {
        showCard(tempal, editing = true)
        compose.onNode(hasSetTextAction()).performTextReplacement("12")
        compose.onNodeWithContentDescription("9, Great").performClick()
        compose.onNodeWithText("9 · Great").assertIsDisplayed()
        compose.onNodeWithText("Rewatching").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { sent.isNotEmpty() }
        assertEquals(listOf(MalListPatch(status = MalStatus.COMPLETED, score = 9, watchedEpisodes = 12)), sent)
        compose.onNodeWithText("12 / 12").assertIsDisplayed()
    }

    private val listStats = MalAnimeStatistics(
        watching = 10, completed = 689, onHold = 0, dropped = 180, planToWatch = 33, total = 912,
    )

    private fun showToolbar(width: Dp) {
        compose.setContent {
            NuvioTheme {
                Surface {
                    Box(Modifier.width(width)) {
                        val selected = remember { mutableStateOf<MalStatus?>(null) }
                        MalListToolbar(
                            selected = selected.value,
                            onSelect = { selected.value = it },
                            stats = listStats,
                            sort = MalListSort.UPDATED,
                            onSort = {},
                        )
                    }
                }
            }
        }
    }

    private fun assertEveryStatusTabIsLabelled() {
        listOf(
            "All" to "912", "Watching" to "10", "Completed" to "689",
            "On Hold" to "0", "Dropped" to "180", "Plan to Watch" to "33",
        ).forEach { (label, count) ->
            // The tab merges its texts, so check the label's own node: a zero-width label still
            // leaves the merged tab visible through its count.
            compose.onNodeWithText(label, useUnmergedTree = true)
                .assertIsDisplayed()
                .assertWidthIsAtLeast(16.dp)
            compose.onNodeWithText(count, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun wideToolbarLabelsEveryStatusBesideItsCount() {
        showToolbar(1280.dp)
        assertEveryStatusTabIsLabelled()
    }

    @Test fun narrowToolbarLabelsEveryStatusBesideItsCount() {
        showToolbar(720.dp)
        compose.onNodeWithText("Completed", useUnmergedTree = true).assertWidthIsAtLeast(16.dp)
        compose.onNodeWithText("Watching", useUnmergedTree = true).assertWidthIsAtLeast(16.dp)
        compose.onNodeWithTag("mal-filter-PLAN_TO_WATCH")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose.onNodeWithText("Plan to Watch", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(16.dp)
    }
}
