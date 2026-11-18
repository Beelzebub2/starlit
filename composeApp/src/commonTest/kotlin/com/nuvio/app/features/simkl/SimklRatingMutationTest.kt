package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SimklRatingMutationTest {
    private val animeRef = TrackingMediaReference(
        kind = TrackingMediaKind.ANIME,
        title = "Attack on Titan",
        ids = TrackingExternalIds(simkl = 39687, mal = 16498),
    )
    private val movieRef = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        title = "Terminator 3",
        ids = TrackingExternalIds(simkl = 53536, imdb = "tt0181852"),
    )
    private val showRef = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "The Walking Dead",
        ids = TrackingExternalIds(simkl = 1, imdb = "tt1520211"),
    )

    @Test
    fun ratingBodyUsesTheRightBucket() {
        val anime = Json.parseToJsonElement(buildSimklRatingBody(animeRef, 8, Json)).jsonObject
        val animeItem = anime.getValue("shows").jsonArray.single().jsonObject
        assertEquals(8, animeItem.getValue("rating").jsonPrimitive.content.toInt())
        assertEquals(16498L, animeItem.getValue("ids").jsonObject.getValue("mal").jsonPrimitive.content.toLong())
        assertNull(anime["anime"])

        val movie = Json.parseToJsonElement(buildSimklRatingBody(movieRef, 6, Json)).jsonObject
        assertEquals(6, movie.getValue("movies").jsonArray.single().jsonObject.getValue("rating").jsonPrimitive.content.toInt())

        val show = Json.parseToJsonElement(buildSimklRatingBody(showRef, 9, Json)).jsonObject
        assertEquals(1, show.getValue("shows").jsonArray.size)
    }

    @Test
    fun removalBodyOmitsRating() {
        val body = Json.parseToJsonElement(buildSimklRatingBody(animeRef, 0, Json)).jsonObject
        assertNull(body.getValue("shows").jsonArray.single().jsonObject["rating"])
        assertNull(body["anime"])
    }

    @Test
    fun clearingRatingTargetsRemoveEndpoint(): Unit = runBlocking {
        val engine = RecordingEngine()
        val service = SimklMutationService(
            client = SimklApiClient(
                engine = engine,
                accessToken = { "token" },
                onUnauthorized = {},
                nowEpochMs = { 0L },
                sleep = {},
                retryJitterMs = { 0L },
            ),
        )
        service.setRating(movieRef, 0)
        service.setRating(movieRef, 7)
        assertEquals(listOf("/sync/ratings/remove", "/sync/ratings"), engine.paths)
        assertFailsWith<IllegalArgumentException> { service.setRating(movieRef, 11) }
    }

    @Test
    fun notFoundResponseDoesNotCommitLocally(): Unit = runBlocking {
        var commits = 0
        val engine = RecordingEngine(responseBody = """{"not_found":{"movies":[{"ids":{"simkl":53536}}]}}""")
        val service = SimklMutationService(
            client = SimklApiClient(
                engine = engine,
                accessToken = { "token" },
                onUnauthorized = {},
                nowEpochMs = { 0L },
                sleep = {},
                retryJitterMs = { 0L },
            ),
            onRatingCommitted = { _, _ -> commits += 1 },
        )
        val result = service.setRating(movieRef, 8)
        assertEquals(1, result.notFoundCount)
        assertEquals(0, commits)
        val ok = SimklMutationService(
            client = SimklApiClient(
                engine = RecordingEngine(),
                accessToken = { "token" },
                onUnauthorized = {},
                nowEpochMs = { 0L },
                sleep = {},
                retryJitterMs = { 0L },
            ),
            onRatingCommitted = { _, _ -> commits += 1 },
        )
        ok.setRating(movieRef, 8)
        assertEquals(1, commits)
    }

    private class RecordingEngine(private val responseBody: String = "{}") : SimklHttpEngine {
        val paths = mutableListOf<String>()
        override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String): RawHttpResponse {
            paths += url.substringAfter("api.simkl.com").substringBefore('?')
            return RawHttpResponse(status = 201, statusText = "", url = url, body = responseBody, headers = emptyMap())
        }
    }
}
