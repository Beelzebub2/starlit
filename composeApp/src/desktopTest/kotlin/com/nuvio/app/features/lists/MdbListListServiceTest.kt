package com.nuvio.app.features.lists

/*
 * Dropped-write outcome: DROPPED IS PICKABLE AND LEAVABLE FOR SERIES (not for movies).
 * Evidence: MDBList's OpenAPI schema (https://api.mdblist.com/schema/?format=json, rendered at
 * https://api.mdblist.com/docs/) documents POST /sync/dropped ("Mark Shows as Dropped", body
 * {"shows":[{"ids":{...},"dropped_at":"<iso>"}]}) and POST /sync/dropped/remove ("Remove Dropped Status").
 * Caveat from the same schema: marking dropped "updates existing watched state only", so dropping a show with no
 * watched history comes back as not_found and surfaces as an error (see MdbListDroppedServiceTest).
 */

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.mdblist.MdbListIds
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeMdbListPort(
    var snapshot: MdbListListSnapshot = MdbListListSnapshot(),
    val metas: Map<String, MetaDetails> = emptyMap(),
) : MdbListListPort {
    val calls = mutableListOf<String>()
    var loads = 0
    var now = 1_000L

    override fun activeProfileId(): Int = 1
    override suspend fun load(): MdbListListSnapshot {
        loads += 1
        return snapshot
    }

    override suspend fun setWatchlisted(item: LibraryItem, inWatchlist: Boolean) {
        calls += "watchlist:${if (inWatchlist) "add" else "remove"}:${item.id}"
    }

    override suspend fun setDropped(media: TrackingMediaReference, dropped: Boolean) {
        calls += "dropped:${if (dropped) "add" else "remove"}:${media.ids.imdb}"
    }

    override suspend fun removeHistory(media: TrackingMediaReference) {
        calls += "history:remove:${media.kind.name.lowercase()}:${media.ids.imdb}"
    }

    override fun cachedMeta(type: String, id: String): MetaDetails? = metas[id]
    override suspend fun fetchMeta(type: String, id: String): MetaDetails? = metas[id]
    override fun isFullyWatchedLocally(type: String, id: String): Boolean = false
    override fun todayIsoDate(): String = "2026-01-01"
    override fun nowEpochMs(): Long = now
}

private fun watchlist(id: String, type: String, name: String) =
    LibraryItem(id = id, type = type, name = name, imdbId = id, savedAtEpochMs = 100L)

private fun episodes(id: String, name: String, count: Int) = (1..count).map {
    WatchedItem(id = id, type = "series", name = name, season = 1, episode = it, markedAtEpochMs = 200L + it)
}

private fun show(id: String, episodes: Int) = seriesMeta(episodes).copy(id = id)

class MdbListListServiceTest {
    private fun adapter(port: FakeMdbListPort = FakeMdbListPort(), history: FakeHistoryPort = FakeHistoryPort()) =
        MdbListListAdapter(port, MutableStateFlow(true), ListHistoryWrites(history), { "tester" })

    @Test fun mdbListHasNoScoreOrStepper() {
        val svc = adapter()
        val series = svc.capabilities(ListMediaType.SERIES)
        assertEquals(listOf(ListStatus.COMPLETED, ListStatus.DROPPED, ListStatus.PLAN_TO_WATCH), series.statuses)
        assertEquals(setOf(ListStatus.PLAN_TO_WATCH, ListStatus.COMPLETED, ListStatus.DROPPED), series.pickableStatuses)
        assertEquals(setOf(ListStatus.PLAN_TO_WATCH, ListStatus.DROPPED), series.leavableStatuses)
        val movie = svc.capabilities(ListMediaType.MOVIE)
        assertEquals(listOf(ListStatus.COMPLETED, ListStatus.PLAN_TO_WATCH), movie.statuses)
        assertEquals(setOf(ListStatus.PLAN_TO_WATCH, ListStatus.COMPLETED), movie.pickableStatuses)
        assertEquals(setOf(ListStatus.PLAN_TO_WATCH), movie.leavableStatuses)
        listOf(series, movie).forEach { caps ->
            assertFalse(caps.score || caps.episodeProgress || caps.rewatch || caps.changeEntry)
            assertTrue(caps.removeFromHistory)
            assertFalse(ListStatus.WATCHING in caps.statuses || ListStatus.ON_HOLD in caps.statuses)
        }
    }

