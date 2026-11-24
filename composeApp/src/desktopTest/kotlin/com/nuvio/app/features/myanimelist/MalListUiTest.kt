package com.nuvio.app.features.myanimelist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.lists.ListChange
import com.nuvio.app.features.lists.ListChangeImpact
import com.nuvio.app.features.lists.ListEntry
import com.nuvio.app.features.lists.ListMediaType
import com.nuvio.app.features.lists.ListPage
import com.nuvio.app.features.lists.ListPreferences
import com.nuvio.app.features.lists.ListService
import com.nuvio.app.features.lists.ListServiceId
import com.nuvio.app.features.lists.ListSort
import com.nuvio.app.features.lists.ListStatus
import com.nuvio.app.features.lists.ListSummary
import com.nuvio.app.features.lists.ListTitleKind
import com.nuvio.app.features.lists.ListToolbar
import com.nuvio.app.features.lists.ListsTabContent
import com.nuvio.app.features.lists.applyMalChange
import com.nuvio.app.features.lists.malListCapabilities
import com.nuvio.app.features.lists.malListSummary
import com.nuvio.app.features.lists.toListEntry
import com.nuvio.app.features.lists.toListStatus
import com.nuvio.app.features.lists.withMalListStatus
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A MyAnimeList account in memory: the tab writes to it through the real MAL adapter rules ([applyMalChange]) and the
 * MAL tab hooks ([malTabHooks]), so the patches recorded here are what MyAnimeList would receive.
 */
private class FakeMalAccount(entries: List<MalAnimeListEntry>) : ListService {
    private val server = entries.associate { it.anime.id to it.listStatus }.toMutableMap()
    private var shown = entries.map { it.toListEntry() }
    val sent = mutableListOf<MalListPatch>()
    val removed = mutableListOf<Int>()
    var finished = 0
    var pages = 0
    var summaries = 0

    override val id = ListServiceId.MYANIMELIST
    override val connected = MutableStateFlow(true)
    override val sorts = MalListSort.entries.map { ListSort(it.name, it.label) }
    override fun covers(kind: ListTitleKind, meta: MetaDetails) = true
    override fun capabilities(type: ListMediaType) = malListCapabilities
    override fun profileUrl() = "https://myanimelist.net/"
    override suspend fun summary(): ListSummary {
        summaries++
        return malListSummary("tester", MalAnimeStatistics(watching = 1, total = shown.size, episodes = 2650, days = 45.3))
    }
    override suspend fun page(status: ListStatus?, sort: ListSort, offset: Int): ListPage {
        pages++
        return ListPage(shown.filter { status == null || it.status == status }, nextOffset = null)
    }
    override suspend fun entryFor(meta: MetaDetails): ListEntry? = null
    override suspend fun impact(entry: ListEntry, change: ListChange) = ListChangeImpact(0, false)
    override suspend fun apply(entry: ListEntry, change: ListChange) = applyMalChange(entry, change, ::update, ::finishRewatch)
    override suspend fun removeFromHistory(entry: ListEntry): Int = error("MyAnimeList keeps no watch history")

    suspend fun update(id: Int, patch: MalListPatch): MalListStatus {
        sent += patch
        val current = server.getValue(id)
        return MalListStatus(
            patch.status ?: current.status,
            patch.score ?: current.score,
            patch.watchedEpisodes ?: current.watchedEpisodes,
            patch.isRewatching ?: current.isRewatching,
            patch.numTimesRewatched ?: current.numTimesRewatched,
        ).also { store(id, it) }
    }

    suspend fun finishRewatch(id: Int): MalListStatus {
        finished++
        return server.getValue(id).withCompletedRewatch().also { store(id, it) }
    }

    suspend fun remove(id: Int) {
        removed += id
        shown = shown.filterNot { it.key.id == id.toString() }
    }

    private fun store(id: Int, status: MalListStatus) {
        server[id] = status
        shown = shown.map { if (it.key.id == id.toString()) it.withMalListStatus(status) else it }
    }
}

@OptIn(ExperimentalTestApi::class)
class MalListUiTest {
    private val previousLocale = Locale.getDefault()

