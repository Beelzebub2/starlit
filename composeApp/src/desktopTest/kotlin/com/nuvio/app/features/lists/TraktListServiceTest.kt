package com.nuvio.app.features.lists

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.trakt.TraktDroppedShow
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records Trakt writes and serves a fixed snapshot; never touches the network or repositories. */
private class FakeTraktPort(
    var snapshot: TraktListSnapshot = TraktListSnapshot(),
    val metas: Map<String, MetaDetails> = emptyMap(),
    val fullyWatched: Set<String> = emptySet(),
) : TraktListPort {
    val calls = mutableListOf<String>()
    var loads = 0
    var profile = 1
    var now = 1_000L

    override fun activeProfileId(): Int = profile

    override suspend fun load(): TraktListSnapshot {
        loads += 1
        return snapshot
    }

    override suspend fun setWatchlisted(item: LibraryItem, inWatchlist: Boolean) {
        calls += "watchlist:${if (inWatchlist) "add" else "remove"}:${item.id}"
    }

    override suspend fun setDropped(media: TrackingMediaReference, dropped: Boolean) {
        calls += "dropped:${if (dropped) "add" else "remove"}:${media.ids.imdb}"
    }

    override suspend fun setRating(media: TrackingMediaReference, rating: Int) {
        calls += "rate:$rating:${media.ids.imdb}"
    }

    override suspend fun removeHistory(media: TrackingMediaReference) {
        calls += "history:remove:${media.kind.name.lowercase()}:${media.ids.imdb}"
    }

    override fun cachedMeta(type: String, id: String): MetaDetails? = metas[id]

    override suspend fun fetchMeta(type: String, id: String): MetaDetails? = metas[id]

    override fun isFullyWatchedLocally(type: String, id: String): Boolean = id in fullyWatched

    override fun todayIsoDate(): String = "2026-01-01"

    override fun nowEpochMs(): Long = now
}

private fun metaFor(id: String, episodes: Int) = seriesMeta(episodes).copy(id = id)

private fun watchlistItem(id: String, type: String, name: String, tmdbId: Int? = null) = LibraryItem(
    id = id,
    type = type,
    name = name,
    imdbId = id.takeIf { it.startsWith("tt") },
    tmdbId = tmdbId,
    savedAtEpochMs = 100L,
)

private fun watchedEpisodes(id: String, name: String, count: Int) = (1..count).map { episode ->
    WatchedItem(id = id, type = "series", name = name, season = 1, episode = episode, markedAtEpochMs = 200L + episode)
}

private fun watchedMovie(id: String, name: String) =
    WatchedItem(id = id, type = "movie", name = name, markedAtEpochMs = 300L)

class TraktListServiceTest {
    private fun adapter(
        trakt: FakeTraktPort = FakeTraktPort(),
        history: FakeHistoryPort = FakeHistoryPort(),
    ) = TraktListAdapter(
        port = trakt,
        connected = MutableStateFlow(true),
        history = ListHistoryWrites(history),
        signedInAs = { "tester" },
    )

    /** A show watched 3 of 12 episodes, i.e. Watching on Trakt. */
    private fun watchingShow(): FakeTraktPort = FakeTraktPort(
        snapshot = TraktListSnapshot(watched = watchedEpisodes("tt1", "Show", 3)),
        metas = mapOf("tt1" to metaFor("tt1", 12)),
    )

    @Test fun traktNeverOffersOnHold(): Unit = runBlocking {
        val svc = adapter()
        val series = svc.capabilities(ListMediaType.SERIES)
        assertEquals(listOf(ListStatus.WATCHING, ListStatus.COMPLETED, ListStatus.DROPPED, ListStatus.PLAN_TO_WATCH), series.statuses)
        assertEquals(setOf(ListStatus.PLAN_TO_WATCH, ListStatus.DROPPED, ListStatus.COMPLETED), series.pickableStatuses)
        assertEquals(setOf(ListStatus.PLAN_TO_WATCH, ListStatus.DROPPED), series.leavableStatuses)
        assertTrue(series.score && series.episodeProgress && series.removeFromHistory)
        assertFalse(series.rewatch || series.changeEntry)
        val movie = svc.capabilities(ListMediaType.MOVIE)
        assertEquals(listOf(ListStatus.COMPLETED, ListStatus.PLAN_TO_WATCH), movie.statuses)
        assertEquals(setOf(ListStatus.PLAN_TO_WATCH, ListStatus.COMPLETED), movie.pickableStatuses)
        assertEquals(setOf(ListStatus.PLAN_TO_WATCH), movie.leavableStatuses)
        assertFalse(movie.episodeProgress)
        assertTrue(movie.score && movie.removeFromHistory)
        listOf(series, movie).forEach { caps ->
            assertFalse(ListStatus.ON_HOLD in caps.statuses)
            assertFalse(ListStatus.ON_HOLD in caps.pickableStatuses)
            assertFalse(ListStatus.WATCHING in caps.pickableStatuses)
        }

        val trakt = watchingShow()
        val withShow = adapter(trakt)
        val entry = withShow.entryFor(metaFor("tt1", 12))!!
        assertFailsWith<IllegalArgumentException> { withShow.apply(entry, ListChange.SetStatus(ListStatus.ON_HOLD)) }
        assertFailsWith<IllegalArgumentException> { withShow.apply(entry, ListChange.SetStatus(ListStatus.WATCHING)) }
        assertTrue(trakt.calls.isEmpty())
    }

