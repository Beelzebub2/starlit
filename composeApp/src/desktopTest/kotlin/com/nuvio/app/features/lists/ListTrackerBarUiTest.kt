package com.nuvio.app.features.lists

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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.MetaDetails
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeListService(
    override val id: ListServiceId,
    connected: Boolean = true,
    private val caps: (ListMediaType) -> ListCapabilities,
    private val coverage: (ListTitleKind) -> Boolean = { true },
    var entry: ListEntry? = null,
    var impactOf: (ListChange) -> ListChangeImpact = { ListChangeImpact(0, false) },
    /** When set, a write waits for it before finishing, like a slow multi-step service write. */
    var applyGate: CompletableDeferred<Unit>? = null,
) : ListService {
    override val connected = MutableStateFlow(connected)
    val applied = mutableListOf<ListChange>()
    val completed = mutableListOf<ListChange>()
    var entryForCalls = 0
    override val sorts: List<ListSort> = emptyList()
    override fun covers(kind: ListTitleKind, meta: MetaDetails) = coverage(kind)
    override fun capabilities(type: ListMediaType) = caps(type)
    override fun profileUrl(): String? = null
    override suspend fun summary(): ListSummary? = null
    override suspend fun page(status: ListStatus?, sort: ListSort, offset: Int): ListPage = error("unused")
    override suspend fun entryFor(meta: MetaDetails): ListEntry? {
        entryForCalls++
        return entry
    }
    override suspend fun impact(entry: ListEntry, change: ListChange): ListChangeImpact = impactOf(change)
    override suspend fun apply(entry: ListEntry, change: ListChange): ListEntry {
        applied += change
        applyGate?.await()
        completed += change
        return when (change) {
            is ListChange.SetStatus -> entry.copy(status = change.status)
            is ListChange.SetScore -> entry.copy(score = change.score)
            else -> entry
        }
    }
    override suspend fun removeFromHistory(entry: ListEntry): Int = error("unused")
}

// Capabilities as the real adapters declare them (see the adapters' capabilities()).
private val malCaps = { _: ListMediaType ->
    ListCapabilities(ListStatus.entries, ListStatus.entries.toSet(), score = true, episodeProgress = true,
        rewatch = true, changeEntry = true, removeFromHistory = false)
}
private val simklCaps = { _: ListMediaType ->
    ListCapabilities(ListStatus.entries, ListStatus.entries.toSet(), score = true, episodeProgress = true,
        rewatch = false, changeEntry = false, removeFromHistory = false)
}
private val traktCaps = { _: ListMediaType ->
    ListCapabilities(
        statuses = listOf(ListStatus.WATCHING, ListStatus.COMPLETED, ListStatus.DROPPED, ListStatus.PLAN_TO_WATCH),
        pickableStatuses = setOf(ListStatus.PLAN_TO_WATCH, ListStatus.DROPPED, ListStatus.COMPLETED),
        score = true, episodeProgress = true, rewatch = false, changeEntry = false, removeFromHistory = true,
        leavableStatuses = setOf(ListStatus.PLAN_TO_WATCH, ListStatus.DROPPED),
    )
}
private val mdbListCaps = { _: ListMediaType ->
    ListCapabilities(
        statuses = listOf(ListStatus.WATCHING, ListStatus.COMPLETED, ListStatus.DROPPED, ListStatus.PLAN_TO_WATCH),
        pickableStatuses = setOf(ListStatus.PLAN_TO_WATCH, ListStatus.COMPLETED, ListStatus.DROPPED),
        score = false, episodeProgress = false, rewatch = false, changeEntry = false, removeFromHistory = true,
        leavableStatuses = setOf(ListStatus.PLAN_TO_WATCH, ListStatus.DROPPED), dropRequiresHistory = true,
    )
}

private fun capsFor(id: ListServiceId): ListCapabilities = when (id) {
    ListServiceId.MYANIMELIST -> malCaps(ListMediaType.SERIES)
    ListServiceId.SIMKL -> simklCaps(ListMediaType.SERIES)
    ListServiceId.TRAKT -> traktCaps(ListMediaType.SERIES)
    ListServiceId.MDBLIST -> mdbListCaps(ListMediaType.SERIES)
}

