package com.nuvio.app.features.lists

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.myanimelist.isLikelyMyAnimeListAnime

fun titleKind(meta: MetaDetails, malResolved: Boolean): ListTitleKind =
    ListTitleKind(
        type = if (meta.type.equals("movie", ignoreCase = true)) ListMediaType.MOVIE else ListMediaType.SERIES,
        anime = malResolved || isLikelyMyAnimeListAnime(meta),
    )

fun derivedHistoryStatus(
    inWatchlist: Boolean,
    dropped: Boolean,
    watchedEpisodes: Int,
    airedEpisodes: Int,
    isMovie: Boolean,
): ListStatus? = when {
    dropped -> ListStatus.DROPPED
    isMovie && watchedEpisodes > 0 -> ListStatus.COMPLETED
    !isMovie && watchedEpisodes > 0 && airedEpisodes > 0 && watchedEpisodes >= airedEpisodes -> ListStatus.COMPLETED
    watchedEpisodes > 0 -> ListStatus.WATCHING
    inWatchlist -> ListStatus.PLAN_TO_WATCH
    else -> null
}
