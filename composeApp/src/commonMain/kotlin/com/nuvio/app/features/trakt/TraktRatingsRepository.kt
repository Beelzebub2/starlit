package com.nuvio.app.features.trakt

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val TRAKT_LIST_API_URL = "https://api.trakt.tv"
private const val RATINGS_PAGE_LIMIT = 1_000
private const val RATINGS_MAX_PAGES = 100
private const val MOVIES_BUCKET = "movies"
private const val SHOWS_BUCKET = "shows"

private val traktListJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** One Trakt API call, built by pure functions so tests can check the URL and body without the network. */
internal data class TraktJsonRequest(val method: String, val url: String, val body: String = "")

internal fun interface TraktListHttp {
    suspend fun send(request: TraktJsonRequest, headers: Map<String, String>): RawHttpResponse
}

private val platformTraktListHttp = TraktListHttp { request, headers ->
    val jsonHeaders = if (request.method == "GET") {
        mapOf("Accept" to "application/json")
    } else {
        mapOf("Accept" to "application/json", "Content-Type" to "application/json")
    }
    httpRequestRaw(
        method = request.method,
        url = request.url,
        headers = jsonHeaders + headers,
        body = request.body,
        followRedirects = true,
        maxResponseBodyBytes = TRAKT_WATCHED_MAX_RESPONSE_BODY_BYTES,
    )
}

/** Trakt only has movies and shows: anime series go to `shows`, anime movies to `movies`. */
internal fun traktListBucket(media: TrackingMediaReference): String = when (media.kind) {
    TrackingMediaKind.MOVIE -> MOVIES_BUCKET
    TrackingMediaKind.SHOW -> SHOWS_BUCKET
    TrackingMediaKind.ANIME ->
        if (media.catalog?.contentType?.trim()?.lowercase() in setOf("movie", "film")) MOVIES_BUCKET else SHOWS_BUCKET
}

/** `1..10` posts to `/sync/ratings`; `0` clears through `/sync/ratings/remove`. */
internal fun buildTraktRatingRequest(media: TrackingMediaReference, rating: Int): TraktJsonRequest {
    require(rating in 0..10) { "Trakt ratings range from 1 to 10 (0 clears the rating)" }
    val bucket = traktListBucket(media)
    val item = buildJsonObject {
        if (rating > 0) put("rating", rating)
        put("ids", traktIdsJson(media.ids, bucket))
    }
    val url = if (rating == 0) "$TRAKT_LIST_API_URL/sync/ratings/remove" else "$TRAKT_LIST_API_URL/sync/ratings"
    return TraktJsonRequest(method = "POST", url = url, body = singleItemBody(bucket, item))
}

/** Dropping adds the show to `users/hidden/dropped` (which also hides it from progress); undropping removes it. */
internal fun buildTraktDropRequest(media: TrackingMediaReference, dropped: Boolean): TraktJsonRequest {
    val bucket = traktListBucket(media)
    require(bucket == SHOWS_BUCKET) { "Only shows can be dropped on Trakt" }
    val item = buildJsonObject { put("ids", traktIdsJson(media.ids, bucket)) }
    val url = "$TRAKT_LIST_API_URL/users/hidden/dropped" + if (dropped) "" else "/remove"
    return TraktJsonRequest(method = "POST", url = url, body = singleItemBody(bucket, item))
}

internal fun buildTraktRatingsPageRequest(bucket: String, page: Int): TraktJsonRequest =
    TraktJsonRequest(method = "GET", url = "$TRAKT_LIST_API_URL/sync/ratings/$bucket?page=$page&limit=$RATINGS_PAGE_LIMIT")

private fun singleItemBody(bucket: String, item: JsonObject): String =
    buildJsonObject { put(bucket, buildJsonArray { add(item) }) }.toString()

private fun traktIdsJson(ids: TrackingExternalIds, bucket: String): JsonObject {
    val json = buildJsonObject {
        ids.trakt?.let { put("trakt", it) }
        ids.imdb?.trim()?.takeIf(String::isNotBlank)?.let { put("imdb", it) }
        ids.tmdb?.let { put("tmdb", it) }
        if (bucket == SHOWS_BUCKET) ids.tvdb?.trim()?.toLongOrNull()?.let { put("tvdb", it) }
    }
    if (json.isEmpty()) throw IllegalStateException("Trakt needs an IMDb, TMDB, TVDB or Trakt id for this title")
    return json
}

/**
 * Ratings keyed as `"movie:<traktId>"` / `"show:<traktId>"`, plus the same rating under each
 * Starlit content id form (`"movie:tt1"`, `"movie:tmdb:5"`, `"movie:trakt:10"`) so callers holding
 * only a content id can look it up.
 */