    @Test fun pickingPlanToWatchOnWatchedShowKeepsHistory(): Unit = runBlocking {
        val trakt = watchingShow()
        val history = FakeHistoryPort(meta = metaFor("tt1", 12), watched = (1..3).map { "1:$it" }.toMutableSet())
        val svc = adapter(trakt, history)
        val entry = svc.entryFor(metaFor("tt1", 12))!!
        assertEquals(ListStatus.WATCHING, entry.status)

        val updated = svc.apply(entry, ListChange.SetStatus(ListStatus.PLAN_TO_WATCH))

        assertEquals(listOf("watchlist:add:tt1"), trakt.calls)
        assertTrue(history.calls.isEmpty(), "Plan to Watch must not touch watch history: ${history.calls}")
        assertEquals(ListStatus.WATCHING, updated.status)
        assertEquals(3, updated.watchedEpisodes)
    }

    @Test fun droppedImpactWarnsAboutContinueWatching(): Unit = runBlocking {
        val trakt = watchingShow()
        val history = FakeHistoryPort(meta = metaFor("tt1", 12))
        val svc = adapter(trakt, history)
        val entry = svc.entryFor(metaFor("tt1", 12))!!

        assertEquals(ListChangeImpact(0, hidesFromContinueWatching = true), svc.impact(entry, ListChange.SetStatus(ListStatus.DROPPED)))
        assertEquals(ListChangeImpact(0, hidesFromContinueWatching = false), svc.impact(entry, ListChange.SetStatus(ListStatus.PLAN_TO_WATCH)))

        val dropped = svc.apply(entry, ListChange.SetStatus(ListStatus.DROPPED))
        assertEquals(listOf("dropped:add:tt1"), trakt.calls)
        assertTrue(history.calls.isEmpty())
        assertEquals(ListStatus.DROPPED, dropped.status)
    }

    @Test fun leavingAListRemovesOnlyThatList(): Unit = runBlocking {
        val trakt = FakeTraktPort(
            snapshot = TraktListSnapshot(
                watchlist = listOf(watchlistItem("tt1", "series", "Show"), watchlistItem("tt5", "movie", "Film")),
                dropped = listOf(TraktDroppedShow(title = "Show", ids = TrackingExternalIds(imdb = "tt1", trakt = 1))),
                watched = watchedEpisodes("tt1", "Show", 2),
            ),
            metas = mapOf("tt1" to metaFor("tt1", 12)),
        )
        val history = FakeHistoryPort(meta = metaFor("tt1", 12))
        val svc = adapter(trakt, history)
        val show = svc.entryFor(metaFor("tt1", 12))!!
        assertEquals(ListStatus.DROPPED, show.status)

        val undropped = svc.apply(show, ListChange.LeaveStatus(ListStatus.DROPPED))
        assertEquals(ListStatus.WATCHING, undropped.status)
        val unlisted = svc.apply(undropped, ListChange.LeaveStatus(ListStatus.PLAN_TO_WATCH))
        assertEquals(ListStatus.WATCHING, unlisted.status)
        assertEquals(listOf("dropped:remove:tt1", "watchlist:remove:tt1"), trakt.calls)
        assertTrue(history.calls.isEmpty())
        assertFailsWith<IllegalArgumentException> { svc.apply(unlisted, ListChange.LeaveStatus(ListStatus.WATCHING)) }

        val film = svc.entryFor(MetaDetails(id = "tt5", type = "movie", name = "Film"))!!
        assertEquals(ListStatus.PLAN_TO_WATCH, film.status)
        assertFailsWith<IllegalArgumentException> { svc.apply(film, ListChange.SetStatus(ListStatus.DROPPED)) }
        assertFailsWith<IllegalArgumentException> { svc.apply(film, ListChange.LeaveStatus(ListStatus.DROPPED)) }
        val leftFilm = svc.apply(film, ListChange.LeaveStatus(ListStatus.PLAN_TO_WATCH))
        assertNull(leftFilm.status)
        assertEquals("watchlist:remove:tt5", trakt.calls.last())
    }

