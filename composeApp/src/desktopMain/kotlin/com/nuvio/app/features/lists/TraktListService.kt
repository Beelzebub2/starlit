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
import com.nuvio.app.features.trakt.TraktLibraryRepository
import com.nuvio.app.features.trakt.TraktProgressRepository
import com.nuvio.app.features.trakt.TraktRatingsRepository
import com.nuvio.app.features.trakt.TraktWatchedShowSnapshotRepository
import com.nuvio.app.features.trakt.defaultTraktListClient
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


/** Seam over Trakt's registry ports and repositories so the adapter can be tested without network or storage. */
internal interface TraktListPort {
    fun activeProfileId(): Int
    suspend fun load(): HistoryListSnapshot
    suspend fun setWatchlisted(item: LibraryItem, inWatchlist: Boolean)
    suspend fun setDropped(media: TrackingMediaReference, dropped: Boolean)
    suspend fun setRating(media: TrackingMediaReference, rating: Int)

    /** Clears the title's whole Trakt history (`/sync/history/remove`); only for the confirmed removal action. */
    suspend fun removeHistory(media: TrackingMediaReference)

    /** Metadata already in memory (never a network call); used to count aired episodes while building pages. */
    fun cachedMeta(type: String, id: String): MetaDetails?
    suspend fun fetchMeta(type: String, id: String): MetaDetails?

    /** Starlit's own "fully watched" flag; the fallback when no metadata is cached. */
    fun isFullyWatchedLocally(type: String, id: String): Boolean
    fun todayIsoDate(): String
    fun nowEpochMs(): Long
}