    @BeforeTest fun useEnglish() {
        Locale.setDefault(Locale.US)
        NuvioToastController.dismiss()
    }
    @AfterTest fun restoreLocale() = Locale.setDefault(previousLocale)

    private val tempal = MalAnimeListEntry(
        anime = MalAnime(41, "Tempal: Item no Chikara"),
        imageUrl = null,
        totalEpisodes = 12,
        listStatus = MalListStatus(MalStatus.WATCHING, 0, 2),
        mediaType = "tv",
        startYear = 2025,
        startSeason = "fall",
    )
    private val rewatching = tempal.copy(listStatus = MalListStatus(
        MalStatus.COMPLETED, score = 9, watchedEpisodes = 12, isRewatching = true, numTimesRewatched = 2,
    ))

    private val malOnly = ListPreferences(
        order = ListServiceId.entries.toList(), inTab = setOf(ListServiceId.MYANIMELIST),
        onTitlePages = ListServiceId.entries.toSet(), lastTabService = null,
    )

    private fun ComposeUiTest.showTab(account: FakeMalAccount) {
        setContent {
            NuvioTheme {
                ListsTabContent(
                    topChromePadding = null,
                    services = listOf(account),
                    preferences = malOnly,
                    hooks = mapOf(
                        ListServiceId.MYANIMELIST to malTabHooks(
                            MalConnectionState(connected = true, username = "tester"),
                            onConnect = {},
                            update = account::update,
                            remove = account::remove,
                        ),
                    ),
                    profileId = 1,
                    activeProfile = { 1 },
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Tempal: Item no Chikara").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.inPopup(text: String) = onNode(hasText(text) and hasAnyAncestor(isPopup()))

    private fun ComposeUiTest.openDetails() {
        onNodeWithContentDescription("More actions").performClick()
        inPopup("Edit").performClick()
        onNode(isDialog()).assertExists()
    }

    @Test fun stepperAddsAnEpisodeAndSavesIt() = runDesktopComposeUiTest(width = 1280, height = 760) {
        val account = FakeMalAccount(listOf(tempal))
        showTab(account)
        onNodeWithText("2 / 12").assertIsDisplayed()
        onNodeWithText("2 of 12 episodes").assertIsDisplayed()
        onNodeWithContentDescription("One more episode").performClick()
        onNodeWithText("3 / 12").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) { account.sent.isNotEmpty() }
        assertEquals(listOf(MalListPatch(watchedEpisodes = 3)), account.sent)
    }

    @Test fun statusAndScoreMenusSaveTheChoice() = runDesktopComposeUiTest(width = 1280, height = 760) {
        val account = FakeMalAccount(listOf(tempal))
        showTab(account)
        onNodeWithContentDescription("Status: Watching. Change status").performClick()
        inPopup("On Hold").performClick()
        onNodeWithContentDescription("Status: On Hold. Change status").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) { account.sent.size == 1 }

        onNodeWithContentDescription("Score this title").performClick()
        inPopup("8  ·  Very good").performClick()
        onNodeWithContentDescription("Your score: 8 out of 10. Change score").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) { account.sent.size == 2 }
        assertEquals(listOf(MalListPatch(status = MalStatus.ON_HOLD), MalListPatch(score = 8)), account.sent)
    }

    @Test fun detailsDialogSavesEveryFieldAsOnePatch() = runDesktopComposeUiTest(width = 1280, height = 760) {
        val account = FakeMalAccount(listOf(tempal))
        showTab(account)
        openDetails()
        onNode(hasSetTextAction()).performTextReplacement("12")
        onNodeWithContentDescription("9, Great").performClick()
        onNodeWithText("9 · Great").assertIsDisplayed()
        onNodeWithText("Rewatching").assertIsDisplayed()
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 5_000) { account.sent.isNotEmpty() }
        assertEquals(listOf(MalListPatch(status = MalStatus.COMPLETED, score = 9, watchedEpisodes = 12)), account.sent)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("12 / 12").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun rewatchCountIsShownAndSavedBackToMyAnimeList() = runDesktopComposeUiTest(width = 1280, height = 760) {
        val account = FakeMalAccount(listOf(rewatching))
        showTab(account)
        onNodeWithTag("mal-rewatch-count-41", useUnmergedTree = true).assertIsDisplayed()
        openDetails()
        onAllNodes(hasSetTextAction())[1].performTextReplacement("4")
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 5_000) { account.sent.isNotEmpty() }
        assertEquals(listOf(MalListPatch(numTimesRewatched = 4)), account.sent)
    }