    @Test fun completedMarksHistoryThroughSharedWritesAndSkipsTraktLists(): Unit = runBlocking {
        val trakt = watchingShow()
        val history = FakeHistoryPort(meta = metaFor("tt1", 12), watched = (1..3).map { "1:$it" }.toMutableSet())
        val svc = adapter(trakt, history)
        val entry = svc.entryFor(metaFor("tt1", 12))!!

        val impact = svc.impact(entry, ListChange.SetStatus(ListStatus.COMPLETED))
        assertEquals(9, impact.episodesMarked)
        val completed = svc.apply(entry, ListChange.SetStatus(ListStatus.COMPLETED))

        assertEquals(listOf("poster:tt1"), history.calls)
        assertTrue(trakt.calls.isEmpty())
        assertEquals(ListStatus.COMPLETED, completed.status)
        assertEquals(12, completed.watchedEpisodes)
        assertEquals(ListStatus.COMPLETED, svc.page(ListStatus.COMPLETED, svc.sorts.first(), 0).entries.single().status)
    }

    @Test fun completedWithMissingMetaThrowsBeforeAnyWrite(): Unit = runBlocking {
        val trakt = watchingShow()
        val history = FakeHistoryPort(meta = null)
        val svc = adapter(trakt, history)
        val entry = svc.entryFor(metaFor("tt1", 12))!!
        assertFailsWith<IllegalStateException> { svc.apply(entry, ListChange.SetStatus(ListStatus.COMPLETED)) }
        assertTrue(history.calls.isEmpty())
        assertTrue(trakt.calls.isEmpty())
    }

    @Test fun scoreWritesRatingAndZeroClearsIt(): Unit = runBlocking {
        val trakt = FakeTraktPort(
            snapshot = TraktListSnapshot(watched = watchedEpisodes("tt1", "Show", 3), ratings = mapOf("show:tt1" to 7)),
            metas = mapOf("tt1" to metaFor("tt1", 12)),
        )
        val svc = adapter(trakt)
        val entry = svc.entryFor(metaFor("tt1", 12))!!
        assertEquals(7, entry.score)
        assertEquals(9, svc.apply(entry, ListChange.SetScore(9)).score)
        assertEquals(0, svc.apply(entry, ListChange.SetScore(0)).score)
        assertEquals(listOf("rate:9:tt1", "rate:0:tt1"), trakt.calls)
        assertFailsWith<IllegalArgumentException> { svc.apply(entry, ListChange.SetScore(11)) }
    }

    @Test fun stepEpisodesUsesSharedHistoryWrites(): Unit = runBlocking {
        val trakt = watchingShow()
        val history = FakeHistoryPort(meta = metaFor("tt1", 12), watched = (1..3).map { "1:$it" }.toMutableSet())
        val svc = adapter(trakt, history)
        val entry = svc.entryFor(metaFor("tt1", 12))!!
        assertEquals(ListChangeImpact(1, false), svc.impact(entry, ListChange.StepEpisodes(1)))
        val stepped = svc.apply(entry, ListChange.StepEpisodes(1))
        assertEquals(listOf("episode:1:4:false"), history.calls)
        assertEquals(4, stepped.watchedEpisodes)
        assertTrue(trakt.calls.isEmpty())
    }

    @Test fun removeFromHistoryUsesStarlitPathThenTraktRemove(): Unit = runBlocking {
        val trakt = FakeTraktPort(
            snapshot = TraktListSnapshot(
                watchlist = listOf(watchlistItem("tt1", "series", "Show")),
                watched = watchedEpisodes("tt1", "Show", 2),
            ),
            metas = mapOf("tt1" to metaFor("tt1", 5)),
        )
        val history = FakeHistoryPort(meta = metaFor("tt1", 5), watched = mutableSetOf("1:1", "1:2"))
        val svc = adapter(trakt, history)
        val entry = svc.entryFor(metaFor("tt1", 5))!!
        assertEquals(ListStatus.WATCHING, entry.status)

        assertEquals(2, svc.removeFromHistory(entry))

        assertEquals(listOf("episode:1:1:true", "episode:1:2:true"), history.calls)
        assertEquals(listOf("history:remove:show:tt1"), trakt.calls)
        val after = svc.page(null, svc.sorts.first(), 0).entries.single()
        assertEquals(ListStatus.PLAN_TO_WATCH, after.status)
        assertEquals(0, after.watchedEpisodes)
    }

