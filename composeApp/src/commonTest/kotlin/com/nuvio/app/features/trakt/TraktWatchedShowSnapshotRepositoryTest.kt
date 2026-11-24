package com.nuvio.app.features.trakt

import com.nuvio.app.features.addons.RawHttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraktWatchedShowSnapshotRepositoryTest {
    @Test
    fun `concurrent consumers share one watched show request`() = runBlocking {
        var requests = 0
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val client = TraktWatchedPageClient(
            engine = TraktWatchedHttpEngine { url, _, _ ->
                requests += 1
                requestStarted.complete(Unit)
                releaseRequest.await()
                response(url)
            },
        )
        val store = TraktWatchedShowSnapshotStore(
            pageClient = client,
            nowEpochMs = { 1_000L },
        )

        val first = async { store.get(headers()) }
        requestStarted.await()
        val second = async { store.get(headers()) }
        yield()
        releaseRequest.complete(Unit)

        val firstResult = first.await()
        assertEquals(1, firstResult.size)
        assertEquals(firstResult, second.await())
        assertEquals(1, requests)
    }

    @Test
    fun `fresh watched show snapshot is reused and clear forces a request`() = runBlocking {
        var requests = 0
        var now = 1_000L
        val client = TraktWatchedPageClient(
            engine = TraktWatchedHttpEngine { url, _, _ ->
                requests += 1
                response(url)
            },
        )
        val store = TraktWatchedShowSnapshotStore(
            pageClient = client,
            nowEpochMs = { now },
        )

        store.get(headers())
        now += 10_000L
        store.get(headers())
        store.clear()
        store.get(headers())

        assertEquals(2, requests)
    }

    @Test
    fun `existing snapshot keeps asking for progress while the detailed one asks for full show info`(): Unit = runBlocking {
        val urls = mutableListOf<String>()
        val engine = TraktWatchedHttpEngine { url, _, _ ->
            urls += url
            detailedResponse(url)
        }
        val existing = TraktWatchedShowSnapshotStore(pageClient = TraktWatchedPageClient(engine), nowEpochMs = { 1_000L })
        val detailed = TraktWatchedShowSnapshotStore(
            pageClient = TraktWatchedPageClient(engine),
            nowEpochMs = { 1_000L },
            extended = TRAKT_WATCHED_SHOWS_DETAILS_EXTENDED,
        )

        existing.get(headers())
        val detailedItems = detailed.get(headers())

        assertEquals("full", TRAKT_WATCHED_SHOWS_DETAILS_EXTENDED)
        assertTrue(urls[0].startsWith("https://api.trakt.tv/sync/watched/shows?"))
        assertTrue(urls[0].endsWith("&extended=progress"), urls[0])
        assertTrue(urls[1].startsWith("https://api.trakt.tv/sync/watched/shows?"))
        assertTrue(urls[1].endsWith("&extended=full"), urls[1])
        assertEquals(12, detailedItems.single().show?.airedEpisodes)
    }

    @Test
    fun `aired episode counts are keyed by every content id and skip conflicting ids`() {
        val counts = traktAiredEpisodeCounts(
            listOf(
                show(trakt = 1, imdb = "tt1", tmdb = 11, aired = 12),
                show(trakt = 2, imdb = "tt2", tmdb = 99, aired = 5),
                show(trakt = 3, imdb = "tt3", tmdb = 99, aired = 8),
                show(trakt = 4, imdb = "tt4", tmdb = 44, aired = null),
            ),
        )

        assertEquals(12, counts["tt1"])
        assertEquals(12, counts["tmdb:11"])
        assertEquals(12, counts["trakt:1"])
        assertEquals(5, counts["tt2"])
        assertEquals(8, counts["tt3"])
        assertNull(counts["tmdb:99"])
        assertNull(counts["tt4"])
    }

    private fun show(trakt: Int, imdb: String, tmdb: Int, aired: Int?) = TraktWatchedShowSnapshotItem(
        show = TraktWatchedSnapshotMedia(
            title = imdb,
            ids = TraktWatchedSnapshotIds(trakt = trakt, imdb = imdb, tmdb = tmdb),
            airedEpisodes = aired,
        ),
    )

    private fun detailedResponse(url: String): RawHttpResponse = RawHttpResponse(
        status = 200,
        statusText = "OK",
        url = url,
        body = """
            [{
              "last_watched_at": "2026-08-09T10:00:00.000Z",
              "show": {"title": "Example", "ids": {"trakt": 1, "imdb": "tt1"}, "aired_episodes": 12},
              "seasons": [{"number": 1, "episodes": [{"number": 1, "plays": 1}]}]
            }]
        """.trimIndent(),
        headers = mapOf("X-Pagination-Page-Count" to "1"),
    )

    private fun headers(): Map<String, String> = mapOf("Authorization" to "Bearer test")

    private fun response(url: String): RawHttpResponse = RawHttpResponse(
        status = 200,
        statusText = "OK",
        url = url,
        body = """
            [{
              "last_watched_at": "2026-08-09T10:00:00.000Z",
              "show": {"title": "Example", "ids": {"trakt": 1, "imdb": "tt1"}},
              "seasons": [{"number": 1, "episodes": [{"number": 1, "plays": 1}]}]
            }]
        """.trimIndent(),
        headers = mapOf("X-Pagination-Page-Count" to "1"),
    )
}