    @Test fun finishingARewatchRefreshesTheListAndTotals() = runDesktopComposeUiTest(width = 1280, height = 760) {
        val account = FakeMalAccount(listOf(rewatching))
        showTab(account)
        waitUntil(timeoutMillis = 5_000) { account.summaries > 0 }
        val pagesBefore = account.pages
        val summariesBefore = account.summaries
        openDetails()
        onNodeWithText("Finish rewatch").performClick()
        waitUntil(timeoutMillis = 5_000) { account.finished == 1 }
        // As before the lists tab: the list and the header totals are read again.
        waitUntil(timeoutMillis = 10_000) { account.pages > pagesBefore && account.summaries > summariesBefore }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Rewatches: 3").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(account.sent.isEmpty(), "Finishing a rewatch uses MyAnimeList's finish call, not a patch")
    }

    @Test fun removeFromListAsksThenRemoves() = runDesktopComposeUiTest(width = 1280, height = 760) {
        val account = FakeMalAccount(listOf(tempal))
        showTab(account)
        onNodeWithContentDescription("More actions").performClick()
        inPopup("Remove from list").performClick()
        onNodeWithText("Remove from MyAnimeList?").assertExists()
        assertTrue(account.removed.isEmpty())
        onNodeWithText("Remove").performClick()
        waitUntil(timeoutMillis = 5_000) { account.removed == listOf(41) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Tempal: Item no Chikara").fetchSemanticsNodes().isEmpty() }
        waitUntil(timeoutMillis = 5_000) { NuvioToastController.currentToast.value?.message == "Removed from MyAnimeList" }
    }

    private val listStats = MalAnimeStatistics(
        watching = 10, completed = 689, onHold = 0, dropped = 180, planToWatch = 33, total = 912,
    )

    @Composable
    private fun MalToolbar() {
        val selected = remember { mutableStateOf<ListStatus?>(null) }
        val sorts = MalListSort.entries.map { ListSort(it.name, it.label) }
        ListToolbar(
            statuses = MalStatus.entries.map { it.toListStatus() },
            selected = selected.value,
            onSelect = { selected.value = it },
            counts = malListSummary(null, listStats).counts,
            sorts = sorts,
            sort = sorts.first(),
            onSort = {},
            tagPrefix = "mal",
        )
    }

    private fun ComposeUiTest.showToolbar(width: Dp) {
        setContent {
            NuvioTheme {
                Surface {
                    Box(Modifier.width(width).background(MaterialTheme.nuvio.colors.background)) { MalToolbar() }
                }
            }
        }
    }

    private fun ComposeUiTest.assertEveryStatusTabIsLabelled() {
        listOf(
            "All" to "912", "Watching" to "10", "Completed" to "689",
            "On Hold" to "0", "Dropped" to "180", "Plan to Watch" to "33",
        ).forEach { (label, count) ->
            // The tab merges its texts, so check the label's own node: a zero-width label still
            // leaves the merged tab visible through its count.
            onNodeWithText(label, useUnmergedTree = true)
                .assertIsDisplayed()
                .assertWidthIsAtLeast(16.dp)
            onNodeWithText(count, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun wideToolbarLabelsEveryStatusBesideItsCount() = runDesktopComposeUiTest(width = 1400, height = 300) {
        showToolbar(1280.dp)
        assertEveryStatusTabIsLabelled()
    }

    @Test fun narrowToolbarLabelsEveryStatusBesideItsCount() = runDesktopComposeUiTest(width = 800, height = 300) {
        showToolbar(720.dp)
        onNodeWithText("Completed", useUnmergedTree = true).assertWidthIsAtLeast(16.dp)
        onNodeWithText("Watching", useUnmergedTree = true).assertWidthIsAtLeast(16.dp)
        onNodeWithTag("mal-filter-PLAN_TO_WATCH")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        onNodeWithText("Plan to Watch", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(16.dp)
    }
}