    @Test fun mdbListStatusesAreDerived(): Unit = runBlocking {
        val port = FakeMdbListPort(
            MdbListListSnapshot(
                watchlist = listOf(watchlist("tt1", "series", "Alpha"), watchlist("tt9", "series", "Hotel")),
                watched = episodes("tt2", "Bravo", 3) + episodes("tt3", "Charlie", 2) + episodes("tt9", "Hotel", 1) +
                    WatchedItem(id = "tt5", type = "movie", name = "Echo", markedAtEpochMs = 300L),
                dropped = listOf(MdbListIds(imdb = "tt3")),
            ),
            metas = mapOf("tt2" to show("tt2", 3), "tt3" to show("tt3", 5), "tt9" to show("tt9", 8)),
        )
        val svc = adapter(port)
        val byTitle = svc.sorts.first { it.label == "Title" }

        val all = svc.page(null, byTitle, 0).entries
        // Hotel is watchlisted but only partly watched: derived Watching has no MDBList tab, so it is not listed.
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Echo"), all.map { it.title })
        val status = all.associate { it.title to it.status }
        assertEquals(ListStatus.PLAN_TO_WATCH, status["Alpha"])
        assertEquals(ListStatus.COMPLETED, status["Bravo"])
        assertEquals(ListStatus.DROPPED, status["Charlie"])
        assertEquals(ListStatus.COMPLETED, status["Echo"])
        assertTrue(all.all { it.score == 0 })
        assertEquals(listOf("Bravo", "Echo"), svc.page(ListStatus.COMPLETED, byTitle, 0).entries.map { it.title })

        val summary = svc.summary()
        assertEquals(1, summary.counts[ListStatus.PLAN_TO_WATCH])
        assertEquals(2, summary.counts[ListStatus.COMPLETED])
        assertEquals(1, summary.counts[ListStatus.DROPPED])
        assertEquals(0, summary.counts[ListStatus.WATCHING])
        assertEquals(4, summary.counts[null])
        assertEquals("tester", summary.signedInAs)
        assertEquals(1, port.loads)
    }

    @Test fun coversOnlyIdsMdbListAccepts() {
        val svc = adapter()
        assertTrue(svc.covers(ListTitleKind(ListMediaType.SERIES, anime = false), MetaDetails(id = "tt1", type = "series", name = "A")))
        assertTrue(svc.covers(ListTitleKind(ListMediaType.SERIES, anime = true), MetaDetails(id = "tmdb:5", type = "series", name = "A")))
        assertTrue(svc.covers(ListTitleKind(ListMediaType.MOVIE, anime = false), MetaDetails(id = "trakt:5", type = "movie", name = "A")))
        assertFalse(svc.covers(ListTitleKind(ListMediaType.SERIES, anime = true), MetaDetails(id = "mal:5", type = "series", name = "A")))
        assertFalse(svc.covers(ListTitleKind(ListMediaType.SERIES, anime = true), MetaDetails(id = "kitsu:5", type = "series", name = "A")))
    }

    @Test fun planToWatchAndLeaveChangeOnlyTheWatchlistAndKeepHistory(): Unit = runBlocking {
        val port = FakeMdbListPort(
            MdbListListSnapshot(watchlist = listOf(watchlist("tt1", "series", "Show")), watched = episodes("tt1", "Show", 12)),
            metas = mapOf("tt1" to show("tt1", 12)),
        )
        val history = FakeHistoryPort(meta = show("tt1", 12))
        val svc = adapter(port, history)
        val entry = svc.entryFor(show("tt1", 12))!!
        assertEquals(ListStatus.COMPLETED, entry.status)

        svc.apply(entry, ListChange.SetStatus(ListStatus.PLAN_TO_WATCH))
        val left = svc.apply(entry, ListChange.LeaveStatus(ListStatus.PLAN_TO_WATCH))

        assertEquals(listOf("watchlist:add:tt1", "watchlist:remove:tt1"), port.calls)
        assertTrue(history.calls.isEmpty())
        assertEquals(ListStatus.COMPLETED, left.status)
        assertFailsWith<IllegalArgumentException> { svc.apply(entry, ListChange.LeaveStatus(ListStatus.COMPLETED)) }
    }

    @Test fun droppedIsPickableAndLeavableForSeriesOnly(): Unit = runBlocking {
        val port = FakeMdbListPort(
            MdbListListSnapshot(
                watchlist = listOf(watchlist("tt5", "movie", "Film")),
                watched = episodes("tt1", "Show", 2),
            ),
            metas = mapOf("tt1" to show("tt1", 12)),
        )
        val history = FakeHistoryPort(meta = show("tt1", 12))
        val svc = adapter(port, history)
        val dropShow = svc.entryFor(show("tt1", 12))
        assertNull(dropShow, "a partly watched, unlisted show has no MDBList status")

        val fresh = adapter(FakeMdbListPort(
            MdbListListSnapshot(watched = episodes("tt1", "Show", 12), dropped = emptyList()),
            metas = mapOf("tt1" to show("tt1", 12)),
        ).also { it.calls.clear() })
        val completed = fresh.entryFor(show("tt1", 12))!!
        assertEquals(ListChangeImpact(0, true), fresh.impact(completed, ListChange.SetStatus(ListStatus.DROPPED)))
        val dropped = fresh.apply(completed, ListChange.SetStatus(ListStatus.DROPPED))
        assertEquals(ListStatus.DROPPED, dropped.status)
        assertEquals(ListStatus.COMPLETED, fresh.apply(dropped, ListChange.LeaveStatus(ListStatus.DROPPED)).status)

        val film = svc.entryFor(MetaDetails(id = "tt5", type = "movie", name = "Film"))!!
        assertFailsWith<IllegalArgumentException> { svc.apply(film, ListChange.SetStatus(ListStatus.DROPPED)) }
        assertFailsWith<IllegalArgumentException> { svc.apply(film, ListChange.LeaveStatus(ListStatus.DROPPED)) }
    }

