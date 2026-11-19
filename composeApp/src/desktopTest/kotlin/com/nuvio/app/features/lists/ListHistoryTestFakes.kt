package com.nuvio.app.features.lists

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview

/** Records which title-page actions a history write calls, without any network or repositories. */
internal class FakeHistoryPort(
    var meta: MetaDetails? = null,
    val watched: MutableSet<String> = mutableSetOf(),
    var fullyWatched: Boolean = false,
    /** Runs while episodes load, e.g. to simulate a background sync finishing in that window. */
    var onFetch: () -> Unit = {},
) : ListHistoryPort {
    val calls = mutableListOf<String>()

    override suspend fun fetchMeta(type: String, id: String): MetaDetails? {
        onFetch()
        return meta
    }

    override fun isWatched(id: String, type: String, season: Int?, episode: Int?): Boolean =
        watched.contains(if (season == null && episode == null) "title" else "$season:$episode")

    override fun isFullyWatchedSeries(id: String, type: String): Boolean = fullyWatched

    override suspend fun togglePosterWatched(preview: MetaPreview) {
        calls += "poster:${preview.id}"
    }

    override fun markMovieWatched(preview: MetaPreview) {
        calls += "mark:movie:${preview.id}"
    }

    override fun markSeriesWatched(meta: MetaDetails) {
        calls += "mark:series:${meta.id}"
    }

    override fun toggleEpisodeWatched(meta: MetaDetails, episode: MetaVideo, isCurrentlyWatched: Boolean) {
        calls += "episode:${episode.season}:${episode.episode}:$isCurrentlyWatched"
    }

    override fun todayIsoDate(): String = "2026-01-01"
}

internal fun seriesMeta(episodes: Int, future: Int = 0) = MetaDetails(
    id = "tt1",
    type = "series",
    name = "Show",
    videos = (1..episodes).map {
        MetaVideo(id = "tt1:1:$it", title = "E$it", released = "2020-01-01", season = 1, episode = it)
    } + (1..future).map {
        MetaVideo(id = "tt1:2:$it", title = "F$it", released = "2030-01-01", season = 2, episode = it)
    },
)
