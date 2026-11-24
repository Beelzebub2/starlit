package com.nuvio.app.features.myanimelist

import com.nuvio.app.features.details.MetaDetails
import kotlinx.serialization.Serializable

/** A local checkpoint for a rewatch, tied to MAL's count before this pass. */
@Serializable
internal data class MalRewatchProgress(
    val baseline: Int,
    val completedEpisodes: Set<Int> = emptySet(),
) {
    fun record(episode: Int) = copy(completedEpisodes = completedEpisodes + episode)
    fun complete(totalEpisodes: Int) = totalEpisodes > 0 && completedEpisodes.size == totalEpisodes
}

/**
 * Automatic completion is limited to titles whose local episodes can unambiguously represent
 * one complete MAL entry. Multi-season and incomplete catalogs need an explicit finish action.
 */
internal fun matchedMalRewatchEpisode(
    meta: MetaDetails,
    malTotalEpisodes: Int,
    season: Int?,
    episode: Int?,
): Int? {
    if (malTotalEpisodes !in 1..MAX_MAL_EPISODES) return null
    if (meta.type == "movie") return 1.takeIf { malTotalEpisodes == 1 && season == null && episode == null }
    if (meta.type != "series" || season == null || season <= 0 || episode == null) return null
    val seasons = meta.videos.mapNotNull { it.season?.takeIf { seasonId -> seasonId > 0 } }.distinct()
    if (seasons.size != 1 || seasons.single() != season) return null
    val knownEpisodes = meta.videos.filter { it.season == season }
        .mapNotNull { it.episode?.takeIf { number -> number > 0 } }.toSet()
    if (knownEpisodes.size != malTotalEpisodes || (1..malTotalEpisodes).any { it !in knownEpisodes }) return null
    return episode.takeIf { it in knownEpisodes }
}
