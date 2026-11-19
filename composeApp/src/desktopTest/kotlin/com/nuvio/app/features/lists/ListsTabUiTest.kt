package com.nuvio.app.features.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class AppliedChange(val entry: ListEntry, val change: ListChange, val profile: Int)

/** In-memory list service: pages come from [entries], writes are recorded and folded back into [entries]. */
private class TabFakeService(
    override val id: ListServiceId,
    private val caps: (ListMediaType) -> ListCapabilities,
    var entries: List<ListEntry>,
    connected: Boolean = true,
    private val applyDelayMillis: Long = 0,
    private val profile: () -> Int = { 1 },
    var impactOf: (ListEntry, ListChange) -> ListChangeImpact = { _, _ -> ListChangeImpact(0, false) },
    var historyRemoved: Int = 0,
) : ListService {
    override val connected = MutableStateFlow(connected)
    val applied = mutableListOf<AppliedChange>()
    val removedFromHistory = mutableListOf<ListEntry>()
    var running = 0
    var maxRunning = 0
    override val sorts = listOf(ListSort("updated", "Last updated"), ListSort("title", "Title"))
    override fun covers(kind: ListTitleKind, meta: MetaDetails) = true
    override fun capabilities(type: ListMediaType) = caps(type)
    override fun profileUrl(): String = "https://example.test/${id.storageId}"
    override suspend fun summary() = ListSummary(
        signedInAs = "tester",
        counts = entries.groupingBy { it.status }.eachCount() + (null to entries.size),
        extra = listOf("episodes watched" to "1234"),
    )
    override suspend fun page(status: ListStatus?, sort: ListSort, offset: Int) =
        ListPage(entries.filter { status == null || it.status == status }, nextOffset = null)
    override suspend fun entryFor(meta: MetaDetails): ListEntry? = null
    override suspend fun impact(entry: ListEntry, change: ListChange) = impactOf(entry, change)
    override suspend fun apply(entry: ListEntry, change: ListChange): ListEntry {
        running++
        maxRunning = maxOf(maxRunning, running)
        applied += AppliedChange(entry, change, profile())
        try {
            if (applyDelayMillis > 0) delay(applyDelayMillis)
        } finally {
            running--
        }
        // Like MyAnimeList, the new count is absolute and computed from the entry it is given.
        val result = when (change) {
            is ListChange.StepEpisodes -> entry.copy(watchedEpisodes = entry.watchedEpisodes + change.delta)
            is ListChange.SetStatus -> entry.copy(status = change.status)
            is ListChange.SetScore -> entry.copy(score = change.score)
            else -> entry
        }
        entries = entries.map { if (it.key == result.key) result else it }
        return result
    }
    override suspend fun removeFromHistory(entry: ListEntry): Int {
        removedFromHistory += entry
        return historyRemoved
    }
}