private fun entry(
    status: ListStatus?,
    service: ListServiceId = ListServiceId.MYANIMELIST,
    score: Int = 0,
    watched: Int = 0,
    rewatching: Boolean = false,
    rewatchCount: Int = 0,
    type: ListMediaType = ListMediaType.SERIES,
) = ListEntry(
    key = ListEntryKey(service, "series:tt0000001"), title = "Example", subtitle = null, posterUrl = null,
    type = type, anime = service == ListServiceId.MYANIMELIST, facts = emptyList(), communityScore = null,
    status = status, score = score, watchedEpisodes = watched, totalEpisodes = 12, airing = false,
    rewatching = rewatching, rewatchCount = rewatchCount, catalogType = "series", catalogId = "tt0000001", siteUrl = null,
)

private val allOnTitlePages = ListPreferences(
    order = ListServiceId.entries.toList(), inTab = emptySet(), onTitlePages = ListServiceId.entries.toSet(), lastTabService = null,
)

private val seriesMeta = MetaDetails(id = "tt0000001", type = "series", name = "Example")
private val animeMeta = MetaDetails(id = "tt0000002", type = "series", name = "Example Anime", genres = listOf("Anime"))

@OptIn(ExperimentalTestApi::class)
class ListTrackerBarUiTest {
    private val previousLocale = Locale.getDefault()

    // The bar uses translated strings; pin English so the assertions are stable.
    @BeforeTest fun useEnglish() = Locale.setDefault(Locale.US)
    @AfterTest fun restoreLocale() = Locale.setDefault(previousLocale)

    private val heroBackdrop = Brush.linearGradient(listOf(Color(0xFF3B4A5C), Color(0xFF1B2230), Color(0xFF0E1117)))

    @Composable
    private fun Bar(
        entry: ListEntry?,
        service: ListServiceId = ListServiceId.MYANIMELIST,
        others: List<ListServiceId> = emptyList(),
        linked: Boolean = true,
        working: Boolean = false,
        error: String? = null,
        onSwitch: (ListServiceId) -> Unit = {},
        onChange: (ListChange) -> Unit = {},
    ) = ListTrackerBar(
        service = service,
        otherServices = others,
        capabilities = capsFor(service),
        entry = entry,
        working = working,
        error = error,
        onSwitch = onSwitch,
        onChange = onChange,
        onChooseEntry = if (service == ListServiceId.MYANIMELIST) ({}) else null,
        onRetry = {},
        linked = linked,
    )

    @Composable
    private fun Hero(content: @Composable () -> Unit) {
        NuvioTheme(darkTheme = true) {
            Box(Modifier.fillMaxSize().background(heroBackdrop).padding(24.dp)) { content() }
        }
    }

    private fun malHooks(id: Int?, connected: Boolean = true) = MalTitleHooks(
        connected = connected, busy = false, linkRevision = 0,
        resolveId = { id }, onConnect = {}, onChooseEntry = {},
    )

    private fun ComposeUiTest.capture(name: String) {
        waitForIdle()
        val directory = File("build/list-tracker-capture").apply { mkdirs() }
        // An open menu or dialog is its own popup root; the last root captures the scene with it on top.
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test
    fun rendersEveryTrackerState() = runDesktopComposeUiTest(width = 900, height = 560) {
        setContent {
            Hero {
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Bar(entry(ListStatus.WATCHING, score = 8, watched = 7))
                    Bar(entry(ListStatus.COMPLETED, score = 9, watched = 12, rewatching = true, rewatchCount = 2))
                    Bar(entry(ListStatus.COMPLETED, watched = 12, rewatchCount = 1))
                    Bar(entry(ListStatus.PLAN_TO_WATCH), working = true)
                    Bar(entry = null)
                    Bar(entry = null, linked = false)
                    ListConnectPrompt(ListServiceId.MYANIMELIST, onConnect = {})
                    Bar(entry(ListStatus.ON_HOLD, score = 6, watched = 3), error = "MyAnimeList did not respond in time.")
                }
            }
        }
        onAllNodesWithText("Rewatches: 0").assertCountEquals(3)
        onNodeWithText("Rewatches: 2").assertExists()
        onNodeWithText("Rewatches: 1").assertExists()
        onNodeWithText("Link MAL entry").assertExists()
        onNodeWithText("Connect MyAnimeList").assertExists()
        capture("myanimelist")
    }