    @Test fun removeFromHistoryClearsTraktOnlyHistoryAndCountsTraktEntries(): Unit = runBlocking {
        val trakt = FakeTraktPort(
            snapshot = TraktListSnapshot(
                watched = watchedEpisodes("tt1", "Show", 4) + watchedMovie("tt5", "Film"),
            ),
            metas = mapOf("tt1" to metaFor("tt1", 6)),
        )
        // Starlit's own history knows one episode and not the movie: Trakt holds the rest.
        val history = FakeHistoryPort(meta = metaFor("tt1", 6), watched = mutableSetOf("1:1"))
        val svc = adapter(trakt, history)
        val show = svc.entryFor(metaFor("tt1", 6))!!
        assertEquals(4, show.watchedEpisodes)

        assertEquals(4, svc.removeFromHistory(show))
        assertEquals(listOf("episode:1:1:true"), history.calls)
        assertEquals(listOf("history:remove:show:tt1"), trakt.calls)
        assertNull(svc.entryFor(metaFor("tt1", 6)), "a show with no list and no history has no Trakt entry")

        history.calls.clear()
        trakt.calls.clear()
        val film = svc.entryFor(MetaDetails(id = "tt5", type = "movie", name = "Film"))!!
        assertEquals(ListStatus.COMPLETED, film.status)
        assertEquals(1, svc.removeFromHistory(film))
        assertTrue(history.calls.isEmpty(), "Starlit's history had no watched mark for the movie")
        assertEquals(listOf("history:remove:movie:tt5"), trakt.calls)
    }

    @Test fun removeFromHistoryStopsBeforeTraktWhenStarlitPathFails(): Unit = runBlocking {
        val trakt = watchingShow()
        val svc = adapter(trakt, FakeHistoryPort(meta = null, watched = mutableSetOf("1:1")))
        val entry = svc.entryFor(metaFor("tt1", 12))!!
        assertFailsWith<IllegalStateException> { svc.removeFromHistory(entry) }
        assertTrue(trakt.calls.isEmpty())
    }

    @Test fun traktAiredCountsMarkFullyWatchedShowsCompletedWithoutCachedMeta(): Unit = runBlocking {
        val trakt = FakeTraktPort(
            snapshot = TraktListSnapshot(
                watched = watchedEpisodes("tt9", "India", 4) + watchedEpisodes("tt8", "Hotel", 4),
                airedEpisodes = mapOf("tt9" to 4, "trakt:9" to 4, "tt8" to 6),
            ),
        )
        val svc = adapter(trakt)
        val entries = svc.page(null, svc.sorts.first { it.label == "Title" }, 0).entries.associateBy { it.title }

        assertEquals(ListStatus.COMPLETED, entries.getValue("India").status)
        assertEquals(4, entries.getValue("India").totalEpisodes)
        assertEquals(ListStatus.WATCHING, entries.getValue("Hotel").status)
        assertEquals(6, entries.getValue("Hotel").totalEpisodes)
        assertEquals(ListStatus.COMPLETED, svc.entryFor(MetaDetails(id = "tt9", type = "series", name = "India"))?.status)
    }

