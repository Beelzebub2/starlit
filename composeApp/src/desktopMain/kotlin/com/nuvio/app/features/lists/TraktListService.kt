package com.nuvio.app.features.lists

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.myanimelist.isLikelyMyAnimeListAnime
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingCatalogReference
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.tracking.extractTrackingYear
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktDroppedShow
import com.nuvio.app.features.trakt.TraktProgressRepository
import com.nuvio.app.features.trakt.TraktRatingsRepository
import com.nuvio.app.features.trakt.setDropped
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.releasedMainSeasonEpisodes
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TRAKT_PAGE_SIZE = 50
private const val TRAKT_CACHE_TTL_MS = 60_000L
private const val TRAKT_WATCHED_PULL_PAGE_SIZE = 1_000
private const val SORT_UPDATED = "UPDATED"
private const val SORT_TITLE = "TITLE"
private const val SORT_SCORE = "SCORE"

/** Everything the Trakt list is derived from; one load feeds every page, the summary and title lookups. */
internal data class TraktListSnapshot(
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
)

/** Seam over Trakt's registry ports and repositories so the adapter can be tested without network or storage. */
internal interface TraktListPort {
    fun activeProfileId(): Int
    suspend fun load(): TraktListSnapshot
    suspend fun setWatchlisted(item: LibraryItem, inWatchlist: Boolean)
    suspend fun setDropped(media: TrackingMediaReference, dropped: Boolean)
    suspend fun setRating(media: TrackingMediaReference, rating: Int)

    /** Metadata already in memory (never a network call); used to count aired episodes while building pages. */
    fun cachedMeta(type: String, id: String): MetaDetails?
    suspend fun fetchMeta(type: String, id: String): MetaDetails?

    /** Starlit's own "fully watched" flag; the fallback when no metadata is cached. */
    fun isFullyWatchedLocally(type: String, id: String): Boolean
    fun todayIsoDate(): String
    fun nowEpochMs(): Long
}

/** One title as Trakt sees it: list memberships plus history counts. The status is always derived, never stored. */
internal data class TraktTitleState(
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
    /** Released main-season episodes; 0 when unknown (a watched series then reads as Watching). */
    val airedEpisodes: Int,
    val score: Int,
    val updatedAtEpochMs: Long,
) {
    val isMovie: Boolean get() = type == ListMediaType.MOVIE
    val catalogType: String get() = if (isMovie) "movie" else "series"
    val key: String get() = "$catalogType:$contentId"
    val status: ListStatus?
        get() = derivedHistoryStatus(inWatchlist, dropped, watchedEpisodes, airedEpisodes, isMovie)
}