    @Test
    fun completedRewatchMenuTogglesAndFinishes() = runDesktopComposeUiTest(width = 900, height = 520) {
        val changes = mutableListOf<ListChange>()
        setContent {
            Hero { Bar(entry(ListStatus.COMPLETED, score = 9, watched = 12, rewatching = true, rewatchCount = 2), onChange = { changes += it }) }
        }
        onNodeWithContentDescription("Rewatches: 2, Rewatching").assertHasClickAction().performClick()
        capture("rewatch-menu")
        onNodeWithText("Finish rewatch").performClick()
        assertEquals(listOf<ListChange>(ListChange.FinishRewatch), changes)
        onNodeWithContentDescription("Rewatches: 2, Rewatching").performClick()
        // The bar's "Rewatching" tag plus the menu's toggle row.
        onAllNodesWithText("Rewatching", useUnmergedTree = true).onLast().performClick()
        assertEquals(ListChange.SetRewatching(false), changes.last())
    }

    @Test
    fun rewatchCountIsInformationalUntilTheTitleIsCompleted() = runDesktopComposeUiTest(width = 900, height = 200) {
        setContent { NuvioTheme(darkTheme = true) { Bar(entry(ListStatus.WATCHING, score = 8, watched = 7)) } }
        onNodeWithContentDescription("Rewatches: 0").assertHasNoClickAction()
        onNodeWithContentDescription("Change MAL entry").assertHasClickAction()
    }

    @Test
    fun statusAndRatingMenusReportTheChosenValue() = runDesktopComposeUiTest(width = 900, height = 760) {
        val changes = mutableListOf<ListChange>()
        setContent { Hero { Bar(entry(ListStatus.WATCHING, score = 8, watched = 7), onChange = { changes += it }) } }
        onNodeWithContentDescription("MyAnimeList status: Watching").performClick()
        capture("status-menu")
        onNodeWithText("Completed").performClick()
        assertEquals(ListChange.SetStatus(ListStatus.COMPLETED), changes.last())
        onNodeWithContentDescription("MyAnimeList rating: 8 out of 10").performClick()
        capture("rating-menu")
        onNodeWithText("Clear rating").performClick()
        assertEquals(ListChange.SetScore(0), changes.last())
    }