private val malTabCaps = { _: ListMediaType ->
    ListCapabilities(ListStatus.entries, ListStatus.entries.toSet(), score = true, episodeProgress = true,
        rewatch = true, changeEntry = true, removeFromHistory = false)
}
private val simklTabCaps = { type: ListMediaType ->
    if (type == ListMediaType.MOVIE) {
        val statuses = listOf(ListStatus.PLAN_TO_WATCH, ListStatus.COMPLETED, ListStatus.DROPPED)
        ListCapabilities(statuses, statuses.toSet(), score = true, episodeProgress = false,
            rewatch = false, changeEntry = false, removeFromHistory = false)
    } else {
        ListCapabilities(ListStatus.entries, ListStatus.entries.toSet(), score = true, episodeProgress = true,
            rewatch = false, changeEntry = false, removeFromHistory = false)
    }
}
private val traktTabCaps = { type: ListMediaType ->
    if (type == ListMediaType.MOVIE) {
        ListCapabilities(listOf(ListStatus.COMPLETED, ListStatus.PLAN_TO_WATCH), setOf(ListStatus.PLAN_TO_WATCH, ListStatus.COMPLETED),
            score = true, episodeProgress = false, rewatch = false, changeEntry = false, removeFromHistory = true,
            leavableStatuses = setOf(ListStatus.PLAN_TO_WATCH))
    } else {
        ListCapabilities(
            listOf(ListStatus.WATCHING, ListStatus.COMPLETED, ListStatus.DROPPED, ListStatus.PLAN_TO_WATCH),
            setOf(ListStatus.PLAN_TO_WATCH, ListStatus.DROPPED, ListStatus.COMPLETED),
            score = true, episodeProgress = true, rewatch = false, changeEntry = false, removeFromHistory = true,
            leavableStatuses = setOf(ListStatus.PLAN_TO_WATCH, ListStatus.DROPPED),
        )
    }
}
private val mdbListTabCaps = { type: ListMediaType ->
    if (type == ListMediaType.MOVIE) {
        ListCapabilities(listOf(ListStatus.COMPLETED, ListStatus.PLAN_TO_WATCH), setOf(ListStatus.PLAN_TO_WATCH, ListStatus.COMPLETED),
            score = false, episodeProgress = false, rewatch = false, changeEntry = false, removeFromHistory = true,
            leavableStatuses = setOf(ListStatus.PLAN_TO_WATCH))
    } else {
        ListCapabilities(
            listOf(ListStatus.WATCHING, ListStatus.COMPLETED, ListStatus.DROPPED, ListStatus.PLAN_TO_WATCH),
            setOf(ListStatus.PLAN_TO_WATCH, ListStatus.COMPLETED, ListStatus.DROPPED),
            score = false, episodeProgress = false, rewatch = false, changeEntry = false, removeFromHistory = true,
            leavableStatuses = setOf(ListStatus.PLAN_TO_WATCH, ListStatus.DROPPED),
        )
    }
}

private fun tabEntry(
    service: ListServiceId,
    id: String,
    title: String,
    status: ListStatus?,
    type: ListMediaType = ListMediaType.SERIES,
    score: Int = 0,
    watched: Int = 0,
    total: Int = 12,
    facts: List<String> = listOf("TV", "2025"),
    subtitle: String? = null,
    rewatchCount: Int = 0,
    community: Double? = null,
) = ListEntry(
    key = ListEntryKey(service, id), title = title, subtitle = subtitle, posterUrl = null, type = type,
    anime = service == ListServiceId.MYANIMELIST, facts = facts, communityScore = community, status = status,
    score = score, watchedEpisodes = watched, totalEpisodes = total, airing = false, rewatching = false,
    rewatchCount = rewatchCount, catalogType = if (type == ListMediaType.MOVIE) "movie" else "series",
    catalogId = "tt$id", siteUrl = "https://example.test/title/$id",
)

private fun prefsFor(vararg inTab: ListServiceId, last: ListServiceId? = null) = ListPreferences(
    order = ListServiceId.entries.toList(), inTab = inTab.toSet(), onTitlePages = ListServiceId.entries.toSet(), lastTabService = last,
)

@OptIn(ExperimentalTestApi::class)
class ListsTabUiTest {
    private val previousLocale = Locale.getDefault()

    // The tab uses translated strings; pin English so the assertions are stable.
    @BeforeTest fun useEnglish() {
        Locale.setDefault(Locale.US)
        NuvioToastController.dismiss()
    }
    @AfterTest fun restoreLocale() = Locale.setDefault(previousLocale)

    private fun malService(applyDelayMillis: Long = 0, profile: () -> Int = { 1 }) = TabFakeService(
        ListServiceId.MYANIMELIST, malTabCaps,
        entries = listOf(
            tabEntry(ListServiceId.MYANIMELIST, "41", "Tempal: Item no Chikara", ListStatus.WATCHING, score = 8, watched = 2,
                facts = listOf("TV", "Fall 2025", "12 eps"), community = 6.52),
            tabEntry(ListServiceId.MYANIMELIST, "42", "Kono Subarashii Sekai ni Shukufuku wo! 2", ListStatus.COMPLETED, score = 10,
                watched = 10, total = 10, subtitle = "KonoSuba: God's Blessing on This Wonderful World! 2",
                facts = listOf("TV", "Winter 2017", "10 eps"), rewatchCount = 3, community = 8.3),
        ),
        applyDelayMillis = applyDelayMillis,
        profile = profile,
    )

