package com.nuvio.app.features.lists

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.releasedMainSeasonEpisodes
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.domain.isSeriesLikeWatchingContentType

/**
 * Seam over the title page's own watched actions so history-derived list services
 * (Simkl and Trakt now, MDBList later) can be tested without repositories or network.
 */
interface ListHistoryPort {
    suspend fun fetchMeta(type: String, id: String): MetaDetails?
    fun isWatched(id: String, type: String, season: Int? = null, episode: Int? = null): Boolean
    fun isFullyWatchedSeries(id: String, type: String): Boolean
    suspend fun togglePosterWatched(preview: MetaPreview)
    fun toggleEpisodeWatched(meta: MetaDetails, episode: MetaVideo, isCurrentlyWatched: Boolean)
    fun todayIsoDate(): String
}

object DefaultListHistoryPort : ListHistoryPort {
    override suspend fun fetchMeta(type: String, id: String): MetaDetails? =
        MetaDetailsRepository.fetch(type = type, id = id)

    override fun isWatched(id: String, type: String, season: Int?, episode: Int?): Boolean =
        WatchedRepository.isWatched(id = id, type = type, season = season, episode = episode)

    override fun isFullyWatchedSeries(id: String, type: String): Boolean =
        WatchedRepository.isFullyWatchedSeries(id = id, type = type)

    override suspend fun togglePosterWatched(preview: MetaPreview) = WatchingActions.togglePosterWatched(preview)

    override fun toggleEpisodeWatched(meta: MetaDetails, episode: MetaVideo, isCurrentlyWatched: Boolean) =
        WatchingActions.toggleEpisodeWatched(meta, episode, isCurrentlyWatched)

    override fun todayIsoDate(): String = CurrentDateProvider.todayIsoDate()
}

/**
 * History writes shared by services that derive their lists from watched history.
 * Every write goes through the same actions the title page uses, so the tracker
 * mirroring and local state stay identical to tapping the title page controls.
 */
class ListHistoryWrites(private val port: ListHistoryPort = DefaultListHistoryPort) {

    private fun isMovie(type: String): Boolean = !type.isSeriesLikeWatchingContentType()

    private fun titleWatched(type: String, id: String): Boolean =
        port.isWatched(id = id, type = type) || port.isFullyWatchedSeries(id = id, type = type)

    /** Number of history entries "Completed" would add: unwatched released main-season episodes, or 1 for an unwatched movie. */
    suspend fun completedImpact(type: String, id: String): Int {
        if (isMovie(type)) return if (port.isWatched(id = id, type = type)) 0 else 1
        val meta = port.fetchMeta(type, id)
            ?: throw IllegalStateException("Could not load episodes to count what Completed would mark as watched")
        return unwatchedReleased(meta).size
    }

    /** Marks the title watched exactly like the poster action, but never toggles an already-watched title off. */
    suspend fun markCompleted(type: String, id: String, name: String, poster: String?) {
        if (titleWatched(type, id)) return
        if (!isMovie(type)) {
            // Verify episodes load before anything is written, so a failure cannot leave the tracker and history out of sync.
            port.fetchMeta(type, id)
                ?: throw IllegalStateException("Could not load episodes to mark this title as watched")
        }
        port.togglePosterWatched(MetaPreview(id = id, type = type, name = name, poster = poster))
    }

    /** +n marks the next n released unwatched episodes; -n unmarks the last n watched ones. */
    suspend fun stepEpisodes(type: String, id: String, delta: Int) {
        if (delta == 0) return
        require(!isMovie(type)) { "Episode progress does not apply to movies" }
        val meta = port.fetchMeta(type, id) ?: throw IllegalStateException("Could not load episodes for this title")
        val released = releasedMain(meta)
        val count = kotlin.math.abs(delta)
        if (delta > 0) {
            val next = released.filterNot { isEpisodeWatched(meta, it) }.take(count)
            if (next.size < count) throw IllegalStateException("There is no next released episode to mark as watched")
            next.forEach { port.toggleEpisodeWatched(meta, it, isCurrentlyWatched = false) }
        } else {
            val previous = released.filter { isEpisodeWatched(meta, it) }.takeLast(count).reversed()
            if (previous.size < count) throw IllegalStateException("There is no watched episode to unmark")
            previous.forEach { port.toggleEpisodeWatched(meta, it, isCurrentlyWatched = true) }
        }
    }

    /**
     * Removes the title's watch history through the title page's actions and returns how many entries were removed.
     * A movie is toggled off only when watched; a series has each watched released main-season episode toggled off
     * one by one. The poster toggle is never used for a series: on a partly watched series it would mark everything.
     */
    suspend fun removeAll(catalogType: String, catalogId: String): Int {
        if (isMovie(catalogType)) {
            if (!port.isWatched(id = catalogId, type = catalogType)) return 0
            port.togglePosterWatched(MetaPreview(id = catalogId, type = catalogType, name = catalogId))
            return 1
        }
        val meta = port.fetchMeta(catalogType, catalogId)
            ?: throw IllegalStateException("Could not load episodes to remove them from watch history")
        val watched = releasedMain(meta).filter { isEpisodeWatched(meta, it) }
        watched.forEach { port.toggleEpisodeWatched(meta, it, isCurrentlyWatched = true) }
        return watched.size
    }

    private fun releasedMain(meta: MetaDetails): List<MetaVideo> =
        meta.releasedMainSeasonEpisodes(port.todayIsoDate())

    private fun isEpisodeWatched(meta: MetaDetails, episode: MetaVideo): Boolean =
        port.isWatched(id = meta.id, type = meta.type, season = episode.season, episode = episode.episode)

    private fun unwatchedReleased(meta: MetaDetails): List<MetaVideo> =
        releasedMain(meta).filterNot { isEpisodeWatched(meta, it) }
}
