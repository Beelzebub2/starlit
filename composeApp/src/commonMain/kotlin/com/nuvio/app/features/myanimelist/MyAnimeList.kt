package com.nuvio.app.features.myanimelist

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.nuvio.app.features.details.MetaDetails

@Composable internal expect fun MyAnimeListControls(meta: MetaDetails)
@Composable internal expect fun MyAnimeListSettingsCard()
/** Settings card to order connected list services and choose where each shows (desktop only). */
@Composable internal expect fun ListServicesSettingsCard()
@Composable internal expect fun MyAnimeListTabVisible(): Boolean
@Composable internal expect fun MyAnimeListTabContent(topChromePadding: Dp? = null)
/** Navigation label of the lists tab (desktop: the single service's name or "Lists"; other platforms never show it). */
@Composable internal expect fun listsTabLabel(): String
internal expect fun myAnimeListPlaybackUpdateThresholdPercent(): Int
internal expect suspend fun markMyAnimeListRewatchOnPlayback(
    meta: MetaDetails,
    playbackProfileId: Int,
    positionMs: Long,
    durationMs: Long,
    seasonNumber: Int?,
    episodeNumber: Int?,
    completionReached: Boolean,
): Boolean
internal expect fun clearMyAnimeListState()

internal fun isLikelyMyAnimeListAnime(meta: MetaDetails): Boolean =
    meta.id.substringBefore(':') in setOf("nativeairing", "mal", "anilist", "kitsu") ||
        meta.links.any { it.url.contains("myanimelist.net/anime/", ignoreCase = true) } ||
        meta.genres.any { it.equals("anime", true) || it.equals("animation", true) }