    private fun simklService() = TabFakeService(
        ListServiceId.SIMKL, simklTabCaps,
        entries = listOf(
            tabEntry(ListServiceId.SIMKL, "s1", "Severance", ListStatus.WATCHING, score = 9, watched = 14, total = 19, facts = listOf("Series", "2022")),
            tabEntry(ListServiceId.SIMKL, "m1", "Dune: Part Two", ListStatus.COMPLETED, type = ListMediaType.MOVIE, score = 8,
                watched = 1, total = 1, facts = listOf("Movie", "2024")),
        ),
    )

    private fun traktService(connected: Boolean = true) = TabFakeService(
        ListServiceId.TRAKT, traktTabCaps,
        entries = listOf(
            tabEntry(ListServiceId.TRAKT, "t1", "The Bear", ListStatus.WATCHING, score = 7, watched = 6, total = 28, facts = listOf("Series", "2022")),
        ),
        connected = connected,
        historyRemoved = 6,
    )

    private fun mdbListService() = TabFakeService(
        ListServiceId.MDBLIST, mdbListTabCaps,
        entries = listOf(
            tabEntry(ListServiceId.MDBLIST, "d1", "Shogun", ListStatus.WATCHING, watched = 4, total = 10, facts = listOf("Series", "2024")),
        ),
    )

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        NuvioTheme(darkTheme = true) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background)) { content() }
        }
    }

    private fun ComposeUiTest.capture(name: String) {
        waitForIdle()
        val directory = File("build/lists-tab-capture").apply { mkdirs() }
        // An open menu or dialog is its own popup root; the last root captures the scene with it on top.
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun ComposeUiTest.waitFor(timeoutMillis: Long = 5_000, condition: () -> Boolean) =
        waitUntil(timeoutMillis = timeoutMillis, condition = condition)

    @Test
    fun singleServiceTabIsNamedAfterIt() = runDesktopComposeUiTest(width = 1280, height = 760) {
        val mal = malService()
        setContent { Page { ListsTabContent(null, listOf(mal, simklService()), prefsFor(ListServiceId.MYANIMELIST), profileId = 1, activeProfile = { 1 }) } }
        waitFor { onAllNodesWithText("Tempal: Item no Chikara").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("MyAnimeList").assertExists()
        onAllNodesWithText("Lists").assertCountEquals(0)
        onAllNodesWithTag("lists-chip-simkl").assertCountEquals(0)
        onNodeWithText("Signed in as tester").assertExists()
        onNodeWithText("2 of 12 episodes").assertExists()
        capture("single-service")
    }

    @Test
    fun severalServicesShowChips() = runDesktopComposeUiTest(width = 1280, height = 760) {
        val remembered = mutableListOf<ListServiceId>()
        setContent {
            Page {
                ListsTabContent(
                    null, listOf(malService(), simklService()), prefsFor(ListServiceId.MYANIMELIST, ListServiceId.SIMKL),
                    profileId = 1, activeProfile = { 1 }, onRememberService = { remembered += it },
                )
            }
        }
        waitFor { onAllNodesWithText("Tempal: Item no Chikara").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Lists").assertExists()
        onNodeWithTag("lists-chip-myanimelist").assertExists()
        onNodeWithTag("lists-chip-simkl").assertExists()
        capture("several-services")

        onNodeWithTag("lists-chip-simkl").performClick()
        waitFor { onAllNodesWithText("Severance").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(ListServiceId.SIMKL), remembered)
        onAllNodesWithText("Tempal: Item no Chikara").assertCountEquals(0)
        capture("several-services-simkl")
    }

    @Test
    fun rememberedServiceIsSelectedFirst() = runDesktopComposeUiTest(width = 1280, height = 760) {
        setContent {
            Page {
                ListsTabContent(
                    null, listOf(malService(), simklService()),
                    prefsFor(ListServiceId.MYANIMELIST, ListServiceId.SIMKL, last = ListServiceId.SIMKL),
                    profileId = 1, activeProfile = { 1 }, onRememberService = {},
                )
            }
        }
        waitFor { onAllNodesWithText("Severance").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Tempal: Item no Chikara").assertCountEquals(0)
    }

    private fun ComposeUiTest.inCard(tag: String, matcher: androidx.compose.ui.test.SemanticsMatcher): SemanticsNodeInteractionCollection =
        onAllNodes(matcher and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    @Test
    fun cardsShowOnlySupportedControls() = runDesktopComposeUiTest(width = 1180, height = 900) {
        val trakt = traktService().entries.single()
        val mdb = mdbListService().entries.single()
        val simklMovie = simklService().entries.last()
        val mal = malService().entries.last()
        setContent {
            Page {
                Column(Modifier.padding(24.dp).width(560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ListEntryCard(trakt, traktTabCaps(trakt.type), saving = false, onChange = {}, onOpen = {}, onRemoveFromHistory = {}, onEditDetails = null)
                    ListEntryCard(mdb, mdbListTabCaps(mdb.type), saving = false, onChange = {}, onOpen = {}, onRemoveFromHistory = {}, onEditDetails = null)
                    ListEntryCard(simklMovie, simklTabCaps(simklMovie.type), saving = true, onChange = {}, onOpen = {}, onRemoveFromHistory = null, onEditDetails = null)
                    ListEntryCard(mal, malTabCaps(mal.type), saving = false, onChange = {}, onOpen = {}, onRemoveFromHistory = null, onEditDetails = {})
                }
            }
        }
        waitForIdle()
        // Trakt: score and stepper, no rewatch line.
        inCard("trakt-entry-t1", hasContentDescription("Your score: 7 out of 10. Change score")).assertCountEquals(1)
        onAllNodesWithTag("trakt-episode-stepper", useUnmergedTree = true).assertCountEquals(1)
        onAllNodesWithTag("trakt-rewatch-count-t1", useUnmergedTree = true).assertCountEquals(0)
        // MDBList: no score and no stepper, but its derived status is shown.
        inCard("mdblist-entry-d1", hasContentDescription("Score this title")).assertCountEquals(0)
        inCard("mdblist-entry-d1", hasText("Rate")).assertCountEquals(0)
        onAllNodesWithTag("mdblist-episode-stepper", useUnmergedTree = true).assertCountEquals(0)
        inCard("mdblist-entry-d1", hasContentDescription("Status: Watching. Change status")).assertCountEquals(1)
        // Simkl movie: score, no episode stepper and no episode line.
        onAllNodesWithTag("simkl-episode-stepper", useUnmergedTree = true).assertCountEquals(0)
        inCard("simkl-entry-m1", hasText("episodes", substring = true)).assertCountEquals(0)
        inCard("simkl-entry-m1", hasContentDescription("Your score: 8 out of 10. Change score")).assertCountEquals(1)
        // MyAnimeList keeps its rewatch line and stepper.
        onNodeWithTag("mal-rewatch-count-42", useUnmergedTree = true).assertExists()
        onAllNodesWithTag("mal-episode-stepper", useUnmergedTree = true).assertCountEquals(1)
        capture("cards-trakt-mdblist-simkl-mal")
    }

    @Test
    fun statusMenuOffersOnlyPickableStatusesAndLeaving() = runDesktopComposeUiTest(width = 900, height = 700) {
        val mdb = mdbListService().entries.single().copy(status = ListStatus.PLAN_TO_WATCH)
        val changes = mutableListOf<ListChange>()
        setContent {
            Page {
                Column(Modifier.padding(24.dp).width(560.dp)) {
                    ListEntryCard(mdb, mdbListTabCaps(mdb.type), saving = false, onChange = { changes += it }, onOpen = {}, onRemoveFromHistory = {}, onEditDetails = null)
                }
            }
        }
        onNodeWithContentDescription("Status: Plan to Watch. Change status").performClick()
        // Watching is derived from history on MDBList and is never offered.
        onAllNodesWithText("Watching").assertCountEquals(0)
        onAllNodesWithText("On Hold").assertCountEquals(0)
        onNodeWithText("Dropped").assertExists()
        capture("mdblist-status-menu")
        onNodeWithText("Remove from Plan to Watch").performClick()
        assertEquals(listOf<ListChange>(ListChange.LeaveStatus(ListStatus.PLAN_TO_WATCH)), changes)
    }

    @Test
    fun malCardKeepsItsMenu() = runDesktopComposeUiTest(width = 900, height = 700) {
        val mal = malService().entries.first()
        var edits by mutableIntStateOf(0)
        setContent {
            Page {
                Column(Modifier.padding(24.dp).width(560.dp)) {
                    ListEntryCard(mal, malTabCaps(mal.type), saving = false, onChange = {}, onOpen = {}, onRemoveFromHistory = null,
                        onEditDetails = { edits++ }, onRemoveEntry = {})
                }
            }
        }
        onNodeWithContentDescription("More actions").performClick()
        onNodeWithText("Edit").assertExists()
        onNodeWithText("Open on MyAnimeList").assertExists()
        onNodeWithText("Remove from list").assertExists()
        onAllNodesWithText("Remove from watch history…").assertCountEquals(0)
        capture("mal-card-menu")
        onNodeWithText("Edit").performClick()
        assertEquals(1, edits)
    }

    @Test
    fun rapidStepperClicksAreSerialised() = runDesktopComposeUiTest(width = 1280, height = 760) {
        val mal = malService(applyDelayMillis = 400)
        setContent { Page { ListsTabContent(null, listOf(mal), prefsFor(ListServiceId.MYANIMELIST), profileId = 1, activeProfile = { 1 }) } }
        waitFor { onAllNodesWithText("2 / 12").fetchSemanticsNodes().isNotEmpty() }
        val more = onAllNodes(hasContentDescription("One more episode") and hasAnyAncestor(hasTestTag("mal-entry-41")), useUnmergedTree = true)
        repeat(3) { more[0].performClick() }
        onNodeWithText("5 / 12").assertExists()
        // Let the first save start, then keep clicking while it is still on its way.
        waitFor { mal.applied.isNotEmpty() }
        repeat(2) { more[0].performClick() }
        onNodeWithText("7 / 12").assertExists()
        waitFor(10_000) {
            mal.applied.sumOf { (it.change as ListChange.StepEpisodes).delta } == 5 && mal.running == 0 &&
                onAllNodesWithText("Saving…").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, mal.maxRunning, "Writes for one entry must never overlap")
        // Every write starts from the entry the previous write returned.
        var expected = 2
        mal.applied.forEach {
            assertEquals(expected, it.entry.watchedEpisodes)
            expected += (it.change as ListChange.StepEpisodes).delta
        }
        assertEquals(7, mal.entries.first { it.key.id == "41" }.watchedEpisodes)
        onNodeWithText("7 / 12").assertExists()
    }

    @Test
    fun profileSwitchDropsPendingEdit() = runDesktopComposeUiTest(width = 1280, height = 760) {
        var profile by mutableIntStateOf(1)
        val mal = malService(profile = { profile })
        setContent {
            Page { ListsTabContent(null, listOf(mal), prefsFor(ListServiceId.MYANIMELIST), profileId = profile, activeProfile = { profile }) }
        }
        waitFor { onAllNodesWithText("2 / 12").fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasContentDescription("One more episode") and hasAnyAncestor(hasTestTag("mal-entry-41")), useUnmergedTree = true)[0]
            .performClick()
        onNodeWithText("3 / 12").assertExists()
        profile = 2
        waitForIdle()
        mainClock.advanceTimeBy(3_000)
        waitForIdle()
        assertTrue(mal.applied.none { it.profile == 2 }, "An edit made in profile 1 was applied in profile 2")
        assertTrue(mal.applied.isEmpty(), "A pending edit from the previous profile must be dropped")
        waitFor { onAllNodesWithText("2 / 12").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun removeFromHistoryAsksAndReportsCount() = runDesktopComposeUiTest(width = 1280, height = 760) {
        val trakt = traktService()
        setContent { Page { ListsTabContent(null, listOf(trakt), prefsFor(ListServiceId.TRAKT), profileId = 1, activeProfile = { 1 }) } }
        waitFor { onAllNodesWithText("The Bear").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Trakt").assertExists()
        onNodeWithContentDescription("More actions").performClick()
        onNodeWithText("Remove from watch history…").performClick()
        onNodeWithText("Remove from watch history?").assertExists()
        onNodeWithText("6 watched entries", substring = true).assertExists()
        capture("remove-from-history-confirmation")
        assertTrue(trakt.removedFromHistory.isEmpty(), "Nothing is removed before the user confirms")
        onNodeWithText("Remove").performClick()
        waitFor { trakt.removedFromHistory.isNotEmpty() }
        waitFor { NuvioToastController.currentToast.value?.message == "Removed 6 entries from watch history" }
        assertEquals("t1", trakt.removedFromHistory.single().key.id)
    }

    @Test
    fun changeThatMarksSeveralEpisodesAsksFirst() = runDesktopComposeUiTest(width = 1280, height = 760) {
        val trakt = traktService()
        trakt.impactOf = { _, change ->
            if (change == ListChange.SetStatus(ListStatus.COMPLETED)) ListChangeImpact(22, false) else ListChangeImpact(0, false)
        }
        setContent { Page { ListsTabContent(null, listOf(trakt), prefsFor(ListServiceId.TRAKT), profileId = 1, activeProfile = { 1 }) } }
        waitFor { onAllNodesWithText("The Bear").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Status: Watching. Change status").performClick()
        onNode(hasText("Completed") and hasAnyAncestor(isPopup())).performClick()
        onNodeWithText("This marks 22 episodes as watched in Starlit and on your connected services.").assertExists()
        assertTrue(trakt.applied.isEmpty())
        onNodeWithText("Continue").performClick()
        waitFor(10_000) { trakt.applied.isNotEmpty() }
        assertEquals(ListChange.SetStatus(ListStatus.COMPLETED), trakt.applied.single().change)
    }

    @Test
    fun impactFailureShowsAnErrorAndDoesNotApply() = runDesktopComposeUiTest(width = 1280, height = 760) {
        val trakt = traktService()
        trakt.impactOf = { _, _ -> throw IllegalStateException("Could not load episodes") }
        setContent { Page { ListsTabContent(null, listOf(trakt), prefsFor(ListServiceId.TRAKT), profileId = 1, activeProfile = { 1 }) } }
        waitFor { onAllNodesWithText("The Bear").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Status: Watching. Change status").performClick()
        onNode(hasText("Completed") and hasAnyAncestor(isPopup())).performClick()
        waitFor { NuvioToastController.currentToast.value?.message?.contains("Could not load episodes") == true }
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertTrue(trakt.applied.isEmpty())
        onNodeWithContentDescription("Status: Watching. Change status").assertExists()
    }

    @Test
    fun disconnectedServiceShowsItsReconnectMessage() = runDesktopComposeUiTest(width = 1280, height = 760) {
        val mal = malService()
        val trakt = traktService()
        setContent {
            Page {
                ListsTabContent(null, listOf(mal, trakt), prefsFor(ListServiceId.MYANIMELIST, ListServiceId.TRAKT, last = ListServiceId.TRAKT),
                    profileId = 1, activeProfile = { 1 }, onRememberService = {})
            }
        }
        waitFor { onAllNodesWithText("The Bear").fetchSemanticsNodes().isNotEmpty() }
        trakt.connected.value = false
        waitFor { onAllNodesWithText("Trakt is not connected").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("The Bear").assertCountEquals(0)
        capture("trakt-disconnected")
        assertTrue(trakt.applied.isEmpty())
    }
}
