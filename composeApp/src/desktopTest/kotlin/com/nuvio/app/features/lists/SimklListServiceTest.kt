package com.nuvio.app.features.lists

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.simkl.SimklLibraryEntry
import com.nuvio.app.features.simkl.SimklListStatus
import com.nuvio.app.features.simkl.SimklMedia
import com.nuvio.app.features.simkl.SimklMediaType
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingWrites : SimklListWrites {
    val calls = mutableListOf<String>()

    override suspend fun moveToList(media: TrackingMediaReference, status: TrackingListStatus): TrackingMutationResult {
        calls += "move:${status.name}"
        return TrackingMutationResult(attemptedCount = 1)
    }

    override suspend fun setRating(media: TrackingMediaReference, rating: Int): TrackingMutationResult {
        calls += "rate:$rating"
        return TrackingMutationResult(attemptedCount = 1)
    }
}

private fun simklEntry(
    title: String,
    simklId: Long,
    status: SimklListStatus,
    type: SimklMediaType = SimklMediaType.SHOWS,
    rating: Int? = null,
    watched: Int = 0,
    total: Int = 0,
    lastWatched: String? = null,
): SimklLibraryEntry {
    val media = SimklMedia(
        title = title,
        ids = mapOf("simkl" to JsonPrimitive(simklId), "imdb" to JsonPrimitive("tt$simklId")),
    )
    return SimklLibraryEntry(
        mediaType = type,
        status = status,
        userRating = rating,
        watchedEpisodesCount = watched,
        totalEpisodesCount = total,
        lastWatchedAt = lastWatched,
        show = media.takeIf { type != SimklMediaType.MOVIES },
        movie = media.takeIf { type == SimklMediaType.MOVIES },
    )
}

class SimklListServiceTest {
    private fun adapter(
        entries: List<SimklLibraryEntry> = emptyList(),
        writes: SimklListWrites = RecordingWrites(),
        port: FakeHistoryPort = FakeHistoryPort(),
    ) = SimklListAdapter(
        entries = { entries },
        connected = MutableStateFlow(true),
        writes = writes,
        history = ListHistoryWrites(port),
        signedInAs = { null },
    )

    @Test fun moviesHaveNoWatchingOrOnHold() {
        val service = adapter()
        val movie = service.capabilities(ListMediaType.MOVIE)
        assertEquals(listOf(ListStatus.PLAN_TO_WATCH, ListStatus.COMPLETED, ListStatus.DROPPED), movie.statuses)
        assertFalse(movie.episodeProgress)
        val series = service.capabilities(ListMediaType.SERIES)
        assertEquals(5, series.statuses.size)
        assertTrue(series.episodeProgress && series.score)
        assertTrue(series.leavableStatuses.isEmpty())
        assertFalse(series.removeFromHistory)
    }

    @Test fun completedImpactCountsRemainingAiredEpisodes(): Unit = runBlocking {
        val port = FakeHistoryPort(meta = seriesMeta(episodes = 12, future = 3), watched = (1..7).map { "1:$it" }.toMutableSet())
        val svc = adapter(
            entries = listOf(simklEntry("Show", 1, SimklListStatus.WATCHING, watched = 7, total = 12)),
            port = port,
        )
        val entry = svc.entryFor(MetaDetails(id = "tt1", type = "series", name = "Show"))!!
        val impact = svc.impact(entry, ListChange.SetStatus(ListStatus.COMPLETED))
        assertEquals(5, impact.episodesMarked)
        assertTrue(impact.hidesFromContinueWatching)
    }

    @Test fun completedMarksWatchedThenMovesToList(): Unit = runBlocking {
        val port = FakeHistoryPort(meta = seriesMeta(3))
        val writes = RecordingWrites()
        val svc = adapter(entries = listOf(simklEntry("Show", 1, SimklListStatus.WATCHING)), writes = writes, port = port)
        val entry = svc.entryFor(MetaDetails(id = "tt1", type = "series", name = "Show"))!!
        val updated = svc.apply(entry, ListChange.SetStatus(ListStatus.COMPLETED))
        assertEquals(listOf("poster:tt1"), port.calls)
        assertEquals(listOf("move:COMPLETED"), writes.calls)
        assertEquals(ListStatus.COMPLETED, updated.status)
    }

    @Test fun completedWithMissingMetaThrowsBeforeAnyWrite(): Unit = runBlocking {
        val port = FakeHistoryPort(meta = null)
        val writes = RecordingWrites()
        val svc = adapter(entries = listOf(simklEntry("Show", 1, SimklListStatus.WATCHING)), writes = writes, port = port)
        val entry = svc.entryFor(MetaDetails(id = "tt1", type = "series", name = "Show"))!!
        assertFailsWith<IllegalStateException> { svc.impact(entry, ListChange.SetStatus(ListStatus.COMPLETED)) }
        assertFailsWith<IllegalStateException> { svc.apply(entry, ListChange.SetStatus(ListStatus.COMPLETED)) }
        assertTrue(writes.calls.isEmpty())
        assertTrue(port.calls.isEmpty())
    }

