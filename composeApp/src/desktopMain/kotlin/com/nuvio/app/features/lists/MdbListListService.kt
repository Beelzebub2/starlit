package com.nuvio.app.features.lists

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.mdblist.MDBLIST_WATCHLIST_KEY
import com.nuvio.app.features.mdblist.MdbListIds
import com.nuvio.app.features.mdblist.MdbListTracker
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
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import com.nuvio.app.features.trakt.TraktDroppedShow
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.releasedMainSeasonEpisodes
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val MDBLIST_PAGE_SIZE = 50
private const val MDBLIST_CACHE_TTL_MS = 60_000L
private const val MDBLIST_WATCHED_PULL_PAGE_SIZE = 1_000
private const val SORT_UPDATED = "UPDATED"
private const val SORT_TITLE = "TITLE"

/** Everything the MDBList list is derived from; one load feeds every page, the summary and title lookups. */
internal data class MdbListListSnapshot(
    /** MDBList watchlist (Plan to Watch). */
    val watchlist: List<LibraryItem> = emptyList(),
    /** MDBList watched history: movies (no season) and single episodes. */
    val watched: List<WatchedItem> = emptyList(),
    /** Shows MDBList reports as dropped as a whole (season-level drops are ignored). */
    val dropped: List<MdbListIds> = emptyList(),
)

/** Seam over MDBList's registry ports and Starlit's metadata/watched state so the adapter can be tested offline. */
internal interface MdbListListPort {
    fun activeProfileId(): Int
    suspend fun load(): MdbListListSnapshot

    /** Changes only the watchlist membership (a single add/remove call), never any other list. */
    suspend fun setWatchlisted(item: LibraryItem, inWatchlist: Boolean)
    suspend fun setDropped(media: TrackingMediaReference, dropped: Boolean)

    /** Clears the title's MDBList watched history (`/sync/watched/remove`); only for the confirmed removal action. */
    suspend fun removeHistory(media: TrackingMediaReference)
    fun cachedMeta(type: String, id: String): MetaDetails?
    suspend fun fetchMeta(type: String, id: String): MetaDetails?
    fun isFullyWatchedLocally(type: String, id: String): Boolean
    fun todayIsoDate(): String
    fun nowEpochMs(): Long
}

/**
 * MDBList list service. Statuses are derived from the watchlist, the dropped list and watch history (Dropped >
 * Completed > Watching > Plan to Watch). Watching is derived only: shown as a tab but never pickable or leavable.
 * Picking a status only ever adds (watchlist, dropped, or history through the title page's own
 * actions) and never deletes history. Title state building is shared with the Trakt adapter.
 */
