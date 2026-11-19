package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.encodeAddonPathSegment
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.player.skip.SimklIdResolver
import com.nuvio.app.features.player.skip.resolveContentSkipImdbId
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_addon_subtitle_display_format
import org.jetbrains.compose.resources.getString

@Serializable
data class SubtitleAddonRequest(
    val url: String,
    val addonId: String,
    val addonName: String,
    val fallbackUrl: String? = null,
)

internal data class SubtitleLookup(
    val type: String,
    val videoId: String?,
    val contentId: String? = null,
    val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val extraPath: String? = null,
) {
    fun videoIds(): List<String> {
        val exactId = videoId?.trim()?.takeIf(String::isNotEmpty)
        val requestType = canonicalSubtitleType(type)
        val canonical = listOf(exactId, imdbId, contentId)
            .firstNotNullOfOrNull { it?.trim()?.substringBefore(':')?.takeIf(imdbSubtitleId::matches) }
        if (requestType == "movie") return listOfNotNull(exactId, canonical, contentId?.trim())
            .filter(String::isNotEmpty).distinct()
        if (requestType != "series") return listOfNotNull(exactId)

        val parts = exactId.orEmpty().split(':')
        val numberedId = parts.size >= 3 && parts.first() !in listOf("mal", "kitsu") &&
            parts.takeLast(2).all { it.toIntOrNull() != null }
        val resolvedSeason = season ?: parts.takeIf { numberedId }?.get(parts.lastIndex - 1)?.toIntOrNull()
        val resolvedEpisode = episode ?: parts.takeIf { numberedId }?.last()?.toIntOrNull()
        val canonicalEpisode = if (canonical != null && resolvedSeason != null && resolvedSeason >= 0 &&
            resolvedEpisode != null && resolvedEpisode > 0
        ) "$canonical:$resolvedSeason:$resolvedEpisode" else null
        // A bare IMDb ID would query the whole series instead of the episode.
        return listOfNotNull(exactId?.takeUnless(imdbSubtitleId::matches), canonicalEpisode).distinct()
    }

    suspend fun resolve(): SubtitleLookup {
        val requestType = canonicalSubtitleType(type)
        if (requestType !in setOf("movie", "series") ||
            videoIds().any { imdbSubtitleId.matches(it.substringBefore(':')) }
        ) return this

        val resolvedImdb = resolveContentSkipImdbId(
            contentId, videoId, imdbId,
            resolveTmdb = { TmdbService.tmdbToImdb(it, requestType) },
            resolveAnime = { source, id -> SimklIdResolver.resolveIds(source, id)?.imdb },
        ) ?: return this
        return copy(imdbId = resolvedImdb)
    }
}

internal fun StreamItem.subtitleRequestExtra(): String? =
    behaviorHints.subtitleRequestExtra(clientResolve?.filename)

internal fun StreamBehaviorHints.subtitleRequestExtra(fallbackFilename: String? = null): String? = listOfNotNull(
    videoHash?.trim()?.takeIf(String::isNotEmpty)?.let { "videoHash=" + it.encodeAddonPathSegment() },
    videoSize?.takeIf { it > 0 }?.let { "videoSize=$it" },
    (filename?.takeIf(String::isNotBlank) ?: fallbackFilename)?.trim()?.takeIf(String::isNotEmpty)
        ?.let { "filename=" + it.encodeAddonPathSegment() },
).joinToString("&").ifEmpty { null }

private val imdbSubtitleId = Regex("tt[0-9]+", RegexOption.IGNORE_CASE)