    @Test
    fun capturesEachServiceOnItsOwn() {
        val states = mapOf(
            ListServiceId.SIMKL to listOf(
                entry(ListStatus.WATCHING, ListServiceId.SIMKL, score = 7), entry(ListStatus.ON_HOLD, ListServiceId.SIMKL),
                entry(ListStatus.COMPLETED, ListServiceId.SIMKL, score = 8, type = ListMediaType.MOVIE), null,
            ),
            ListServiceId.TRAKT to listOf(
                entry(ListStatus.WATCHING, ListServiceId.TRAKT, score = 9), entry(ListStatus.DROPPED, ListServiceId.TRAKT),
                entry(ListStatus.COMPLETED, ListServiceId.TRAKT, score = 6, type = ListMediaType.MOVIE), null,
            ),
            ListServiceId.MDBLIST to listOf(
                entry(ListStatus.WATCHING, ListServiceId.MDBLIST), entry(ListStatus.PLAN_TO_WATCH, ListServiceId.MDBLIST),
                entry(ListStatus.COMPLETED, ListServiceId.MDBLIST, type = ListMediaType.MOVIE), null,
            ),
        )
        states.forEach { (service, entries) ->
            runDesktopComposeUiTest(width = 900, height = 330) {
                setContent {
                    Hero {
                        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            entries.forEachIndexed { index, item ->
                                Bar(item, service = service, others = if (index == 0) listOf(ListServiceId.MYANIMELIST) else emptyList())
                            }
                        }
                    }
                }
                capture(service.storageId)
            }
        }
    }

    @Test
    fun rendersEachServiceWithOnlyItsControls() = runDesktopComposeUiTest(width = 900, height = 470) {
        setContent {
            Hero {
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Bar(entry(ListStatus.WATCHING, ListServiceId.SIMKL, score = 7), service = ListServiceId.SIMKL)
                    Bar(entry = null, service = ListServiceId.SIMKL)
                    Bar(entry(ListStatus.WATCHING, ListServiceId.TRAKT, score = 9), service = ListServiceId.TRAKT, others = listOf(ListServiceId.SIMKL))
                    Bar(entry = null, service = ListServiceId.TRAKT)
                    Bar(entry(ListStatus.PLAN_TO_WATCH, ListServiceId.MDBLIST), service = ListServiceId.MDBLIST)
                    Bar(entry(ListStatus.COMPLETED, ListServiceId.MDBLIST, type = ListMediaType.MOVIE), service = ListServiceId.MDBLIST)
                }
            }
        }
        // MDBList has no score; Simkl and Trakt rate; nobody but MAL has rewatches or entry editing.
        onAllNodesWithContentDescription("Rate on Simkl").assertCountEquals(1)
        onAllNodesWithContentDescription("Rate on Trakt").assertCountEquals(1)
        onAllNodesWithContentDescription("Rate on MDBList").assertCountEquals(0)
        onAllNodesWithContentDescription("MDBList rating", substring = true).assertCountEquals(0)
        onAllNodes(hasText("Rewatches", substring = true)).assertCountEquals(0)
        onAllNodesWithContentDescription("Change MAL entry").assertCountEquals(0)
        onNodeWithContentDescription("Trakt status: Watching").assertExists()
        capture("services")
    }

    @Test
    fun switcherListsOtherServicesAndReportsChoice() = runDesktopComposeUiTest(width = 900, height = 420) {
        val switched = mutableListOf<ListServiceId>()
        setContent {
            Hero {
                Bar(entry(ListStatus.WATCHING, ListServiceId.TRAKT, score = 9), service = ListServiceId.TRAKT,
                    others = listOf(ListServiceId.SIMKL), onSwitch = { switched += it })
            }
        }
        onNodeWithContentDescription("Trakt, switch list service").assertHasClickAction().performClick()
        capture("switcher-open")
        onNodeWithText("Simkl").performClick()
        assertEquals(listOf(ListServiceId.SIMKL), switched)
    }

    @Test
    fun barWithoutOtherServicesHasNoSwitcher() = runDesktopComposeUiTest(width = 900, height = 200) {
        setContent { Hero { Bar(entry(ListStatus.WATCHING, ListServiceId.TRAKT), service = ListServiceId.TRAKT) } }
        onAllNodesWithContentDescription("Trakt, switch list service").assertCountEquals(0)
    }

    @Test
    fun traktBarHasNoOnHoldAndNoRewatch() = runDesktopComposeUiTest(width = 900, height = 520) {
        val changes = mutableListOf<ListChange>()
        setContent {
            Hero { Bar(entry(ListStatus.WATCHING, ListServiceId.TRAKT, score = 9, watched = 4), service = ListServiceId.TRAKT, onChange = { changes += it }) }
        }
        onAllNodes(hasText("Rewatches", substring = true)).assertCountEquals(0)
        onNodeWithContentDescription("Trakt status: Watching").performClick()
        capture("trakt-status-menu")
        onAllNodesWithText("On Hold").assertCountEquals(0)
        // Watching is derived from history: it shows on the bar but is never offered.
        onAllNodesWithText("Watching").assertCountEquals(1)
        onNodeWithText("Plan to Watch").assertExists()
        onAllNodesWithText("Remove from", substring = true).assertCountEquals(0)
        onNodeWithText("Dropped").performClick()
        assertEquals(listOf<ListChange>(ListChange.SetStatus(ListStatus.DROPPED)), changes)
    }

    @Test
    fun leavableCurrentStatusOffersRemove() = runDesktopComposeUiTest(width = 900, height = 520) {
        val changes = mutableListOf<ListChange>()
        setContent {
            Hero { Bar(entry(ListStatus.PLAN_TO_WATCH, ListServiceId.TRAKT), service = ListServiceId.TRAKT, onChange = { changes += it }) }
        }
        onNodeWithContentDescription("Trakt status: Plan to Watch").performClick()
        onNodeWithText("Remove from Plan to Watch").performClick()
        assertEquals(listOf<ListChange>(ListChange.LeaveStatus(ListStatus.PLAN_TO_WATCH)), changes)
    }

    @Test
    fun simklPlanToWatchIsNotLeavable() = runDesktopComposeUiTest(width = 900, height = 520) {
        setContent { Hero { Bar(entry(ListStatus.PLAN_TO_WATCH, ListServiceId.SIMKL), service = ListServiceId.SIMKL) } }
        onNodeWithContentDescription("Simkl status: Plan to Watch").performClick()
        onAllNodesWithText("Remove from", substring = true).assertCountEquals(0)
        onNodeWithText("On Hold").assertExists()
    }

    @Test
    fun completedOnSeriesAsksForConfirmation() = runDesktopComposeUiTest(width = 900, height = 560) {
        val trakt = FakeListService(ListServiceId.TRAKT, caps = traktCaps, entry = entry(ListStatus.WATCHING, ListServiceId.TRAKT, watched = 7),
            impactOf = { if (it == ListChange.SetStatus(ListStatus.COMPLETED)) ListChangeImpact(5, false) else ListChangeImpact(0, false) })
        setContent { Hero { ListTrackerControls(seriesMeta, malHooks(null, connected = false), listOf(trakt), allOnTitlePages) } }
        onNodeWithContentDescription("Trakt status: Watching").performClick()
        onNodeWithText("Completed").performClick()
        onNodeWithText("5", substring = true).assertExists()
        capture("confirmation")
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertTrue(trakt.applied.isEmpty())
        onNodeWithContentDescription("Trakt status: Watching").performClick()
        onNodeWithText("Completed").performClick()
        onNodeWithText("Continue").performClick()
        waitForIdle()
        assertEquals(listOf<ListChange>(ListChange.SetStatus(ListStatus.COMPLETED)), trakt.applied)
        onNodeWithContentDescription("Trakt status: Completed").assertExists()
    }

    @Test
    fun completingAMovieAppliesWithoutConfirmation() = runDesktopComposeUiTest(width = 900, height = 560) {
        val film = MetaDetails(id = "tt0000003", type = "movie", name = "Film")
        val trakt = FakeListService(ListServiceId.TRAKT, caps = traktCaps,
            entry = entry(ListStatus.PLAN_TO_WATCH, ListServiceId.TRAKT, type = ListMediaType.MOVIE),
            impactOf = { if (it == ListChange.SetStatus(ListStatus.COMPLETED)) ListChangeImpact(1, false) else ListChangeImpact(0, false) })
        setContent { Hero { ListTrackerControls(film, malHooks(null, connected = false), listOf(trakt), allOnTitlePages) } }
        onNodeWithContentDescription("Trakt status: Plan to Watch").performClick()
        onNodeWithText("Completed").performClick()
        waitForIdle()
        onAllNodesWithText("Update Trakt?").assertCountEquals(0)
        assertEquals(listOf<ListChange>(ListChange.SetStatus(ListStatus.COMPLETED)), trakt.applied)
        onNodeWithContentDescription("Trakt status: Completed").assertExists()
    }

    @Test
    fun droppingThatHidesFromContinueWatchingSaysSo() = runDesktopComposeUiTest(width = 900, height = 560) {
        val trakt = FakeListService(ListServiceId.TRAKT, caps = traktCaps, entry = entry(ListStatus.WATCHING, ListServiceId.TRAKT, watched = 7),
            impactOf = { ListChangeImpact(0, it == ListChange.SetStatus(ListStatus.DROPPED)) })
        setContent { Hero { ListTrackerControls(seriesMeta, malHooks(null, connected = false), listOf(trakt), allOnTitlePages) } }
        onNodeWithContentDescription("Trakt status: Watching").performClick()
        onNodeWithText("Dropped").performClick()
        onNodeWithText("Dropping also hides it from Continue Watching.").assertExists()
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertTrue(trakt.applied.isEmpty())
    }

    @Test
    fun impactFailureShowsErrorAndDoesNotApply() = runDesktopComposeUiTest(width = 900, height = 560) {
        val trakt = FakeListService(ListServiceId.TRAKT, caps = traktCaps, entry = entry(ListStatus.WATCHING, ListServiceId.TRAKT, watched = 7),
            impactOf = { throw IllegalStateException("Trakt is unreachable") })
        setContent { Hero { ListTrackerControls(seriesMeta, malHooks(null, connected = false), listOf(trakt), allOnTitlePages) } }
        onNodeWithContentDescription("Trakt status: Watching").performClick()
        onNodeWithText("Completed").performClick()
        waitForIdle()
        onNodeWithText("Trakt is unreachable").assertExists()
        assertTrue(trakt.applied.isEmpty())
    }

    @Test
    fun notOnListAppliesToANewEntryForTheTitle() = runDesktopComposeUiTest(width = 900, height = 560) {
        val trakt = FakeListService(ListServiceId.TRAKT, caps = traktCaps, entry = null)
        setContent { Hero { ListTrackerControls(seriesMeta, malHooks(null, connected = false), listOf(trakt), allOnTitlePages) } }
        onNodeWithContentDescription("Trakt status: Set status").performClick()
        onNodeWithText("Plan to Watch").performClick()
        waitForIdle()
        assertEquals(listOf<ListChange>(ListChange.SetStatus(ListStatus.PLAN_TO_WATCH)), trakt.applied)
        onNodeWithContentDescription("Trakt status: Plan to Watch").assertExists()
    }

    @Test
    fun switchingServicesRendersTheChosenOne() = runDesktopComposeUiTest(width = 900, height = 420) {
        val trakt = FakeListService(ListServiceId.TRAKT, caps = traktCaps, entry = entry(ListStatus.WATCHING, ListServiceId.TRAKT))
        val simkl = FakeListService(ListServiceId.SIMKL, caps = simklCaps, entry = entry(ListStatus.ON_HOLD, ListServiceId.SIMKL))
        val prefs = allOnTitlePages.copy(order = listOf(ListServiceId.TRAKT, ListServiceId.SIMKL, ListServiceId.MYANIMELIST, ListServiceId.MDBLIST))
        setContent { Hero { ListTrackerControls(seriesMeta, malHooks(null, connected = false), listOf(simkl, trakt), prefs) } }
        onNodeWithContentDescription("Trakt status: Watching").assertExists()
        onNodeWithContentDescription("Trakt, switch list service").performClick()
        onNodeWithText("Simkl").performClick()
        waitForIdle()
        onNodeWithContentDescription("Simkl status: On Hold").assertExists()
        onAllNodesWithContentDescription("Trakt status", substring = true).assertCountEquals(0)
    }

    @Test
    fun disconnectFallsBackToNextCandidate() = runDesktopComposeUiTest(width = 900, height = 420) {
        val trakt = FakeListService(ListServiceId.TRAKT, caps = traktCaps, entry = entry(ListStatus.WATCHING, ListServiceId.TRAKT))
        val simkl = FakeListService(ListServiceId.SIMKL, caps = simklCaps, entry = entry(ListStatus.ON_HOLD, ListServiceId.SIMKL))
        val prefs = allOnTitlePages.copy(order = listOf(ListServiceId.TRAKT, ListServiceId.SIMKL, ListServiceId.MYANIMELIST, ListServiceId.MDBLIST))
        setContent { Hero { ListTrackerControls(seriesMeta, malHooks(null, connected = false), listOf(trakt, simkl), prefs) } }
        onNodeWithContentDescription("Trakt status: Watching").assertExists()
        trakt.connected.value = false
        waitForIdle()
        onNodeWithContentDescription("Simkl status: On Hold").assertExists()
        onAllNodesWithContentDescription("Trakt", substring = true).assertCountEquals(0)
        assertTrue(trakt.applied.isEmpty() && simkl.applied.isEmpty())
    }

    @Test
    fun mdbListOffersDroppedOnlyOnceTheTitleHasWatchedHistory() = runDesktopComposeUiTest(width = 900, height = 520) {
        var shown by mutableStateOf<ListEntry?>(null)
        setContent { Hero { Bar(shown, service = ListServiceId.MDBLIST) } }
        // Not on the list yet: MDBList would reject a drop, so it is not offered.
        onNodeWithContentDescription("MDBList status: Set status").performClick()
        onNodeWithText("Completed").assertExists()
        onAllNodesWithText("Dropped").assertCountEquals(0)
        onNodeWithText("Plan to Watch").performClick()
        shown = entry(ListStatus.PLAN_TO_WATCH, ListServiceId.MDBLIST, watched = 0)
        waitForIdle()
        onNodeWithContentDescription("MDBList status: Plan to Watch").performClick()
        onNodeWithText("Completed").assertExists()
        onAllNodesWithText("Dropped").assertCountEquals(0)
        onAllNodesWithText("Plan to Watch").onLast().performClick()
        shown = entry(ListStatus.WATCHING, ListServiceId.MDBLIST, watched = 3)
        waitForIdle()
        onNodeWithContentDescription("MDBList status: Watching").performClick()
        onNodeWithText("Dropped").assertExists()
    }

    private fun slowMalHooks(resolved: CompletableDeferred<Int?>) = MalTitleHooks(
        connected = true, busy = false, linkRevision = 0, resolveId = { resolved.await() }, onConnect = {}, onChooseEntry = {},
    )

    @Test
    fun slowMalResolveThenSwitchKeepsTheBarAndFinishesAStartedWrite() = runDesktopComposeUiTest(width = 900, height = 520) {
        // MAL is first in the order and connected, but its id takes a while to resolve for this (non-anime-looking)
        // title, and in the end there is no MAL entry for it.
        val resolved = CompletableDeferred<Int?>()
        val mal = FakeListService(ListServiceId.MYANIMELIST, caps = malCaps, coverage = { it.anime }, entry = null)
        val trakt = FakeListService(ListServiceId.TRAKT, caps = traktCaps, entry = entry(ListStatus.WATCHING, ListServiceId.TRAKT))
        var onPage by mutableStateOf(true)
        setContent { Hero { if (onPage) ListTrackerControls(seriesMeta, slowMalHooks(resolved), listOf(mal, trakt), allOnTitlePages) } }
        waitForIdle()
        // Trakt shows (and loads) at once; the bar does not wait for the MAL id.
        onNodeWithContentDescription("Trakt status: Watching").assertExists()
        onAllNodesWithContentDescription("MyAnimeList", substring = true).assertCountEquals(0)
        assertEquals(1, trakt.entryForCalls)
        assertEquals(0, mal.entryForCalls)

        // The user starts a slow write; the MAL lookup then comes back empty and Trakt stays.
        val gate = CompletableDeferred<Unit>()
        trakt.applyGate = gate
        onNodeWithContentDescription("Trakt status: Watching").performClick()
        onNodeWithText("Plan to Watch").performClick()
        waitUntil(timeoutMillis = 5_000) { trakt.applied.isNotEmpty() }
        resolved.complete(null)
        waitForIdle()
        onNodeWithContentDescription("Trakt status: Watching").assertExists()
        onAllNodesWithContentDescription("MyAnimeList", substring = true).assertCountEquals(0)
        assertEquals(1, trakt.entryForCalls, "Trakt was never reloaded")

        // Leaving the page before the write finishes does not cancel it.
        onPage = false
        waitForIdle()
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { trakt.completed.isNotEmpty() }
        assertEquals(listOf<ListChange>(ListChange.SetStatus(ListStatus.PLAN_TO_WATCH)), trakt.completed)
    }

    @Test
    fun lateMalIdKeepsTheShownServiceAndOnlyAddsMalToTheSwitcher() = runDesktopComposeUiTest(width = 900, height = 520) {
        val resolved = CompletableDeferred<Int?>()
        val mal = FakeListService(ListServiceId.MYANIMELIST, caps = malCaps, coverage = { it.anime }, entry = null)
        val trakt = FakeListService(ListServiceId.TRAKT, caps = traktCaps, entry = entry(ListStatus.WATCHING, ListServiceId.TRAKT))
        setContent { Hero { ListTrackerControls(seriesMeta, slowMalHooks(resolved), listOf(mal, trakt), allOnTitlePages) } }
        waitForIdle()
        onNodeWithContentDescription("Trakt status: Watching").assertExists()
        // A write is under way when the MAL id arrives.
        val gate = CompletableDeferred<Unit>()
        trakt.applyGate = gate
        onNodeWithContentDescription("Trakt status: Watching").performClick()
        onNodeWithText("Plan to Watch").performClick()
        waitUntil(timeoutMillis = 5_000) { trakt.applied.isNotEmpty() }

        resolved.complete(42)
        waitForIdle()
        // MyAnimeList now ranks first but does not take over the bar, and Trakt is not reloaded.
        onAllNodesWithContentDescription("MyAnimeList status", substring = true).assertCountEquals(0)
        assertEquals(1, trakt.entryForCalls)
        assertEquals(0, mal.entryForCalls)
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { trakt.completed.isNotEmpty() }
        waitForIdle()
        onNodeWithContentDescription("Trakt status: Plan to Watch").assertExists()

        // MyAnimeList is offered in the switcher and can be picked.
        onNodeWithContentDescription("Trakt, switch list service").performClick()
        onNodeWithText("MyAnimeList").performClick()
        waitForIdle()
        onNodeWithContentDescription("MyAnimeList status: Set status").assertExists()
        assertEquals(1, mal.entryForCalls)
    }

    @Test
    fun animeLookingTitleShowsMalImmediately() = runDesktopComposeUiTest(width = 900, height = 520) {
        val resolved = CompletableDeferred<Int?>()
        val mal = FakeListService(ListServiceId.MYANIMELIST, caps = malCaps, coverage = { it.anime }, entry = null)
        val trakt = FakeListService(ListServiceId.TRAKT, caps = traktCaps, entry = entry(ListStatus.WATCHING, ListServiceId.TRAKT))
        setContent { Hero { ListTrackerControls(animeMeta, slowMalHooks(resolved), listOf(mal, trakt), allOnTitlePages) } }
        waitForIdle()
        // MyAnimeList is on the bar (waiting for its id) and Trakt is only in the switcher, never loaded.
        onNodeWithContentDescription("MyAnimeList status: Set status").assertExists()
        onAllNodesWithContentDescription("Trakt status", substring = true).assertCountEquals(0)
        assertEquals(0, trakt.entryForCalls)
        assertEquals(0, mal.entryForCalls)
        resolved.complete(42)
        waitForIdle()
        onNodeWithContentDescription("MyAnimeList status: Set status").assertExists()
        onNodeWithContentDescription("Rate on MyAnimeList").assertExists()
        assertEquals(1, mal.entryForCalls)
        assertEquals(0, trakt.entryForCalls)
    }

    @Test
    fun malWaitsForItsIdOnlyWhenNoOtherServiceCanShow() = runDesktopComposeUiTest(width = 900, height = 520) {
        val resolved = CompletableDeferred<Int?>()
        val mal = FakeListService(ListServiceId.MYANIMELIST, caps = malCaps, coverage = { it.anime }, entry = null)
        setContent { Hero { ListTrackerControls(seriesMeta, slowMalHooks(resolved), listOf(mal), allOnTitlePages) } }
        waitForIdle()
        // Nothing else covers the title, so MyAnimeList holds the bar in its waiting state.
        onNodeWithContentDescription("MyAnimeList status: Set status").assertExists()
        assertEquals(0, mal.entryForCalls)
        resolved.complete(42)
        waitForIdle()
        onNodeWithContentDescription("MyAnimeList status: Set status").assertExists()
        onNodeWithContentDescription("Rate on MyAnimeList").assertExists()
        assertEquals(1, mal.entryForCalls)
    }

    @Test
    fun animeWithMalButNoIdShowsLinkEntry() = runDesktopComposeUiTest(width = 900, height = 300) {
        val mal = FakeListService(ListServiceId.MYANIMELIST, caps = malCaps, coverage = { it.anime }, entry = null)
        setContent { Hero { ListTrackerControls(animeMeta, malHooks(null), listOf(mal), allOnTitlePages) } }
        waitForIdle()
        onNodeWithText("Link MAL entry").assertExists()
        onAllNodesWithContentDescription("MyAnimeList status", substring = true).assertCountEquals(0)
    }

    @Test
    fun animeWithResolvedMalIdShowsNotOnListStates() = runDesktopComposeUiTest(width = 900, height = 300) {
        val mal = FakeListService(ListServiceId.MYANIMELIST, caps = malCaps, coverage = { it.anime }, entry = null)
        setContent { Hero { ListTrackerControls(animeMeta, malHooks(42), listOf(mal), allOnTitlePages) } }
        waitForIdle()
        onNodeWithContentDescription("MyAnimeList status: Set status").assertExists()
        onNodeWithContentDescription("Rate on MyAnimeList").assertExists()
        onNodeWithContentDescription("Change MAL entry").assertExists()
    }

    @Test
    fun noCandidateAnimeShowsConnectMyAnimeList() = runDesktopComposeUiTest(width = 900, height = 300) {
        val mal = FakeListService(ListServiceId.MYANIMELIST, connected = false, caps = malCaps, coverage = { it.anime })
        setContent {
            Hero {
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    ListTrackerControls(animeMeta, malHooks(null, connected = false), listOf(mal), allOnTitlePages)
                    // A non-anime title with no candidate shows nothing at all.
                    ListTrackerControls(seriesMeta, malHooks(null, connected = false), listOf(mal), allOnTitlePages)
                }
            }
        }
        waitForIdle()
        onAllNodesWithText("Connect MyAnimeList").assertCountEquals(1)
        capture("connect-prompt")
    }
}