internal class MdbListListAdapter(
    private val port: MdbListListPort,
    override val connected: StateFlow<Boolean>,
    private val history: ListHistoryWrites,
    private val signedInAs: () -> String?,
    private val cacheTtlMs: Long = MDBLIST_CACHE_TTL_MS,
) : ListService {
    override val id = ListServiceId.MDBLIST

    override val sorts: List<ListSort> = listOf(
        ListSort(SORT_UPDATED, "Last updated"),
        ListSort(SORT_TITLE, "Title"),
    )

    private data class Cache(val profileId: Int, val loadedAtEpochMs: Long, val states: List<HistoryTitleState>)

    private val cacheMutex = Mutex()
    private var cache: Cache? = null

    /** MDBList accepts IMDb, TMDB, TVDB and Trakt ids, the same four Trakt does; anime-only ids do not resolve. */
    override fun covers(kind: ListTitleKind, meta: MetaDetails): Boolean =
        buildTrackingMediaReference(meta.type, meta.id).ids.isTraktAddressable()

    override fun capabilities(type: ListMediaType): ListCapabilities = when (type) {
        // MDBList documents dropped writes (POST /sync/dropped and /sync/dropped/remove) for shows only.
        ListMediaType.SERIES -> ListCapabilities(
            statuses = listOf(ListStatus.WATCHING, ListStatus.COMPLETED, ListStatus.DROPPED, ListStatus.PLAN_TO_WATCH),
            pickableStatuses = setOf(ListStatus.PLAN_TO_WATCH, ListStatus.COMPLETED, ListStatus.DROPPED),
            score = false,
            episodeProgress = false,
            rewatch = false,
            changeEntry = false,
            removeFromHistory = true,
            leavableStatuses = setOf(ListStatus.PLAN_TO_WATCH, ListStatus.DROPPED),
            // MDBList rejects a drop for a show with no watched history.
            dropRequiresHistory = true,
        )
        ListMediaType.MOVIE -> ListCapabilities(
            statuses = listOf(ListStatus.COMPLETED, ListStatus.PLAN_TO_WATCH),
            pickableStatuses = setOf(ListStatus.PLAN_TO_WATCH, ListStatus.COMPLETED),
            score = false,
            episodeProgress = false,
            rewatch = false,
            changeEntry = false,
            removeFromHistory = true,
            leavableStatuses = setOf(ListStatus.PLAN_TO_WATCH),
        )
    }

    override fun profileUrl(): String? = "https://mdblist.com/"

    /** The next read loads again; that load refreshes the MDBList library and watched snapshot itself. */
    override suspend fun refresh() {
        cacheMutex.withLock { cache = null }
    }

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
            ),
        )
    }

    override suspend fun page(status: ListStatus?, sort: ListSort, offset: Int): ListPage {
        val matching = visibleStates().filter { status == null || it.status == status }
        val title = { state: HistoryTitleState -> state.title.lowercase() }
        val sorted = when (sort.key) {
            SORT_TITLE -> matching.sortedBy(title)
            else -> matching.sortedWith(compareByDescending<HistoryTitleState> { it.updatedAtEpochMs }.thenBy(title))
        }
        val start = offset.coerceAtLeast(0)
        val slice = sorted.drop(start).take(MDBLIST_PAGE_SIZE).map { it.toEntry() }
        val next = (start + MDBLIST_PAGE_SIZE).takeIf { it < sorted.size }
        return ListPage(slice, next)
    }

    override suspend fun entryFor(meta: MetaDetails): ListEntry? {
        val ids = buildTrackingMediaReference(meta.type, meta.id).ids
        if (!ids.isTraktAddressable()) return null
        val type = if (meta.type.trim().lowercase() in setOf("movie", "film")) ListMediaType.MOVIE else ListMediaType.SERIES
        val lookup = buildSet {
            add(meta.id.trim())
            ids.imdb?.takeIf(String::isNotBlank)?.let(::add)
            ids.tmdb?.let { add("tmdb:$it") }
            ids.trakt?.let { add("trakt:$it") }
        }
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
                ListChangeImpact(history.completedImpact(type, catalogId), hidesFromContinueWatching = false)
            }
            // Only Trakt's dropped list hides a show from Continue Watching.
            else -> ListChangeImpact(0, hidesFromContinueWatching = false)
        }
        else -> ListChangeImpact(0, hidesFromContinueWatching = false)
    }

    override suspend fun apply(entry: ListEntry, change: ListChange): ListEntry {
        val state = stateFor(entry)
        val updated = when (change) {
            is ListChange.SetStatus -> setStatus(entry, state, change.status)
            is ListChange.LeaveStatus -> leaveStatus(state, change.status)
            else -> throw IllegalArgumentException("MDBList does not support $change")
        }.copy(updatedAtEpochMs = port.nowEpochMs())
        remember(updated)
        return updated.toEntry(
            catalogType = entry.catalogType ?: updated.catalogType,
            catalogId = entry.catalogId ?: updated.contentId,
            anime = entry.anime,
        )
    }

    /**
     * The only change that deletes history, reached only from the confirmed "Remove from watch history" action.
     * Starlit's history goes first through its watched actions; the MDBList call is skipped when that fails.
     * Returns the larger of the two counts.
     */
    override suspend fun removeFromHistory(entry: ListEntry): Int {
        val (type, catalogId) = catalogOf(entry)
        val state = stateFor(entry)
        val removedLocally = history.removeAll(type, catalogId)
        port.removeHistory(state.reference())
        val onMdbList = if (state.isMovie) (if (entry.watchedEpisodes > 0) 1 else 0) else entry.watchedEpisodes
        remember(state.copy(watchedEpisodes = 0, updatedAtEpochMs = port.nowEpochMs()))
        return maxOf(removedLocally, onMdbList)
    }

    private suspend fun setStatus(entry: ListEntry, state: HistoryTitleState, status: ListStatus): HistoryTitleState {
        require(status in capabilities(state.type).pickableStatuses) { "MDBList does not offer $status for this title" }
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
            ListStatus.WATCHING, ListStatus.ON_HOLD -> throw IllegalArgumentException("MDBList does not offer $status")
        }
    }

    private suspend fun leaveStatus(state: HistoryTitleState, status: ListStatus): HistoryTitleState {
        require(status in capabilities(state.type).leavableStatuses) { "MDBList cannot leave $status for this title" }
        return when (status) {
            ListStatus.PLAN_TO_WATCH -> {
                port.setWatchlisted(state.libraryItem(port.nowEpochMs()), inWatchlist = false)
                state.copy(inWatchlist = false)
            }
            ListStatus.DROPPED -> {
                port.setDropped(state.reference(), dropped = false)
                state.copy(dropped = false)
            }
            else -> throw IllegalArgumentException("MDBList cannot leave $status")
        }
    }

    private fun catalogOf(entry: ListEntry): Pair<String, String> {
        val type = entry.catalogType
        val catalogId = entry.catalogId
        if (type == null || catalogId == null) throw IllegalStateException("This MDBList entry has no Starlit title to update")
        return type to catalogId
    }

    private suspend fun visibleStates(): List<HistoryTitleState> = states().filter { it.status != null }

    private suspend fun states(): List<HistoryTitleState> = cacheMutex.withLock {
        val profileId = port.activeProfileId()
        val now = port.nowEpochMs()
        cache
            ?.takeIf { it.profileId == profileId && now - it.loadedAtEpochMs in 0 until cacheTtlMs }
            ?.let { return@withLock it.states }
        val snapshot = port.load()
        val states = buildHistoryTitleStates(
            HistoryListSnapshot(
                watchlist = snapshot.watchlist,
                watched = snapshot.watched,
                dropped = snapshot.dropped.map { TraktDroppedShow(null, it.toExternalIds()) },
            ),
            ::airedEpisodesFor,
        )
        cache = Cache(profileId, now, states)
        states
    }

    /** Keeps a write visible in pages until the next reload (MDBList and history mirroring can lag behind). */
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
            aliases = setOf(contentId),
            title = title,
            posterUrl = posterUrl,
            year = null,
            inWatchlist = status == ListStatus.PLAN_TO_WATCH,
            dropped = status == ListStatus.DROPPED,
            watchedEpisodes = watchedEpisodes,
            airedEpisodes = totalEpisodes,
            score = 0,
            updatedAtEpochMs = 0L,
        )
    }
}