private class TraktTitleDraft(val type: ListMediaType, val contentId: String) {
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

private fun listMediaType(type: String): ListMediaType =
    if (type.trim().lowercase() in setOf("movie", "film")) ListMediaType.MOVIE else ListMediaType.SERIES

private fun contentAliases(imdb: String?, tmdb: Long?, trakt: Long?): List<String> = buildList {
    imdb?.trim()?.takeIf(String::isNotBlank)?.let(::add)
    tmdb?.let { add("tmdb:$it") }
    trakt?.let { add("trakt:$it") }
}

private fun contentAliases(ids: TrackingExternalIds): List<String> = contentAliases(ids.imdb, ids.tmdb, ids.trakt)

private fun idsOf(aliases: Collection<String>): TrackingExternalIds =
    aliases.fold(TrackingExternalIds()) { ids, alias -> ids.mergeMissing(parseTrackingExternalIds(alias)) }

/**
 * Merges watchlist, dropped list, watched history and progress into one state per title, de-duplicated across
 * every id form. Progress rows only enrich titles already present: a paused, never-finished title has no status.
 */
internal fun buildTraktTitleStates(
    snapshot: TraktListSnapshot,
    airedEpisodes: (catalogType: String, contentId: String, watchedEpisodes: Int) -> Int,
): List<TraktTitleState> {
    val drafts = mutableListOf<TraktTitleDraft>()
    val byAlias = mutableMapOf<String, TraktTitleDraft>()
    fun find(type: ListMediaType, aliases: Collection<String>): TraktTitleDraft? =
        aliases.firstNotNullOfOrNull { byAlias["${type.name}:$it"] }
    fun draftFor(type: ListMediaType, primary: String, aliases: Collection<String>): TraktTitleDraft {
        val all = (listOf(primary) + aliases).map(String::trim).filter(String::isNotBlank).distinct()
        val draft = find(type, all) ?: TraktTitleDraft(type, primary.trim()).also(drafts::add)
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
        TraktTitleState(
            type = draft.type,
            contentId = draft.contentId,
            aliases = draft.aliases.toSet(),
            title = draft.title?.takeIf(String::isNotBlank) ?: draft.contentId,
            posterUrl = draft.posterUrl,
            year = draft.year,
            inWatchlist = draft.inWatchlist,
            dropped = draft.dropped,
            watchedEpisodes = watched,
            airedEpisodes = if (isMovie) 1 else airedEpisodes(catalogType, draft.contentId, watched),
            score = draft.aliases.firstNotNullOfOrNull { snapshot.ratings["$ratingKind:$it"] } ?: 0,
            updatedAtEpochMs = draft.updatedAtEpochMs,
        )
    }
}

/**
 * Trakt list service. Statuses are derived from the watchlist, the dropped list and watch history (Dropped >
 * Completed > Watching > Plan to Watch); picking a status only ever adds (watchlist, dropped list, or history
 * through the title page's own actions) and never removes the title from another list.
 */
internal class TraktListAdapter(
    private val port: TraktListPort,
    override val connected: StateFlow<Boolean>,
    private val history: ListHistoryWrites,
    private val signedInAs: () -> String?,
    private val cacheTtlMs: Long = TRAKT_CACHE_TTL_MS,
) : ListService {
    override val id = ListServiceId.TRAKT

    override val sorts: List<ListSort> = listOf(
        ListSort(SORT_UPDATED, "Last updated"),
        ListSort(SORT_TITLE, "Title"),
        ListSort(SORT_SCORE, "Your score"),
    )

    private data class Cache(val profileId: Int, val loadedAtEpochMs: Long, val states: List<TraktTitleState>)

    private val cacheMutex = Mutex()
    private var cache: Cache? = null

    override fun covers(kind: ListTitleKind, meta: MetaDetails): Boolean =
        buildTrackingMediaReference(meta.type, meta.id).ids.hasAny

    override fun capabilities(type: ListMediaType): ListCapabilities = when (type) {
        ListMediaType.SERIES -> ListCapabilities(
            statuses = listOf(ListStatus.WATCHING, ListStatus.COMPLETED, ListStatus.DROPPED, ListStatus.PLAN_TO_WATCH),
            pickableStatuses = setOf(ListStatus.PLAN_TO_WATCH, ListStatus.DROPPED, ListStatus.COMPLETED),
            score = true,
            episodeProgress = true,
            rewatch = false,
            changeEntry = false,
            removeFromHistory = true,
            leavableStatuses = setOf(ListStatus.PLAN_TO_WATCH, ListStatus.DROPPED),
        )
        ListMediaType.MOVIE -> ListCapabilities(
            statuses = listOf(ListStatus.COMPLETED, ListStatus.PLAN_TO_WATCH),
            pickableStatuses = setOf(ListStatus.PLAN_TO_WATCH, ListStatus.COMPLETED),
            score = true,
            episodeProgress = false,
            rewatch = false,
            changeEntry = false,
            removeFromHistory = true,
            leavableStatuses = setOf(ListStatus.PLAN_TO_WATCH),
        )
    }

    override fun profileUrl(): String? = "https://trakt.tv/dashboard"

    override suspend fun summary(): ListSummary {
        val all = visibleStates()
        val counts = buildMap<ListStatus?, Int> {
            ListStatus.entries.forEach { listStatus -> put(listStatus, all.count { it.status == listStatus }) }
            put(null, all.size)
        }
        return ListSummary(
            signedInAs = signedInAs(),
            counts = counts,
            extra = listOf(
                "Episodes watched" to all.filterNot { it.isMovie }.sumOf { it.watchedEpisodes }.toString(),
                "Movies watched" to all.count { it.isMovie && it.watchedEpisodes > 0 }.toString(),
                "Rated titles" to all.count { it.score > 0 }.toString(),
            ),
        )
    }

    override suspend fun page(status: ListStatus?, sort: ListSort, offset: Int): ListPage {
        val matching = visibleStates().filter { status == null || it.status == status }
        val title = { state: TraktTitleState -> state.title.lowercase() }
        val sorted = when (sort.key) {
            SORT_TITLE -> matching.sortedBy(title)
            SORT_SCORE -> matching.sortedWith(compareByDescending<TraktTitleState> { it.score }.thenBy(title))
            else -> matching.sortedWith(compareByDescending<TraktTitleState> { it.updatedAtEpochMs }.thenBy(title))
        }
        val start = offset.coerceAtLeast(0)
        val slice = sorted.drop(start).take(TRAKT_PAGE_SIZE).map { it.toEntry() }
        val next = (start + TRAKT_PAGE_SIZE).takeIf { it < sorted.size }
        return ListPage(slice, next)
    }

    override suspend fun entryFor(meta: MetaDetails): ListEntry? {
        val ids = buildTrackingMediaReference(meta.type, meta.id).ids
        if (!ids.hasAny) return null
        val type = listMediaType(meta.type)
        val lookup = (contentAliases(ids) + meta.id.trim()).toSet()
        val state = states().firstOrNull { it.type == type && it.aliases.any(lookup::contains) } ?: return null
        val aired = if (state.isMovie) 1 else meta.releasedMainSeasonEpisodes(port.todayIsoDate()).size
        val refined = state.copy(
            airedEpisodes = if (aired > 0) aired else state.airedEpisodes,
            posterUrl = state.posterUrl ?: meta.poster,
        )
        if (refined != state) remember(refined)
        if (refined.status == null) return null
        return refined.toEntry(catalogType = meta.type, catalogId = meta.id, anime = isLikelyMyAnimeListAnime(meta))
    }

    override suspend fun impact(entry: ListEntry, change: ListChange): ListChangeImpact = when (change) {
        is ListChange.SetStatus -> when (change.status) {
            ListStatus.COMPLETED -> {
                val (type, catalogId) = catalogOf(entry)
                ListChangeImpact(history.completedImpact(type, catalogId), hidesFromContinueWatching = true)
            }
            ListStatus.DROPPED -> ListChangeImpact(0, hidesFromContinueWatching = true)
            else -> ListChangeImpact(0, hidesFromContinueWatching = false)
        }
        is ListChange.StepEpisodes -> ListChangeImpact(change.delta.coerceAtLeast(0), hidesFromContinueWatching = false)
        else -> ListChangeImpact(0, hidesFromContinueWatching = false)
    }

    override suspend fun apply(entry: ListEntry, change: ListChange): ListEntry {
        val state = stateFor(entry)
        val updated = when (change) {
            is ListChange.SetStatus -> setStatus(entry, state, change.status)
            is ListChange.LeaveStatus -> leaveStatus(state, change.status)
            is ListChange.SetScore -> {
                require(change.score in 0..10) { "Trakt scores range from 0 to 10" }
                port.setRating(state.reference(), change.score)
                state.copy(score = change.score)
            }
            is ListChange.StepEpisodes -> {
                require(!state.isMovie) { "Episode progress does not apply to movies" }
                val (type, catalogId) = catalogOf(entry)
                history.stepEpisodes(type, catalogId, change.delta)
                val max = if (state.airedEpisodes > 0) state.airedEpisodes else Int.MAX_VALUE
                state.copy(watchedEpisodes = (state.watchedEpisodes + change.delta).coerceIn(0, max))
            }
            is ListChange.SetRewatching, ListChange.FinishRewatch ->
                throw IllegalArgumentException("Trakt has no rewatch tracking")
        }.copy(updatedAtEpochMs = port.nowEpochMs())
        remember(updated)
        return updated.toEntry(
            catalogType = entry.catalogType ?: updated.catalogType,
            catalogId = entry.catalogId ?: updated.contentId,
            anime = entry.anime,
        )
    }

    /** Removes the title's watch history through Starlit's watched actions (the only Trakt change that deletes history). */
    override suspend fun removeFromHistory(entry: ListEntry): Int {
        val (type, catalogId) = catalogOf(entry)
        val state = stateFor(entry)
        val removed = history.removeAll(type, catalogId)
        // Only what Starlit's own history held is removed; anything Trakt has beyond that stays until a reload shows it.
        val remaining = if (state.isMovie) {
            if (removed > 0) 0 else state.watchedEpisodes
        } else {
            (state.watchedEpisodes - removed).coerceAtLeast(0)
        }
        remember(state.copy(watchedEpisodes = remaining, updatedAtEpochMs = port.nowEpochMs()))
        return removed
    }

    private suspend fun setStatus(entry: ListEntry, state: TraktTitleState, status: ListStatus): TraktTitleState {
        require(status in capabilities(state.type).pickableStatuses) { "Trakt does not offer $status for this title" }
        return when (status) {
            ListStatus.PLAN_TO_WATCH -> {
                port.setWatchlisted(state.libraryItem(port.nowEpochMs()), inWatchlist = true)
                state.copy(inWatchlist = true)
            }
            ListStatus.DROPPED -> {
                port.setDropped(state.reference(), dropped = true)
                state.copy(dropped = true)
            }
            ListStatus.COMPLETED -> {
                val (type, catalogId) = catalogOf(entry)
                history.markCompleted(type, catalogId, entry.title, entry.posterUrl)
                if (state.isMovie) {
                    state.copy(watchedEpisodes = 1)
                } else {
                    val aired = state.airedEpisodes.takeIf { it > 0 }
                        ?: port.fetchMeta(type, catalogId)?.releasedMainSeasonEpisodes(port.todayIsoDate())?.size
                        ?: 0
                    state.copy(watchedEpisodes = maxOf(state.watchedEpisodes, aired), airedEpisodes = aired)
                }
            }
            ListStatus.WATCHING, ListStatus.ON_HOLD -> throw IllegalArgumentException("Trakt does not offer $status")
        }
    }

    private suspend fun leaveStatus(state: TraktTitleState, status: ListStatus): TraktTitleState {
        require(status in capabilities(state.type).leavableStatuses) { "Trakt cannot leave $status for this title" }
        return when (status) {
            ListStatus.PLAN_TO_WATCH -> {
                port.setWatchlisted(state.libraryItem(port.nowEpochMs()), inWatchlist = false)
                state.copy(inWatchlist = false)
            }
            ListStatus.DROPPED -> {
                port.setDropped(state.reference(), dropped = false)
                state.copy(dropped = false)
            }
            else -> throw IllegalArgumentException("Trakt cannot leave $status")
        }
    }

    private fun catalogOf(entry: ListEntry): Pair<String, String> {
        val type = entry.catalogType
        val catalogId = entry.catalogId
        if (type == null || catalogId == null) throw IllegalStateException("This Trakt entry has no Starlit title to update")
        return type to catalogId
    }

    private suspend fun visibleStates(): List<TraktTitleState> = states().filter { it.status != null }

    private suspend fun states(): List<TraktTitleState> = cacheMutex.withLock {
        val profileId = port.activeProfileId()
        val now = port.nowEpochMs()
        cache
            ?.takeIf { it.profileId == profileId && now - it.loadedAtEpochMs in 0 until cacheTtlMs }
            ?.let { return@withLock it.states }
        val states = buildTraktTitleStates(port.load(), ::airedEpisodesFor)
        cache = Cache(profileId, now, states)
        states
    }

    /** Keeps a write visible in pages until the next reload (Trakt and history mirroring can lag behind). */
    private suspend fun remember(state: TraktTitleState) = cacheMutex.withLock {
        val current = cache ?: return@withLock
        if (current.profileId != port.activeProfileId()) return@withLock
        cache = current.copy(states = current.states.filterNot { it.key == state.key } + state)
    }

    private suspend fun stateFor(entry: ListEntry): TraktTitleState =
        states().firstOrNull { it.key == entry.key.id } ?: entry.toFallbackState()

    private fun airedEpisodesFor(catalogType: String, contentId: String, watchedEpisodes: Int): Int {
        val released = port.cachedMeta(catalogType, contentId)
            ?.releasedMainSeasonEpisodes(port.todayIsoDate())
            ?.size
        if (released != null && released > 0) return released
        return if (watchedEpisodes > 0 && port.isFullyWatchedLocally(catalogType, contentId)) watchedEpisodes else 0
    }

    private fun ListEntry.toFallbackState(): TraktTitleState {
        val contentId = catalogId ?: key.id.substringAfter(':')
        return TraktTitleState(
            type = type,
            contentId = contentId,
            aliases = (listOf(contentId) + contentAliases(parseTrackingExternalIds(contentId))).toSet(),
            title = title,
            posterUrl = posterUrl,
            year = null,
            inWatchlist = status == ListStatus.PLAN_TO_WATCH,
            dropped = status == ListStatus.DROPPED,
            watchedEpisodes = watchedEpisodes,
            airedEpisodes = totalEpisodes,
            score = score,
            updatedAtEpochMs = 0L,
        )
    }
}

private fun TraktTitleState.reference(): TrackingMediaReference = TrackingMediaReference(
    kind = if (isMovie) TrackingMediaKind.MOVIE else TrackingMediaKind.SHOW,
    title = title,
    year = year,
    ids = idsOf(aliases),
    catalog = TrackingCatalogReference(contentId = contentId, contentType = catalogType),
)

private fun TraktTitleState.libraryItem(nowEpochMs: Long): LibraryItem {
    val ids = idsOf(aliases)
    return LibraryItem(
        id = contentId,
        type = catalogType,
        name = title,
        poster = posterUrl,
        releaseInfo = year?.toString(),
        imdbId = ids.imdb,
        tmdbId = ids.tmdb?.toInt(),
        traktId = ids.trakt?.toInt(),
        savedAtEpochMs = nowEpochMs,
    )
}

private fun TraktTitleState.siteUrl(): String? {
    val ids = idsOf(aliases)
    val idType = if (isMovie) "movie" else "show"
    return when {
        !ids.imdb.isNullOrBlank() -> "https://trakt.tv/search/imdb/${ids.imdb}"
        ids.tmdb != null -> "https://trakt.tv/search/tmdb/${ids.tmdb}?id_type=$idType"
        ids.trakt != null -> "https://trakt.tv/search/trakt/${ids.trakt}?id_type=$idType"
        else -> null
    }
}

private fun TraktTitleState.toEntry(
    catalogType: String = this.catalogType,
    catalogId: String = contentId,
    anime: Boolean = false,
): ListEntry = ListEntry(
    key = ListEntryKey(ListServiceId.TRAKT, key),
    title = title,
    subtitle = null,
    posterUrl = posterUrl,
    type = type,
    anime = anime,
    facts = listOfNotNull(year?.toString()),
    communityScore = null,
    status = status,
    score = score,
    watchedEpisodes = watchedEpisodes,
    totalEpisodes = airedEpisodes,
    airing = false,
    rewatching = false,
    rewatchCount = 0,
    catalogType = catalogType,
    catalogId = catalogId,
    siteUrl = siteUrl(),
)

/** Production wiring: Trakt registry ports, the Trakt repositories and Starlit's metadata/watched state. */
private object DefaultTraktListPort : TraktListPort {
    override fun activeProfileId(): Int = ProfileRepository.activeProfileId