internal fun parseTraktRatings(kind: String, body: String): Map<String, Int> {
    val items = traktListJson.decodeFromString<List<TraktRatingItemDto>>(body)
    return buildMap {
        items.forEach { item ->
            val rating = item.rating?.takeIf { it in 1..10 } ?: return@forEach
            val ids = (item.movie ?: item.show)?.ids ?: return@forEach
            ids.trakt?.let {
                put("$kind:$it", rating)
                put("$kind:trakt:$it", rating)
            }
            ids.imdb?.trim()?.takeIf(String::isNotBlank)?.let { put("$kind:$it", rating) }
            ids.tmdb?.let { put("$kind:tmdb:$it", rating) }
        }
    }
}

/** Total of the `not_found` arrays in a Trakt sync/hidden write response; 0 when the body has none. */
internal fun traktNotFoundCount(body: String): Int {
    val notFound = runCatching { traktListJson.parseToJsonElement(body).jsonObject["not_found"] }
        .getOrNull() as? JsonObject ?: return 0
    return notFound.values.sumOf { value -> (value as? JsonArray)?.size ?: 0 }
}

private fun checkTraktStatus(response: RawHttpResponse) {
    when {
        response.status in 200..299 -> Unit
        response.status == 401 || response.status == 403 ->
            throw IllegalStateException("Trakt authorization expired. Reconnect Trakt and try again.")
        response.status == 429 -> throw IllegalStateException("Trakt rate limit reached. Try again shortly.")
        else -> throw IllegalStateException("Trakt request failed (${response.status})")
    }
}

/** Rating and dropped-list calls; [headers] returns null when the profile has no Trakt connection. */
internal class TraktListClient(
    private val http: TraktListHttp,
    private val headers: suspend (profileId: Int) -> Map<String, String>?,
) {
    suspend fun ratings(profileId: Int): Map<String, Int> {
        val auth = headers(profileId) ?: return emptyMap()
        return readRatings(auth, MOVIES_BUCKET, "movie") + readRatings(auth, SHOWS_BUCKET, "show")
    }

    suspend fun setRating(profileId: Int, media: TrackingMediaReference, rating: Int) =
        write(profileId, buildTraktRatingRequest(media, rating))

    suspend fun setDropped(profileId: Int, media: TrackingMediaReference, dropped: Boolean) =
        write(profileId, buildTraktDropRequest(media, dropped))

    private suspend fun write(profileId: Int, request: TraktJsonRequest) {
        val auth = headers(profileId) ?: throw IllegalStateException("Trakt is not connected")
        val response = http.send(request, auth)
        checkTraktStatus(response)
        if (traktNotFoundCount(response.body) > 0) throw IllegalStateException("Trakt could not find this title")
    }

    private suspend fun readRatings(auth: Map<String, String>, bucket: String, kind: String): Map<String, Int> {
        val ratings = mutableMapOf<String, Int>()
        var page = 1
        while (page <= RATINGS_MAX_PAGES) {
            val response = http.send(buildTraktRatingsPageRequest(bucket, page), auth)
            checkTraktStatus(response)
            val pageRatings = parseTraktRatings(kind, response.body)
            ratings += pageRatings
            val pageCount = response.headers.entries
                .firstOrNull { (name, _) -> name.equals("x-pagination-page-count", ignoreCase = true) }
                ?.value?.substringBefore(',')?.trim()?.toIntOrNull()
            if (pageRatings.isEmpty() || pageCount == null || page >= pageCount) break
            page += 1
        }
        return ratings
    }
}

internal val defaultTraktListClient = TraktListClient(platformTraktListHttp) { profileId ->
    TraktAuthRepository.authorizedHeaders(profileId)
}

/** The user's Trakt ratings (new read/write; nothing else in the app rates on Trakt). */
object TraktRatingsRepository {
    /** Keys: `"movie:<traktId>"` / `"show:<traktId>"`, plus content-id aliases (see [parseTraktRatings]). */
    suspend fun ratings(profileId: Int): Map<String, Int> = defaultTraktListClient.ratings(profileId)

    /** `0` removes the rating. Throws when Trakt rejects the write or cannot identify the title. */
    suspend fun setRating(profileId: Int, media: TrackingMediaReference, rating: Int) =
        defaultTraktListClient.setRating(profileId, media, rating)
}

@Serializable
private data class TraktRatingItemDto(
    @SerialName("rating") val rating: Int? = null,
    @SerialName("movie") val movie: TraktRatedMediaDto? = null,
    @SerialName("show") val show: TraktRatedMediaDto? = null,
)

@Serializable
private data class TraktRatedMediaDto(
    @SerialName("ids") val ids: TraktExternalIds? = null,
)