private fun MdbListIds.toExternalIds() =
    TrackingExternalIds(imdb = imdb, tmdb = tmdb, tvdb = tvdb?.toString(), trakt = trakt)

private fun HistoryTitleState.ids(): TrackingExternalIds =
    aliases.fold(TrackingExternalIds()) { ids, alias -> ids.mergeMissing(parseTrackingExternalIds(alias)) }

private fun HistoryTitleState.reference(): TrackingMediaReference = TrackingMediaReference(
    kind = if (isMovie) TrackingMediaKind.MOVIE else TrackingMediaKind.SHOW,
    title = title,
    year = year,
    ids = ids(),
    catalog = TrackingCatalogReference(contentId = contentId, contentType = catalogType),
)

private fun HistoryTitleState.libraryItem(nowEpochMs: Long): LibraryItem {
    val ids = ids()
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

private fun HistoryTitleState.toEntry(
    catalogType: String = this.catalogType,
    catalogId: String = contentId,
    anime: Boolean = false,
): ListEntry = ListEntry(
    key = ListEntryKey(ListServiceId.MDBLIST, key),
    title = title,
    subtitle = null,
    posterUrl = posterUrl,
    type = type,
    anime = anime,
    facts = listOfNotNull(year?.toString()),
    communityScore = null,
    status = status,
    score = 0,
    watchedEpisodes = watchedEpisodes,
    totalEpisodes = airedEpisodes,
    airing = false,
    rewatching = false,
    rewatchCount = 0,
    catalogType = catalogType,
    catalogId = catalogId,
    siteUrl = null,
)

/** Production wiring: MDBList registry ports, the MDBList services and Starlit's metadata/watched state. */
private object DefaultMdbListListPort : MdbListListPort {
    override fun activeProfileId(): Int = ProfileRepository.activeProfileId

    override suspend fun load(): MdbListListSnapshot = coroutineScope {
        val profileId = ProfileRepository.activeProfileId
        val watchlist = async { loadWatchlist() }
        // `pull` refreshes the sync snapshot first, so the dropped list read afterwards is current.
        val watched = TrackingProviderRegistry.watchedProvider(TrackingProviderId.MDBLIST)
            ?.pull(profileId, MDBLIST_WATCHED_PULL_PAGE_SIZE)
            .orEmpty()
        val dropped = MdbListTracker.sync.currentSnapshot()?.dropped.orEmpty()
            .filter { it.season == null }
            .map { it.ids }
        MdbListListSnapshot(watchlist = watchlist.await(), watched = watched, dropped = dropped)
    }

    private suspend fun loadWatchlist(): List<LibraryItem> {
        val library = TrackingProviderRegistry.libraryProvider(TrackingProviderId.MDBLIST) ?: return emptyList()
        library.ensureLoaded()
        library.refresh(TrackingRefreshIntent.AUTOMATIC)
        val snapshot = library.snapshot()
        snapshot.errorMessage?.let { message -> throw IllegalStateException(message) }
        val watchlistKey = snapshot.tabs.firstOrNull { it.kind == TrackingLibraryTabKind.WATCHLIST }?.key
            ?: return emptyList()
        return snapshot.sections.firstOrNull { it.type == watchlistKey }?.items.orEmpty()
    }

    /** Writes only the watchlist key; a full desired-membership map could remove the title from another list. */
    override suspend fun setWatchlisted(item: LibraryItem, inWatchlist: Boolean) {
        val library = TrackingProviderRegistry.libraryProvider(TrackingProviderId.MDBLIST)
            ?: throw IllegalStateException("MDBList is not connected")
        library.applyMembership(ProfileRepository.activeProfileId, item, mapOf(MDBLIST_WATCHLIST_KEY to inWatchlist), false)
    }

    override suspend fun setDropped(media: TrackingMediaReference, dropped: Boolean) {
        MdbListTracker.dropped.set(MdbListTracker.sync.currentScope(), media, dropped)
    }

    override suspend fun removeHistory(media: TrackingMediaReference) {
        val scope = MdbListTracker.sync.currentScope()
        MdbListTracker.writes.removeFromHistory(scope.profileId, listOf(media))
    }

    override fun cachedMeta(type: String, id: String): MetaDetails? = MetaDetailsRepository.peek(type = type, id = id)

    override suspend fun fetchMeta(type: String, id: String): MetaDetails? = MetaDetailsRepository.fetch(type = type, id = id)

    override fun isFullyWatchedLocally(type: String, id: String): Boolean =
        WatchedRepository.isFullyWatchedSeries(id = id, type = type)

    override fun todayIsoDate(): String = CurrentDateProvider.todayIsoDate()

    override fun nowEpochMs(): Long = System.currentTimeMillis()
}

object MdbListListService : ListService by MdbListListAdapter(
    port = DefaultMdbListListPort,
    connected = MdbListTracker.isAuthenticated,
    history = ListHistoryWrites(),
    signedInAs = { MdbListTracker.store.state.value.user?.username },
)