    override suspend fun load(): TraktListSnapshot = coroutineScope {
        val profileId = ProfileRepository.activeProfileId
        val watchlist = async { loadWatchlist() }
        val watched = async {
            TrackingProviderRegistry.watchedProvider(TrackingProviderId.TRAKT)
                ?.pull(profileId, TRAKT_WATCHED_PULL_PAGE_SIZE)
                .orEmpty()
        }
        val dropped = async { TraktProgressRepository.droppedShows(profileId) }
        val ratings = async { TraktRatingsRepository.ratings(profileId) }
        val progress = TrackingProviderRegistry.progressProvider(TrackingProviderId.TRAKT)?.snapshot()?.entries.orEmpty()
        TraktListSnapshot(
            watchlist = watchlist.await(),
            watched = watched.await(),
            progress = progress,
            dropped = dropped.await(),
            ratings = ratings.await(),
        )
    }

    private suspend fun loadWatchlist(): List<LibraryItem> {
        val library = TrackingProviderRegistry.libraryProvider(TrackingProviderId.TRAKT) ?: return emptyList()
        library.ensureLoaded()
        library.refresh(TrackingRefreshIntent.AUTOMATIC)
        val snapshot = library.snapshot()
        snapshot.errorMessage?.let { message -> throw IllegalStateException(message) }
        val watchlistKey = snapshot.tabs.firstOrNull { it.kind == TrackingLibraryTabKind.WATCHLIST }?.key
            ?: return emptyList()
        return snapshot.sections.firstOrNull { it.type == watchlistKey }?.items.orEmpty()
    }

