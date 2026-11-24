package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal enum class SimklScrobbleOutcome {
    START,
    PAUSE,
    SCROBBLE,
}

internal enum class SimklRewatchResult {
    NOT_REQUESTED,
    FIRST_WATCH,
    RECORDED,
    PLAN_REQUIRED,
    UNCONFIRMED,
}

internal data class SimklScrobbleResult(
    val outcome: SimklScrobbleOutcome,
    val playbackId: Long?,
    val progress: Double,
    val mediaType: SimklMediaType,
    val media: SimklMedia,
    val episode: SimklPlaybackEpisode?,
    val watchedAt: String? = null,
    val rewatchResult: SimklRewatchResult = SimklRewatchResult.NOT_REQUESTED,
) {
    val rewatchPlanRejected: Boolean get() = rewatchResult == SimklRewatchResult.PLAN_REQUIRED
}

internal fun SimklApiResponse.toSimklScrobbleResult(
    requestedAction: TrackingScrobbleAction,
    event: TrackingScrobbleEvent,
    json: Json,
    completionThreshold: Int = 80,
    requestedRewatch: Boolean = false,
): SimklScrobbleResult {
    val payload = body
        .takeIf(String::isNotBlank)
        ?.let { value -> runCatching { json.parseToJsonElement(value).jsonObject }.getOrNull() }
        ?: JsonObject(emptyMap())
    val responseMediaType = payload.responseMediaType()
    val mediaType = responseMediaType ?: event.media.kind.toSimklMediaType()
    val responseMedia = responseMediaType
        ?.let { type -> payload.media(type, json) }
    val fallbackMedia = event.media.toSimklMedia()
    val episode = payload.episode(json, event)
    val progress = payload.doubleValue("progress")
        ?.coerceIn(0.0, 100.0)
        ?: event.progressPercent.coerceIn(0.0, 100.0)
    val responseAction = payload.stringValue("action")?.toSimklScrobbleOutcome()
    val outcome = when {
        isSoftSuccess && status == 409 -> SimklScrobbleOutcome.SCROBBLE
        else -> responseAction ?: requestedAction.fallbackOutcome(progress, completionThreshold)
    }
    return SimklScrobbleResult(
        outcome = outcome,
        playbackId = payload.longValue("id") ?: payload.longValue("sid"),
        progress = progress,
        mediaType = mediaType,
        media = responseMedia?.mergeMissing(fallbackMedia) ?: fallbackMedia,
        episode = episode,
        watchedAt = payload.stringValue("watched_at")
            ?.takeIf { value -> parseSimklUtcEpochMs(value) != null },
        rewatchResult = when {
            !requestedRewatch -> SimklRewatchResult.NOT_REQUESTED
            else -> when (payload.stringValue("rewatch_status")) {
                "first_watch" -> SimklRewatchResult.FIRST_WATCH
                "active", "completed", "closed" -> SimklRewatchResult.RECORDED
                "pro_required" -> SimklRewatchResult.PLAN_REQUIRED
                // A stop below the server's threshold can still save normal resume progress.
                null -> if (responseAction == SimklScrobbleOutcome.PAUSE) {
                    SimklRewatchResult.NOT_REQUESTED
                } else SimklRewatchResult.UNCONFIRMED
                else -> SimklRewatchResult.UNCONFIRMED
            }
        },
    )
}

internal fun SimklMedia.mergeMissing(fallback: SimklMedia?): SimklMedia {
    if (fallback == null) return this
    return copy(
        title = title?.takeIf(String::isNotBlank) ?: fallback.title,
        poster = poster?.takeIf(String::isNotBlank) ?: fallback.poster,
        year = year ?: fallback.year,
        runtime = runtime ?: fallback.runtime,
        ids = fallback.ids + ids,
    )
}

private fun JsonObject.responseMediaType(): SimklMediaType? = when {
    get("movie") is JsonObject -> SimklMediaType.MOVIES
    get("anime") is JsonObject -> SimklMediaType.ANIME
    get("show") is JsonObject -> SimklMediaType.SHOWS
    else -> null
}

private fun JsonObject.media(type: SimklMediaType, json: Json): SimklMedia? {
    val key = when (type) {
        SimklMediaType.MOVIES -> "movie"
        SimklMediaType.SHOWS -> "show"
        SimklMediaType.ANIME -> "anime"
    }
    val value = get(key) ?: return null
    return runCatching { json.decodeFromJsonElement<SimklMedia>(value) }.getOrNull()
}

private fun JsonObject.episode(
    json: Json,
    event: TrackingScrobbleEvent,
): SimklPlaybackEpisode? {
    val responseEpisode = get("episode")
        ?.let { value ->
            runCatching { json.decodeFromJsonElement<SimklPlaybackEpisode>(value) }.getOrNull()
        }
    val fallback = event.media.episode?.let { episode ->
        SimklPlaybackEpisode(
            season = episode.season,
            number = episode.number,
            title = episode.title,
        )
    }
    val base = responseEpisode ?: fallback ?: return null
    return base.copy(
        tvdbSeason = base.tvdbSeason ?: intValue("tvdb_season"),
        tvdbNumber = base.tvdbNumber ?: intValue("tvdb_number"),
    )
}

internal fun TrackingMediaReference.toSimklMedia(): SimklMedia = SimklMedia(
    title = title?.takeIf(String::isNotBlank),
    year = year,
    ids = ids.toSimklJsonObjectOrNull()?.toMap().orEmpty(),
)

internal fun TrackingMediaKind.toSimklMediaType(): SimklMediaType = when (this) {
    TrackingMediaKind.MOVIE -> SimklMediaType.MOVIES
    TrackingMediaKind.SHOW -> SimklMediaType.SHOWS
    TrackingMediaKind.ANIME -> SimklMediaType.ANIME
}

private fun TrackingScrobbleAction.fallbackOutcome(progress: Double, completionThreshold: Int): SimklScrobbleOutcome =
    when (this) {
        TrackingScrobbleAction.START -> SimklScrobbleOutcome.START
        TrackingScrobbleAction.PAUSE -> SimklScrobbleOutcome.PAUSE
        TrackingScrobbleAction.STOP -> {
            if (progress >= completionThreshold) SimklScrobbleOutcome.SCROBBLE else SimklScrobbleOutcome.PAUSE
        }
    }

private fun String.toSimklScrobbleOutcome(): SimklScrobbleOutcome? = when (lowercase()) {
    "start" -> SimklScrobbleOutcome.START
    "pause" -> SimklScrobbleOutcome.PAUSE
    "scrobble" -> SimklScrobbleOutcome.SCROBBLE
    else -> null
}

private fun JsonObject.stringValue(key: String): String? =
    runCatching { get(key)?.jsonPrimitive?.content }
        .getOrNull()
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private fun JsonObject.longValue(key: String): Long? = stringValue(key)?.toLongOrNull()

private fun JsonObject.intValue(key: String): Int? = stringValue(key)?.toIntOrNull()

private fun JsonObject.doubleValue(key: String): Double? = stringValue(key)?.toDoubleOrNull()
