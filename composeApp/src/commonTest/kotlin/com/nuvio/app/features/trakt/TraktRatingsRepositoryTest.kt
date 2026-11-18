package com.nuvio.app.features.trakt

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.tracking.TrackingCatalogReference
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val OK_WRITE = """{"added":{"movies":1,"shows":1},"deleted":{"movies":1,"shows":1},"not_found":{"movies":[],"shows":[]}}"""

/** Records every request instead of calling Trakt. */
private class RecordingTraktHttp(
    private val respond: (TraktJsonRequest) -> RawHttpResponse = { request -> ok(request, OK_WRITE) },
) : TraktListHttp {
    val requests = mutableListOf<TraktJsonRequest>()

    override suspend fun send(request: TraktJsonRequest, headers: Map<String, String>): RawHttpResponse {
        requests += request
        return respond(request)
    }
}

private fun ok(request: TraktJsonRequest, body: String, headers: Map<String, String> = emptyMap()) =
    RawHttpResponse(status = 200, statusText = "OK", url = request.url, body = body, headers = headers)

private fun client(http: TraktListHttp, signedIn: Boolean = true) = TraktListClient(
    http = http,
    headers = { if (signedIn) mapOf("Authorization" to "Bearer test") else null },
)

private val movie = TrackingMediaReference(
    kind = TrackingMediaKind.MOVIE,
    ids = TrackingExternalIds(imdb = "tt1", trakt = 10),
)

private val show = TrackingMediaReference(
    kind = TrackingMediaKind.SHOW,
    ids = TrackingExternalIds(imdb = "tt2", tmdb = 22, trakt = 20),
)

class TraktRatingsRepositoryTest {
    @Test
    fun ratingPostAndRemoveTargetCorrectEndpoints(): Unit = runBlocking {
        val http = RecordingTraktHttp()
        client(http).setRating(1, movie, 9)
        client(http).setRating(1, movie, 0)

        val (rate, remove) = http.requests
        assertEquals("POST", rate.method)
        assertEquals("https://api.trakt.tv/sync/ratings", rate.url)
        assertEquals("""{"movies":[{"rating":9,"ids":{"trakt":10,"imdb":"tt1"}}]}""", rate.body)
        assertTrue(rate.body.contains("\"rating\":9"))
        assertEquals("POST", remove.method)
        assertEquals("https://api.trakt.tv/sync/ratings/remove", remove.url)
        assertEquals("""{"movies":[{"ids":{"trakt":10,"imdb":"tt1"}}]}""", remove.body)
    }

    @Test
    fun animeSeriesRateAsShowsAndAnimeMoviesAsMovies(): Unit = runBlocking {
        val http = RecordingTraktHttp()
        val animeSeries = TrackingMediaReference(
            kind = TrackingMediaKind.ANIME,
            ids = TrackingExternalIds(imdb = "tt3", mal = 5),
            catalog = TrackingCatalogReference(contentId = "tt3", contentType = "series"),
        )
        val animeMovie = TrackingMediaReference(
            kind = TrackingMediaKind.ANIME,
            ids = TrackingExternalIds(imdb = "tt4", mal = 6),
            catalog = TrackingCatalogReference(contentId = "tt4", contentType = "movie"),
        )
        client(http).setRating(1, animeSeries, 7)
        client(http).setRating(1, animeMovie, 8)
        client(http).setRating(1, show, 6)

        assertEquals("""{"shows":[{"rating":7,"ids":{"imdb":"tt3"}}]}""", http.requests[0].body)
        assertEquals("""{"movies":[{"rating":8,"ids":{"imdb":"tt4"}}]}""", http.requests[1].body)
        assertEquals("""{"shows":[{"rating":6,"ids":{"trakt":20,"imdb":"tt2","tmdb":22}}]}""", http.requests[2].body)
    }

    @Test
    fun dropTargetsHiddenDroppedEndpoints(): Unit = runBlocking {
        val http = RecordingTraktHttp()
        client(http).setDropped(1, show, dropped = true)
        client(http).setDropped(1, show, dropped = false)

        val (drop, undrop) = http.requests
        assertEquals("POST", drop.method)
        assertEquals("https://api.trakt.tv/users/hidden/dropped", drop.url)
        assertEquals("""{"shows":[{"ids":{"trakt":20,"imdb":"tt2","tmdb":22}}]}""", drop.body)
        assertEquals("POST", undrop.method)
        assertEquals("https://api.trakt.tv/users/hidden/dropped/remove", undrop.url)
        assertEquals(drop.body, undrop.body)
        assertFailsWith<IllegalArgumentException> { client(http).setDropped(1, movie, dropped = true) }
        assertEquals(2, http.requests.size)
    }

    @Test
    fun ratingsReadKeysByTraktIdAndContentIds(): Unit = runBlocking {
        val http = RecordingTraktHttp { request ->
            when {
                request.url.contains("/sync/ratings/movies") -> ok(
                    request,
                    """[{"rating":9,"type":"movie","movie":{"title":"Film","ids":{"trakt":10,"imdb":"tt1","tmdb":11}}}]""",
                    headers = mapOf("X-Pagination-Page-Count" to "1"),
                )
                request.url.contains("/sync/ratings/shows") -> ok(
                    request,
                    """[{"rating":6,"type":"show","show":{"title":"Show","ids":{"trakt":20,"imdb":"tt2"}}}]""",
                    headers = mapOf("X-Pagination-Page-Count" to "1"),
                )
                else -> error("unexpected ${request.url}")
            }
        }
        val ratings = client(http).ratings(1)

        assertTrue(http.requests.all { it.method == "GET" })
        assertTrue(http.requests.any { it.url.startsWith("https://api.trakt.tv/sync/ratings/movies") })
        assertTrue(http.requests.any { it.url.startsWith("https://api.trakt.tv/sync/ratings/shows") })
        assertEquals(9, ratings["movie:10"])
        assertEquals(9, ratings["movie:tt1"])
        assertEquals(9, ratings["movie:tmdb:11"])
        assertEquals(9, ratings["movie:trakt:10"])
        assertEquals(6, ratings["show:20"])
        assertEquals(6, ratings["show:tt2"])
        assertEquals(null, ratings["show:10"])
    }

    @Test
    fun rejectedOrUnidentifiableWritesFailLoudly(): Unit = runBlocking {
        val notFound = RecordingTraktHttp { request ->
            ok(request, """{"added":{"movies":0},"not_found":{"movies":[{"ids":{"imdb":"tt1"}}]}}""")
        }
        assertFailsWith<IllegalStateException> { client(notFound).setRating(1, movie, 9) }

        val failing = RecordingTraktHttp { request ->
            RawHttpResponse(status = 500, statusText = "Error", url = request.url, body = "", headers = emptyMap())
        }
        assertFailsWith<IllegalStateException> { client(failing).setDropped(1, show, dropped = true) }

        val unused = RecordingTraktHttp()
        assertFailsWith<IllegalStateException> { client(unused, signedIn = false).setRating(1, movie, 9) }
        assertFailsWith<IllegalStateException> {
            client(unused).setRating(1, TrackingMediaReference(kind = TrackingMediaKind.MOVIE, title = "No ids"), 9)
        }
        assertFailsWith<IllegalArgumentException> { client(unused).setRating(1, movie, 11) }
        assertTrue(unused.requests.isEmpty())
    }
}
