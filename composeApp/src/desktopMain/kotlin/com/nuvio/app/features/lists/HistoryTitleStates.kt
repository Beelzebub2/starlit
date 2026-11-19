package com.nuvio.app.features.lists

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.extractTrackingYear
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import com.nuvio.app.features.trakt.TraktDroppedShow
import com.nuvio.app.features.trakt.TraktRatingsRepository
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry

/** Everything a history-derived list (Trakt, MDBList) is built from. */
internal data class HistoryListSnapshot(
    /** Trakt watchlist (Plan to Watch). */
    val watchlist: List<LibraryItem> = emptyList(),
    /** Trakt watched history: movies (no season) and single episodes. */
    val watched: List<WatchedItem> = emptyList(),
    /** Trakt progress rows; only used for display details (title, poster, recency) of titles already listed. */
    val progress: List<WatchProgressEntry> = emptyList(),
    /** Trakt dropped list. */
    val dropped: List<TraktDroppedShow> = emptyList(),
    /** See [TraktRatingsRepository.ratings]. */
    val ratings: Map<String, Int> = emptyMap(),
    /** Trakt's aired episode count per watched show, keyed by content id (`tt1`, `tmdb:11`, `trakt:1`). */
    val airedEpisodes: Map<String, Int> = emptyMap(),
)

/** One title as a tracker sees it: list memberships plus history counts. The status is always derived, never stored. */
internal data class HistoryTitleState(
    val type: ListMediaType,
    /** Starlit content id, the same form the Trakt library/watched projections use (IMDb, else `tmdb:`, else `trakt:`). */
    val contentId: String,
    /** Every content-id form known for the title, [contentId] included. */
    val aliases: Set<String>,
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val inWatchlist: Boolean,
    val dropped: Boolean,
    val watchedEpisodes: Int,
    /** Aired main-season episodes; 0 when unknown (a watched series then reads as Watching). */
    val airedEpisodes: Int,
    val score: Int,
    val updatedAtEpochMs: Long,
    /** True when [airedEpisodes] is Trakt's own count, which matches Trakt's episode numbering. */
    val airedFromTrakt: Boolean = false,
) {
    val isMovie: Boolean get() = type == ListMediaType.MOVIE
    val catalogType: String get() = if (isMovie) "movie" else "series"
    val key: String get() = "$catalogType:$contentId"
    val status: ListStatus?
        get() = derivedHistoryStatus(inWatchlist, dropped, watchedEpisodes, airedEpisodes, isMovie)
}

private class HistoryTitleDraft(val type: ListMediaType, val contentId: String) {
    val aliases = linkedSetOf(contentId)
    var title: String? = null
    var posterUrl: String? = null
    var year: Int? = null
    var inWatchlist = false
    var dropped = false
    var watchedMovie = false
    val episodes = mutableSetOf<Pair<Int, Int>>()
    var updatedAtEpochMs = 0L

    fun offer(title: String?, poster: String?, year: Int?, updatedAtEpochMs: Long) {
        if (this.title.isNullOrBlank() && !title.isNullOrBlank()) this.title = title
        if (posterUrl.isNullOrBlank() && !poster.isNullOrBlank()) posterUrl = poster
        if (this.year == null) this.year = year
        if (updatedAtEpochMs > this.updatedAtEpochMs) this.updatedAtEpochMs = updatedAtEpochMs
    }
}

internal fun listMediaType(type: String): ListMediaType =
    if (type.trim().lowercase() in setOf("movie", "film")) ListMediaType.MOVIE else ListMediaType.SERIES

internal fun contentAliases(imdb: String?, tmdb: Long?, trakt: Long?): List<String> = buildList {
    imdb?.trim()?.takeIf(String::isNotBlank)?.let(::add)
    tmdb?.let { add("tmdb:$it") }
    trakt?.let { add("trakt:$it") }
}

internal fun contentAliases(ids: TrackingExternalIds): List<String> = contentAliases(ids.imdb, ids.tmdb, ids.trakt)

internal fun idsOf(aliases: Collection<String>): TrackingExternalIds =
    aliases.fold(TrackingExternalIds()) { ids, alias -> ids.mergeMissing(parseTrackingExternalIds(alias)) }

