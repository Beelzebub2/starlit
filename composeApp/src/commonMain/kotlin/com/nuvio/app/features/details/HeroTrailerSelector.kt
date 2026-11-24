package com.nuvio.app.features.details

import com.nuvio.app.core.build.TrailerPlaybackMode
import io.ktor.http.Url
import io.ktor.http.encodedPath

private val YouTubeVideoId = Regex("[A-Za-z0-9_-]{1,32}")
private val YouTubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
private val YouTubeEmbedHosts = setOf("youtube-nocookie.com", "www.youtube-nocookie.com")

internal fun selectHeroTrailer(trailers: List<MetaTrailer>): MetaTrailer? =
    trailers
        .asSequence()
        .filter { it.youtubeVideoId() != null }
        .distinctBy { it.youtubeVideoId() }
        .maxWithOrNull(
            compareBy<MetaTrailer>(
                { it.fromTmdb },
                { it.heroTrailerPriority() },
                { it.publishedAt.orEmpty() },
                { it.size ?: 0 },
                { it.name },
            ),
        )

internal sealed interface TrailerClickTarget {
    val url: String

    data class External(override val url: String) : TrailerClickTarget
    data class InAppYouTube(override val url: String) : TrailerClickTarget
}

internal fun MetaTrailer.trailerClickTarget(mode: TrailerPlaybackMode): TrailerClickTarget? {
    // Add-on media is intentionally handed off only on click. Native players cannot
    // enforce the initial URL policy across redirects and media references.
    validDirectTrailerUrl(directUrl)?.let { return TrailerClickTarget.External(it) }
    val youtubeUrl = youtubePlaybackUrl() ?: return null
    return when (mode) {
        TrailerPlaybackMode.EXTERNAL -> TrailerClickTarget.External(youtubeUrl)
        TrailerPlaybackMode.IN_APP -> TrailerClickTarget.InAppYouTube(youtubeUrl)
    }
}

internal fun MetaTrailer.youtubePlaybackUrl(): String? =
    youtubeVideoId()?.let { "https://www.youtube.com/watch?v=$it" }

internal fun MetaTrailer.youtubeThumbnailUrl(): String? =
    youtubeVideoId()?.let { "https://img.youtube.com/vi/$it/hqdefault.jpg" }

internal fun MetaTrailer.playbackIdentity(): String? =
    validDirectTrailerUrl(directUrl)?.let { "direct:$it" }
        ?: youtubeVideoId()?.let { "youtube:$it" }

internal fun mergeMetadataTrailers(primary: List<MetaTrailer>, additional: List<MetaTrailer>): List<MetaTrailer> =
    (primary + additional).mapNotNull { trailer ->
        trailer.playbackIdentity()?.let { it to trailer }
    }.distinctBy { (identity, _) -> identity }.map { (_, trailer) -> trailer }

internal fun validDirectTrailerUrl(value: String?): String? {
    val input = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (!input.startsWith("https://", ignoreCase = true) && !input.startsWith("http://", ignoreCase = true)) return null
    if (input.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
    val authority = input.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
    if (authority.isBlank() || '@' in authority || '%' in authority) return null
    val url = runCatching { Url(input) }.getOrNull() ?: return null
    if (url.host.isBlank() || url.port !in 1..65535) return null
    return input
}

private fun MetaTrailer.youtubeVideoId(): String? {
    if (directUrl != null || !site.equals("YouTube", ignoreCase = true)) return null
    val input = key.trim()
    if (YouTubeVideoId.matches(input)) return input

    val url = validDirectTrailerUrl(input)?.let { runCatching { Url(it) }.getOrNull() } ?: return null
    val path = url.encodedPath.trim('/').split('/')
    val host = url.host.lowercase()
    val id = when {
        host == "youtu.be" -> path.singleOrNull()
        host in YouTubeHosts && path == listOf("watch") -> url.parameters["v"]
        host in YouTubeHosts && path.size == 2 && path[0] in setOf("embed", "shorts", "live", "v") -> path[1]
        host in YouTubeEmbedHosts && path.size == 2 && path[0] == "embed" -> path[1]
        else -> null
    }
    return id?.takeIf(YouTubeVideoId::matches)
}

private fun MetaTrailer.heroTrailerPriority(): Int {
    val isSeriesTrailer = seasonNumber != null
    val isTrailerType = type.equals("Trailer", ignoreCase = true)
    return when {
        !isSeriesTrailer && isTrailerType && official -> 70
        !isSeriesTrailer && isTrailerType -> 60
        !isSeriesTrailer && official -> 50
        !isSeriesTrailer -> 40
        isTrailerType && official -> 30
        isTrailerType -> 20
        official -> 10
        else -> 0
    }
}
