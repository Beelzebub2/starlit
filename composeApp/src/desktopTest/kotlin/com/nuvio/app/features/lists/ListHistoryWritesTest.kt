package com.nuvio.app.features.lists

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ListHistoryWritesTest {
    @Test fun completedNeverTogglesAnAlreadyWatchedTitle(): Unit = runBlocking {
        val port = FakeHistoryPort(meta = seriesMeta(2), fullyWatched = true)
        ListHistoryWrites(port).markCompleted("series", "tt1", "Show", null)
        assertTrue(port.calls.isEmpty())
        val movie = FakeHistoryPort(watched = mutableSetOf("title"))
        ListHistoryWrites(movie).markCompleted("movie", "tt2", "Film", null)
        assertTrue(movie.calls.isEmpty())
    }

    @Test fun completedMarksAnUnwatchedTitleOnceWithoutToggling(): Unit = runBlocking {
        val port = FakeHistoryPort(meta = seriesMeta(2))
        ListHistoryWrites(port).markCompleted("series", "tt1", "Show", null)
        assertEquals(listOf("mark:series:tt1"), port.calls)
        val movie = FakeHistoryPort()
        ListHistoryWrites(movie).markCompleted("movie", "tt2", "Film", null)
        assertEquals(listOf("mark:movie:tt2"), movie.calls)
    }

    @Test fun completedNeverUnmarksASeriesThatBecameWatchedWhileEpisodesLoaded(): Unit = runBlocking {
        // A background sync completes the series between the "not watched" check and the write.
        val port = FakeHistoryPort(meta = seriesMeta(3))
        port.onFetch = { port.fullyWatched = true }
        ListHistoryWrites(port).markCompleted("series", "tt1", "Show", null)
        assertTrue(port.calls.isEmpty(), "nothing may be toggled or unmarked: ${port.calls}")
        // Marking never goes through the poster toggle, so even a stale "not watched" check can only mark.
        val stale = FakeHistoryPort(meta = seriesMeta(3), watched = mutableSetOf("1:1", "1:2", "1:3"))
        ListHistoryWrites(stale).markCompleted("series", "tt1", "Show", null)
        assertEquals(listOf("mark:series:tt1"), stale.calls)
    }

    @Test fun plusAndMinusTargetTheRightEpisodes(): Unit = runBlocking {
        val port = FakeHistoryPort(meta = seriesMeta(5), watched = mutableSetOf("1:1", "1:2", "1:3"))
        val writes = ListHistoryWrites(port)
        writes.stepEpisodes("series", "tt1", 2)
        assertEquals(listOf("episode:1:4:false", "episode:1:5:false"), port.calls)
        port.calls.clear()
        writes.stepEpisodes("series", "tt1", -2)
        assertEquals(listOf("episode:1:3:true", "episode:1:2:true"), port.calls)
    }

    @Test fun missingNextOrPreviousEpisodeFails(): Unit = runBlocking {
        val port = FakeHistoryPort(meta = seriesMeta(2), watched = mutableSetOf("1:1", "1:2"))
        assertFailsWith<IllegalStateException> { ListHistoryWrites(port).stepEpisodes("series", "tt1", 1) }
        val none = FakeHistoryPort(meta = seriesMeta(2))
        assertFailsWith<IllegalStateException> { ListHistoryWrites(none).stepEpisodes("series", "tt1", -1) }
    }

    @Test fun unknownImpactAndMissingMetaFailLoudly(): Unit = runBlocking {
        val port = FakeHistoryPort(meta = null)
        assertFailsWith<IllegalStateException> { ListHistoryWrites(port).completedImpact("series", "tt1") }
        assertFailsWith<IllegalStateException> { ListHistoryWrites(port).markCompleted("series", "tt1", "Show", null) }
        assertTrue(port.calls.isEmpty())
    }

    @Test fun movieImpactIsOneUnlessWatched(): Unit = runBlocking {
        assertEquals(1, ListHistoryWrites(FakeHistoryPort()).completedImpact("movie", "tt2"))
        assertEquals(0, ListHistoryWrites(FakeHistoryPort(watched = mutableSetOf("title"))).completedImpact("movie", "tt2"))
    }

    @Test fun removeAllUnmarksOnlyWatchedReleasedEpisodesAndNeverUsesThePosterToggle(): Unit = runBlocking {
        val port = FakeHistoryPort(meta = seriesMeta(episodes = 5, future = 2), watched = mutableSetOf("1:1", "1:3", "1:4"))
        val removed = ListHistoryWrites(port).removeAll("series", "tt1")
        assertEquals(3, removed)
        assertEquals(listOf("episode:1:1:true", "episode:1:3:true", "episode:1:4:true"), port.calls)
    }

    @Test fun removeAllOnAnUnwatchedSeriesWritesNothing(): Unit = runBlocking {
        val port = FakeHistoryPort(meta = seriesMeta(3))
        assertEquals(0, ListHistoryWrites(port).removeAll("series", "tt1"))
        assertTrue(port.calls.isEmpty())
    }

    @Test fun removeAllOnAMovieTogglesOffOnlyWhenWatched(): Unit = runBlocking {
        val watched = FakeHistoryPort(watched = mutableSetOf("title"))
        assertEquals(1, ListHistoryWrites(watched).removeAll("movie", "tt2"))
        assertEquals(listOf("poster:tt2"), watched.calls)
        val unwatched = FakeHistoryPort()
        assertEquals(0, ListHistoryWrites(unwatched).removeAll("movie", "tt2"))
        assertTrue(unwatched.calls.isEmpty())
    }

    @Test fun removeAllWithMissingMetaFailsBeforeAnyWrite(): Unit = runBlocking {
        val port = FakeHistoryPort(meta = null, watched = mutableSetOf("1:1"))
        assertFailsWith<IllegalStateException> { ListHistoryWrites(port).removeAll("series", "tt1") }
        assertTrue(port.calls.isEmpty())
    }
}