/**
 * Merges watchlist, dropped list, watched history and progress into one state per title, de-duplicated across
 * every id form. Progress rows only enrich titles already present: a paused, never-finished title has no status.
 */
internal fun buildHistoryTitleStates(
    snapshot: HistoryListSnapshot,
    airedEpisodes: (catalogType: String, contentId: String, watchedEpisodes: Int) -> Int,
): List<HistoryTitleState> {
    val drafts = mutableListOf<HistoryTitleDraft>()
    val byAlias = mutableMapOf<String, HistoryTitleDraft>()
    fun find(type: ListMediaType, aliases: Collection<String>): HistoryTitleDraft? =
        aliases.firstNotNullOfOrNull { byAlias["${type.name}:$it"] }
    fun draftFor(type: ListMediaType, primary: String, aliases: Collection<String>): HistoryTitleDraft {
        val all = (listOf(primary) + aliases).map(String::trim).filter(String::isNotBlank).distinct()
        val draft = find(type, all) ?: HistoryTitleDraft(type, primary.trim()).also(drafts::add)
        all.forEach { alias ->
            draft.aliases += alias
            byAlias["${type.name}:$alias"] = draft
        }
        return draft
    }

    snapshot.watchlist.forEach { item ->
        if (item.id.isBlank()) return@forEach
        val draft = draftFor(
            listMediaType(item.type),
            item.id,
            contentAliases(item.imdbId, item.tmdbId?.toLong(), item.traktId?.toLong()),
        )
        draft.inWatchlist = true
        draft.offer(item.name, item.poster, extractTrackingYear(item.releaseInfo), item.savedAtEpochMs)
    }
    snapshot.dropped.forEach { show ->
        val aliases = contentAliases(show.ids)
        val primary = aliases.firstOrNull() ?: return@forEach
        val draft = draftFor(ListMediaType.SERIES, primary, aliases)
        draft.dropped = true
        draft.offer(show.title, null, null, 0L)
    }
    snapshot.watched.forEach { item ->
        if (item.id.isBlank()) return@forEach
        val year = extractTrackingYear(item.releaseInfo)
        if (listMediaType(item.type) == ListMediaType.MOVIE) {
            val draft = draftFor(ListMediaType.MOVIE, item.id, emptyList())
            draft.watchedMovie = true
            draft.offer(item.name, item.poster, year, item.markedAtEpochMs)
        } else {
            val season = item.season ?: return@forEach
            val episode = item.episode ?: return@forEach
            if (season <= 0 || episode <= 0) return@forEach
            val draft = draftFor(ListMediaType.SERIES, item.id, emptyList())
            draft.episodes += season to episode
            draft.offer(item.name, item.poster, year, item.markedAtEpochMs)
        }
    }
    snapshot.progress.forEach { entry ->
        val draft = find(listMediaType(entry.parentMetaType), listOf(entry.parentMetaId.trim())) ?: return@forEach
        draft.offer(entry.title, entry.poster, null, entry.lastUpdatedEpochMs)
    }

    return drafts.map { draft ->
        val isMovie = draft.type == ListMediaType.MOVIE
        val catalogType = if (isMovie) "movie" else "series"
        val watched = if (isMovie) (if (draft.watchedMovie) 1 else 0) else draft.episodes.size
        val ratingKind = if (isMovie) "movie" else "show"
        val traktAired = if (isMovie) null else draft.aliases.firstNotNullOfOrNull { snapshot.airedEpisodes[it] }
        HistoryTitleState(
            type = draft.type,
            contentId = draft.contentId,
            aliases = draft.aliases.toSet(),
            title = draft.title?.takeIf(String::isNotBlank) ?: draft.contentId,
            posterUrl = draft.posterUrl,
            year = draft.year,
            inWatchlist = draft.inWatchlist,
            dropped = draft.dropped,
            watchedEpisodes = watched,
            airedEpisodes = when {
                isMovie -> 1
                traktAired != null -> traktAired
                else -> airedEpisodes(catalogType, draft.contentId, watched)
            },
            score = draft.aliases.firstNotNullOfOrNull { snapshot.ratings["$ratingKind:$it"] } ?: 0,
            updatedAtEpochMs = draft.updatedAtEpochMs,
            airedFromTrakt = traktAired != null,
        )
    }
}