    @Test fun pageDerivesStatusesDeduplicatesAndSummarizes(): Unit = runBlocking {
        val trakt = FakeTraktPort(
            snapshot = TraktListSnapshot(
                watchlist = listOf(
                    watchlistItem("tt10", "series", "Alpha"),
                    watchlistItem("tt4", "series", "Delta"),
                    watchlistItem("tt6", "movie", "Foxtrot"),
                ),
                watched = watchedEpisodes("tt2", "Bravo", 3) +
                    watchedEpisodes("tt3", "Charlie", 2) +
                    watchedEpisodes("tt4", "Delta", 1) +
                    watchedEpisodes("tt7", "Golf", 4) +
                    watchedEpisodes("tt8", "Hotel", 4) +
                    watchedMovie("tt5", "Echo"),
                dropped = listOf(TraktDroppedShow(title = "Delta", ids = TrackingExternalIds(imdb = "tt4", trakt = 44))),
                ratings = mapOf("show:tt3" to 7, "movie:tt5" to 8),
            ),
            metas = mapOf("tt2" to metaFor("tt2", 3), "tt3" to metaFor("tt3", 5)),
            fullyWatched = setOf("tt7"),
        )
        val svc = adapter(trakt)
        val byTitle = svc.sorts.first { it.label == "Title" }

        val all = svc.page(null, byTitle, 0)
        assertEquals(
            listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel"),
            all.entries.map { it.title },
        )
        assertNull(all.nextOffset)
        val status = all.entries.associate { it.title to it.status }
        assertEquals(ListStatus.PLAN_TO_WATCH, status["Alpha"])
        assertEquals(ListStatus.COMPLETED, status["Bravo"])
        assertEquals(ListStatus.WATCHING, status["Charlie"])
        assertEquals(ListStatus.DROPPED, status["Delta"])
        assertEquals(ListStatus.COMPLETED, status["Echo"])
        assertEquals(ListStatus.PLAN_TO_WATCH, status["Foxtrot"])
        assertEquals(ListStatus.COMPLETED, status["Golf"])
        assertEquals(ListStatus.WATCHING, status["Hotel"])

        val charlie = all.entries.first { it.title == "Charlie" }
        assertEquals(7, charlie.score)
        assertEquals(2, charlie.watchedEpisodes)
        assertEquals(5, charlie.totalEpisodes)
        assertEquals("series", charlie.catalogType)
        assertEquals("tt3", charlie.catalogId)
        val echo = all.entries.first { it.title == "Echo" }
        assertEquals(ListMediaType.MOVIE, echo.type)
        assertEquals(8, echo.score)
        assertEquals("movie", echo.catalogType)

        assertEquals(listOf("Charlie", "Hotel"), svc.page(ListStatus.WATCHING, byTitle, 0).entries.map { it.title })
        assertEquals(listOf("Echo", "Charlie"), svc.page(null, svc.sorts.first { it.label == "Your score" }, 0).entries.take(2).map { it.title })

        val summary = svc.summary()
        assertEquals("tester", summary.signedInAs)
        assertEquals(2, summary.counts[ListStatus.PLAN_TO_WATCH])
        assertEquals(3, summary.counts[ListStatus.COMPLETED])
        assertEquals(2, summary.counts[ListStatus.WATCHING])
        assertEquals(1, summary.counts[ListStatus.DROPPED])
        assertEquals(0, summary.counts[ListStatus.ON_HOLD])
        assertEquals(8, summary.counts[null])
        assertEquals(1, trakt.loads)
    }

    @Test fun entryForMatchesAnyIdOfTheSameTypeAndIsNullOtherwise(): Unit = runBlocking {
        val trakt = FakeTraktPort(
            snapshot = TraktListSnapshot(watchlist = listOf(watchlistItem("tt10", "series", "Alpha", tmdbId = 1010))),
        )
        val svc = adapter(trakt)
        val found = svc.entryFor(MetaDetails(id = "tmdb:1010", type = "series", name = "Alpha"))!!
        assertEquals(ListStatus.PLAN_TO_WATCH, found.status)
        assertEquals("tmdb:1010", found.catalogId)
        assertEquals("series", found.catalogType)
        assertNull(svc.entryFor(MetaDetails(id = "tmdb:1010", type = "movie", name = "Other film")))
        assertNull(svc.entryFor(MetaDetails(id = "tt999", type = "series", name = "Unlisted")))
    }

    @Test fun snapshotIsCachedPerProfile(): Unit = runBlocking {
        val trakt = watchingShow()
        val svc = adapter(trakt)
        svc.page(null, svc.sorts.first(), 0)
        svc.entryFor(metaFor("tt1", 12))
        assertEquals(1, trakt.loads)
        trakt.profile = 2
        svc.page(null, svc.sorts.first(), 0)
        assertEquals(2, trakt.loads)
    }

    @Test fun coversWhenIdsResolve() {
        val svc = adapter()
        val kind = ListTitleKind(ListMediaType.SERIES, anime = true)
        assertTrue(svc.covers(kind, MetaDetails(id = "tt1", type = "series", name = "X")))
        assertFalse(svc.covers(kind, MetaDetails(id = "plain-name", type = "series", name = "X")))
    }
}