    override suspend fun setWatchlisted(item: LibraryItem, inWatchlist: Boolean) {
        if (!TrackingProviderRegistry.isAuthenticated(TrackingProviderId.TRAKT)) {
            throw IllegalStateException("Trakt is not connected")
        }
        val library = TrackingProviderRegistry.libraryProvider(TrackingProviderId.TRAKT)
            ?: throw IllegalStateException("Trakt watchlist is not available")
        val membership = library.membership(item)
        val snapshot = library.snapshot()
        val watchlistKey = snapshot.tabs.firstOrNull { it.kind == TrackingLibraryTabKind.WATCHLIST }?.key
            ?: throw IllegalStateException(snapshot.errorMessage ?: "Trakt watchlist is not available")
        library.applyMembership(
            profileId = ProfileRepository.activeProfileId,
            item = item,
            desiredMembership = membership + (watchlistKey to inWatchlist),
        )
    }

    override suspend fun setDropped(media: TrackingMediaReference, dropped: Boolean) =
        TraktProgressRepository.setDropped(ProfileRepository.activeProfileId, media, dropped)

    override suspend fun setRating(media: TrackingMediaReference, rating: Int) =
        TraktRatingsRepository.setRating(ProfileRepository.activeProfileId, media, rating)

    override fun cachedMeta(type: String, id: String): MetaDetails? = MetaDetailsRepository.peek(type = type, id = id)

    override suspend fun fetchMeta(type: String, id: String): MetaDetails? = MetaDetailsRepository.fetch(type = type, id = id)

    override fun isFullyWatchedLocally(type: String, id: String): Boolean =
        WatchedRepository.isFullyWatchedSeries(id = id, type = type)

    override fun todayIsoDate(): String = CurrentDateProvider.todayIsoDate()

    override fun nowEpochMs(): Long = System.currentTimeMillis()
}

object TraktListService : ListService by TraktListAdapter(
    port = DefaultTraktListPort,
    connected = TraktAuthRepository.also { it.ensureLoaded() }.isAuthenticated,
    history = ListHistoryWrites(),
    signedInAs = { TraktAuthRepository.uiState.value.username },
)