    @Test fun otherStatusesOnlyMoveAndLeaveIsRejected(): Unit = runBlocking {
        val port = FakeHistoryPort(meta = seriesMeta(3))
        val writes = RecordingWrites()
        val svc = adapter(entries = listOf(simklEntry("Show", 1, SimklListStatus.WATCHING)), writes = writes, port = port)
        val entry = svc.entryFor(MetaDetails(id = "tt1", type = "series", name = "Show"))!!
        svc.apply(entry, ListChange.SetStatus(ListStatus.ON_HOLD))
        assertTrue(port.calls.isEmpty())
        assertEquals(listOf("move:ON_HOLD"), writes.calls)
        assertFailsWith<IllegalArgumentException> { svc.apply(entry, ListChange.LeaveStatus(ListStatus.WATCHING)) }
        assertFailsWith<UnsupportedOperationException> { svc.removeFromHistory(entry) }
    }

    @Test fun scoreWritesRating(): Unit = runBlocking {
        val writes = RecordingWrites()
        val svc = adapter(entries = listOf(simklEntry("Show", 1, SimklListStatus.WATCHING)), writes = writes)
        val entry = svc.entryFor(MetaDetails(id = "tt1", type = "series", name = "Show"))!!
        assertEquals(8, svc.apply(entry, ListChange.SetScore(8)).score)
        assertEquals(listOf("rate:8"), writes.calls)
    }

    @Test fun stepEpisodesUsesHistoryWrites(): Unit = runBlocking {
        val port = FakeHistoryPort(meta = seriesMeta(3), watched = mutableSetOf("1:1"))
        val svc = adapter(entries = listOf(simklEntry("Show", 1, SimklListStatus.WATCHING, watched = 1, total = 3)), port = port)
        val entry = svc.entryFor(MetaDetails(id = "tt1", type = "series", name = "Show"))!!
        val updated = svc.apply(entry, ListChange.StepEpisodes(1))
        assertEquals(listOf("episode:1:2:false"), port.calls)
        assertEquals(2, updated.watchedEpisodes)
    }

    @Test fun pageFiltersSortsAndSummarizes(): Unit = runBlocking {
        val svc = adapter(
            entries = listOf(
                simklEntry("Banana", 1, SimklListStatus.WATCHING, SimklMediaType.ANIME, rating = 5, lastWatched = "2025-01-01T00:00:00Z"),
                simklEntry("Apple", 2, SimklListStatus.WATCHING, SimklMediaType.ANIME, rating = 9, lastWatched = "2024-01-01T00:00:00Z"),
                simklEntry("Cherry", 3, SimklListStatus.COMPLETED, SimklMediaType.MOVIES),
            ),
        )
        val sorts = svc.sorts.associateBy { it.label }
        assertEquals(listOf("Banana", "Apple"), svc.page(ListStatus.WATCHING, sorts.getValue("Last updated"), 0).entries.map { it.title })
        assertEquals(listOf("Apple", "Banana"), svc.page(ListStatus.WATCHING, sorts.getValue("Title"), 0).entries.map { it.title })
        assertEquals(listOf("Apple", "Banana"), svc.page(ListStatus.WATCHING, sorts.getValue("Your score"), 0).entries.map { it.title })
        val page = svc.page(null, sorts.getValue("Title"), 0)
        assertEquals(3, page.entries.size)
        assertNull(page.nextOffset)
        val summary = svc.summary()!!
        assertEquals(2, summary.counts[ListStatus.WATCHING])
        assertEquals(1, summary.counts[ListStatus.COMPLETED])
        assertEquals(3, summary.counts[null])
    }

    @Test fun coversWhenIdsResolve() {
        val svc = adapter()
        val kind = ListTitleKind(ListMediaType.SERIES, anime = false)
        assertTrue(svc.covers(kind, MetaDetails(id = "tt1", type = "series", name = "X")))
        assertFalse(svc.covers(kind, MetaDetails(id = "plain-name", type = "series", name = "X")))
    }

    @Test fun entryForMatchesByIdsAndUsesMetaCatalogIds(): Unit = runBlocking {
        val svc = adapter(entries = listOf(simklEntry("Show", 1, SimklListStatus.WATCHING, rating = 7, watched = 3, total = 10)))
        val found = svc.entryFor(MetaDetails(id = "tt1", type = "series", name = "Show"))!!
        assertEquals(7, found.score)
        assertEquals(3, found.watchedEpisodes)
        assertEquals("tt1", found.catalogId)
        assertNull(svc.entryFor(MetaDetails(id = "tt999", type = "series", name = "Other")))
    }
}