internal fun addonSubtitleRequests(
    lookup: SubtitleLookup,
    addons: List<ManagedAddon> = AddonRepository.uiState.value.addons,
): List<SubtitleAddonRequest> {
    val requestType = canonicalSubtitleType(lookup.type)
    val videoIds = lookup.videoIds()
    return addons.enabledAddons().flatMap { addon ->
        val manifest = addon.manifest ?: return@flatMap emptyList()
        videoIds.filter { videoId ->
            manifest.resources.any { resource ->
                resource.isSubtitleResourceFor(requestType) &&
                    (resource.idPrefixes.isEmpty() || resource.idPrefixes.any { videoId.startsWith(it) })
            }
        }.map { videoId ->
            val baseUrl = buildAddonResourceUrl(manifest.transportUrl, "subtitles", requestType, videoId)
            SubtitleAddonRequest(
                url = lookup.extraPath?.let {
                    buildAddonResourceUrl(manifest.transportUrl, "subtitles", requestType, videoId, extraPathSegment = it)
                } ?: baseUrl,
                addonId = manifest.id,
                addonName = addon.displayTitle,
                fallbackUrl = baseUrl.takeIf { lookup.extraPath != null },
            )
        }
    }.distinctBy { it.url }
}

internal fun AddonResource.isSubtitleResourceFor(type: String): Boolean =
    (name.equals("subtitles", true) || name.equals("subtitle", true)) &&
        (types.isEmpty() || types.any { canonicalSubtitleType(it) == canonicalSubtitleType(type) })

internal suspend fun loadAddonSubtitles(
    requests: List<SubtitleAddonRequest>,
    onLoaded: (SubtitleAddonRequest, List<AddonSubtitle>) -> Unit = { _, _ -> },
): List<AddonSubtitle> = supervisorScope {
    requests.map { request ->
        async {
            val subtitles = fetchSubtitleRequest(request.url, request).ifEmpty {
                request.fallbackUrl?.let { fetchSubtitleRequest(it, request) }.orEmpty()
            }
            currentCoroutineContext().ensureActive()
            onLoaded(request, subtitles)
            subtitles
        }
    }.awaitAll().flatten().distinctBy { it.url to it.language }
}

private suspend fun fetchSubtitleRequest(url: String, request: SubtitleAddonRequest): List<AddonSubtitle> = try {
    withTimeoutOrNull(if (url == request.url && request.fallbackUrl != null) 4_000L else 10_000L) {
        parseAddonSubtitles(fetchAddonResponseText(url), request)
    }.orEmpty()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    emptyList()
}

private suspend fun parseAddonSubtitles(response: String, request: SubtitleAddonRequest): List<AddonSubtitle> {
    val subtitles = Json.parseToJsonElement(response).jsonObject["subtitles"]?.jsonArray.orEmpty()
    return subtitles.mapIndexedNotNull { index, element ->
        val obj = element as? JsonObject ?: return@mapIndexedNotNull null
        val url = obj.stringValue("url") ?: return@mapIndexedNotNull null
        // A descriptive track label is not a language code.
        val language = listOf("lang", "language", "languageCode", "locale")
            .firstNotNullOfOrNull(obj::stringValue) ?: "unknown"
        val displayLabel = addonSubtitleDisplayLabel(
            label = obj.stringValue("label"),
            name = obj.stringValue("name"),
            title = obj.stringValue("title"),
        )
        AddonSubtitle(
            id = obj.stringValue("id") ?: "${request.addonId}_$index",
            url = url,
            language = normalizeLanguageCode(language) ?: language,
            display = displayLabel ?: getString(
                Res.string.player_addon_subtitle_display_format,
                getLanguageLabelForCode(language),
                request.addonName,
            ),
            addonName = request.addonName,
        )
    }
}

/** Keep distinct addon tracks identifiable without rendering control/bidi spoofing characters. */
internal fun addonSubtitleDisplayLabel(label: String?, name: String?, title: String?): String? =
    listOf(label, name, title).firstNotNullOfOrNull { candidate ->
        candidate?.filterNot { char ->
            char.code in 0..31 || char.code in 127..159 ||
                char.code in 0x202A..0x202E || char.code in 0x2066..0x2069
        }?.trim()?.takeIf { it.isNotBlank() }?.take(240)
    }

private fun canonicalSubtitleType(type: String): String =
    type.trim().lowercase().let { if (it == "tv") "series" else it }

private fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