/** Trakt can only look titles up by its own, IMDb, TMDB or TVDB ids (not MAL, Kitsu, AniList, AniDB, Simkl, MDBList). */
internal fun TrackingExternalIds.isTraktAddressable(): Boolean =
    trakt != null || !imdb.isNullOrBlank() || tmdb != null || !tvdb.isNullOrBlank()



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

    private data class Cache(val profileId: Int, val loadedAtEpochMs: Long, val states: List<HistoryTitleState>)

    private val cacheMutex = Mutex()
    private var cache: Cache? = null

    override fun covers(kind: ListTitleKind, meta: MetaDetails): Boolean =
        buildTrackingMediaReference(meta.type, meta.id).ids.isTraktAddressable()

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
                "episodes watched" to all.filterNot { it.isMovie }.sumOf { it.watchedEpisodes }.toString(),
                "movies watched" to all.count { it.isMovie && it.watchedEpisodes > 0 }.toString(),
                "rated titles" to all.count { it.score > 0 }.toString(),
            ),
        )
    }

    override suspend fun page(status: ListStatus?, sort: ListSort, offset: Int): ListPage {
        val matching = visibleStates().filter { status == null || it.status == status }
        val title = { state: HistoryTitleState -> state.title.lowercase() }
        val sorted = when (sort.key) {
            SORT_TITLE -> matching.sortedBy(title)
            SORT_SCORE -> matching.sortedWith(compareByDescending<HistoryTitleState> { it.score }.thenBy(title))
            else -> matching.sortedWith(compareByDescending<HistoryTitleState> { it.updatedAtEpochMs }.thenBy(title))
        }
        val start = offset.coerceAtLeast(0)
        val slice = sorted.drop(start).take(TRAKT_PAGE_SIZE).map { it.toEntry() }
        val next = (start + TRAKT_PAGE_SIZE).takeIf { it < sorted.size }
        return ListPage(slice, next)
    }

    override suspend fun entryFor(meta: MetaDetails): ListEntry? {
        val ids = buildTrackingMediaReference(meta.type, meta.id).ids
        if (!ids.isTraktAddressable()) return null
        val type = listMediaType(meta.type)
        val lookup = (contentAliases(ids) + meta.id.trim()).toSet()
        val state = states().firstOrNull { it.type == type && it.aliases.any(lookup::contains) } ?: return null
        // Trakt's own aired count keeps the title page and the Lists tab on the same status; meta is the fallback.
        val aired = when {
            state.isMovie -> 1
            state.airedFromTrakt -> state.airedEpisodes
            else -> meta.releasedMainSeasonEpisodes(port.todayIsoDate()).size
        }
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
                ListChangeImpact(history.completedImpact(type, catalogId), hidesFromContinueWatching = false)
            }
            // Trakt's dropped list also hides the show from Continue Watching; only this change warns about it.
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

    /**
     * The only Trakt change that deletes history, reached only from the confirmed "Remove from watch history" action.
     * Starlit's own history goes first through its watched actions (so badges and Continue Watching update), then
     * `/sync/history/remove` clears whatever exists only on Trakt. Returns the larger of the two counts.
     */
    override suspend fun removeFromHistory(entry: ListEntry): Int {
        val (type, catalogId) = catalogOf(entry)
        val state = stateFor(entry)
        val removedLocally = history.removeAll(type, catalogId)
        port.removeHistory(state.reference())
        val onTrakt = if (state.isMovie) (if (entry.watchedEpisodes > 0) 1 else 0) else entry.watchedEpisodes
        remember(state.copy(watchedEpisodes = 0, updatedAtEpochMs = port.nowEpochMs()))
        return maxOf(removedLocally, onTrakt)
    }

    private suspend fun setStatus(entry: ListEntry, state: HistoryTitleState, status: ListStatus): HistoryTitleState {
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

    private suspend fun leaveStatus(state: HistoryTitleState, status: ListStatus): HistoryTitleState {
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

    private suspend fun visibleStates(): List<HistoryTitleState> = states().filter { it.status != null }

    private suspend fun states(): List<HistoryTitleState> = cacheMutex.withLock {
        val profileId = port.activeProfileId()
        val now = port.nowEpochMs()
        cache
            ?.takeIf { it.profileId == profileId && now - it.loadedAtEpochMs in 0 until cacheTtlMs }
            ?.let { return@withLock it.states }
        val states = buildHistoryTitleStates(port.load(), ::airedEpisodesFor)
        cache = Cache(profileId, now, states)
        states
    }

    /** Keeps a write visible in pages until the next reload (Trakt and history mirroring can lag behind). */
    private suspend fun remember(state: HistoryTitleState) = cacheMutex.withLock {
        val current = cache ?: return@withLock
        if (current.profileId != port.activeProfileId()) return@withLock
        cache = current.copy(states = current.states.filterNot { it.key == state.key } + state)
    }

    private suspend fun stateFor(entry: ListEntry): HistoryTitleState =
        states().firstOrNull { it.key == entry.key.id } ?: entry.toFallbackState()

    private fun airedEpisodesFor(catalogType: String, contentId: String, watchedEpisodes: Int): Int {
        val released = port.cachedMeta(catalogType, contentId)
            ?.releasedMainSeasonEpisodes(port.todayIsoDate())
            ?.size
        if (released != null && released > 0) return released
        return if (watchedEpisodes > 0 && port.isFullyWatchedLocally(catalogType, contentId)) watchedEpisodes else 0
    }

    private fun ListEntry.toFallbackState(): HistoryTitleState {
        val contentId = catalogId ?: key.id.substringAfter(':')
        return HistoryTitleState(
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

private fun HistoryTitleState.reference(): TrackingMediaReference = TrackingMediaReference(
    kind = if (isMovie) TrackingMediaKind.MOVIE else TrackingMediaKind.SHOW,
    title = title,
    year = year,
    ids = idsOf(aliases),
    catalog = TrackingCatalogReference(contentId = contentId, contentType = catalogType),
)

private fun HistoryTitleState.libraryItem(nowEpochMs: Long): LibraryItem {
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

private fun HistoryTitleState.siteUrl(): String? {
    val ids = idsOf(aliases)
    val idType = if (isMovie) "movie" else "show"
    return when {
        !ids.imdb.isNullOrBlank() -> "https://trakt.tv/search/imdb/${ids.imdb}"
        ids.tmdb != null -> "https://trakt.tv/search/tmdb/${ids.tmdb}?id_type=$idType"
        ids.trakt != null -> "https://trakt.tv/search/trakt/${ids.trakt}?id_type=$idType"
        else -> null
    }
}

private fun HistoryTitleState.toEntry(
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

    override suspend fun load(): HistoryListSnapshot = coroutineScope {
        val profileId = ProfileRepository.activeProfileId
        val watchlist = async { loadWatchlist() }
        val watched = async {
            TrackingProviderRegistry.watchedProvider(TrackingProviderId.TRAKT)
                ?.pull(profileId, TRAKT_WATCHED_PULL_PAGE_SIZE)
                .orEmpty()
        }
        val dropped = async { TraktProgressRepository.droppedShows(profileId) }
        val ratings = async { TraktRatingsRepository.ratings(profileId) }
        val aired = async { loadAiredEpisodes(profileId) }
        val progress = TrackingProviderRegistry.progressProvider(TrackingProviderId.TRAKT)?.snapshot()?.entries.orEmpty()
        HistoryListSnapshot(
            watchlist = watchlist.await(),
            watched = watched.await(),
            progress = progress,
            dropped = dropped.await(),
            ratings = ratings.await(),
            airedEpisodes = aired.await(),
        )
    }

    /** One `sync/watched/shows?extended=full` read (cached briefly by the snapshot repository) for aired counts. */
    private suspend fun loadAiredEpisodes(profileId: Int): Map<String, Int> {
        val headers = TraktAuthRepository.authorizedHeaders(profileId) ?: return emptyMap()
        return TraktWatchedShowSnapshotRepository.airedEpisodeCounts(headers)
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

    /** Writes only the watchlist key; a full desired-membership map could remove the title from personal lists. */
    override suspend fun setWatchlisted(item: LibraryItem, inWatchlist: Boolean) =
        TraktLibraryRepository.setWatchlistMembership(item, inWatchlist)

    override suspend fun setDropped(media: TrackingMediaReference, dropped: Boolean) =
        TraktProgressRepository.setDropped(ProfileRepository.activeProfileId, media, dropped)

    override suspend fun setRating(media: TrackingMediaReference, rating: Int) =
        TraktRatingsRepository.setRating(ProfileRepository.activeProfileId, media, rating)

    override suspend fun removeHistory(media: TrackingMediaReference) {
        defaultTraktListClient.removeHistory(ProfileRepository.activeProfileId, media)
        // Same as the watched sync after its own history writes: the next read must not serve the old shows snapshot.
        TraktWatchedShowSnapshotRepository.clear()
    }

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
