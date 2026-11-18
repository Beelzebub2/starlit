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

    @Test fun completedTogglesAnUnwatchedTitleOnce(): Unit = runBlocking {
        val port = FakeHistoryPort(meta = seriesMeta(2))
        ListHistoryWrites(port).markCompleted("series", "tt1", "Show", null)
        assertEquals(listOf("poster:tt1"), port.calls)
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
}