    @Test fun droppedWritesGoToMdbList(): Unit = runBlocking {
        val port = FakeMdbListPort(
            MdbListListSnapshot(watched = episodes("tt1", "Show", 12)),
            metas = mapOf("tt1" to show("tt1", 12)),
        )
        val history = FakeHistoryPort(meta = show("tt1", 12))
        val svc = adapter(port, history)
        val entry = svc.entryFor(show("tt1", 12))!!
        svc.apply(entry, ListChange.SetStatus(ListStatus.DROPPED))
        assertEquals(listOf("dropped:add:tt1"), port.calls)
        assertTrue(history.calls.isEmpty())
    }

    @Test fun completedMarksHistoryThroughSharedWritesOnly(): Unit = runBlocking {
        val port = FakeMdbListPort(
            MdbListListSnapshot(watchlist = listOf(watchlist("tt1", "series", "Show")), watched = episodes("tt1", "Show", 3)),
            metas = mapOf("tt1" to show("tt1", 12)),
        )
        val history = FakeHistoryPort(meta = show("tt1", 12), watched = (1..3).map { "1:$it" }.toMutableSet())
        val svc = adapter(port, history)
        // Watchlisted and partly watched shows are not listed, so the title bar starts from a fallback entry.
        val seed = ListEntry(
            key = ListEntryKey(ListServiceId.MDBLIST, "series:tt1"), title = "Show", subtitle = null, posterUrl = null,
            type = ListMediaType.SERIES, anime = false, facts = emptyList(), communityScore = null, status = null,
            score = 0, watchedEpisodes = 0, totalEpisodes = 0, airing = false, rewatching = false, rewatchCount = 0,
            catalogType = "series", catalogId = "tt1", siteUrl = null,
        )

        assertEquals(9, svc.impact(seed, ListChange.SetStatus(ListStatus.COMPLETED)).episodesMarked)
        val completed = svc.apply(seed, ListChange.SetStatus(ListStatus.COMPLETED))

        assertEquals(listOf("poster:tt1"), history.calls)
        assertTrue(port.calls.isEmpty())
        assertEquals(ListStatus.COMPLETED, completed.status)
        assertFailsWith<IllegalArgumentException> { svc.apply(seed, ListChange.SetScore(5)) }
        assertFailsWith<IllegalArgumentException> { svc.apply(seed, ListChange.StepEpisodes(1)) }
    }

    @Test fun removeFromHistoryRemovesStarlitFirstThenMdbList(): Unit = runBlocking {
        val port = FakeMdbListPort(
            MdbListListSnapshot(watched = episodes("tt1", "Show", 4)),
            metas = mapOf("tt1" to show("tt1", 4)),
        )
        val history = FakeHistoryPort(meta = show("tt1", 4), watched = mutableSetOf("1:1"))
        val svc = adapter(port, history)
        val entry = svc.entryFor(show("tt1", 4))!!
        assertEquals(4, entry.watchedEpisodes)

        assertEquals(4, svc.removeFromHistory(entry))
        assertEquals(listOf("episode:1:1:true"), history.calls)
        assertEquals(listOf("history:remove:show:tt1"), port.calls)
        assertNull(svc.entryFor(show("tt1", 4)))
    }

    @Test fun removeFromHistorySkipsMdbListWhenStarlitRemovalFails(): Unit = runBlocking {
        val port = FakeMdbListPort(
            MdbListListSnapshot(watched = episodes("tt1", "Show", 4)),
            metas = mapOf("tt1" to show("tt1", 4)),
        )
        val svc = adapter(port, FakeHistoryPort(meta = null, watched = mutableSetOf("1:1")))
        val entry = svc.entryFor(show("tt1", 4))!!
        assertFailsWith<IllegalStateException> { svc.removeFromHistory(entry) }
        assertTrue(port.calls.isEmpty())
    }

    @Test fun removeFromHistoryCountsAWatchedMovieAsOne(): Unit = runBlocking {
        val port = FakeMdbListPort(
            MdbListListSnapshot(watched = listOf(WatchedItem(id = "tt5", type = "movie", name = "Film", markedAtEpochMs = 1L))),
        )
        val svc = adapter(port, FakeHistoryPort())
        val film = svc.entryFor(MetaDetails(id = "tt5", type = "movie", name = "Film"))!!
        assertNotNull(film.status)
        assertEquals(1, svc.removeFromHistory(film))
        assertEquals(listOf("history:remove:movie:tt5"), port.calls)
    }
}
