package com.nuvio.app.features.lists

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklLibraryEntry
import com.nuvio.app.features.simkl.SimklListStatus
import com.nuvio.app.features.simkl.SimklMediaType
import com.nuvio.app.features.simkl.SimklMutationRepository
import com.nuvio.app.features.simkl.SimklSyncRepository
import com.nuvio.app.features.simkl.parseSimklUtcEpochMs
import com.nuvio.app.features.simkl.sharesIdentityWith
import com.nuvio.app.features.simkl.toLibraryItem
import com.nuvio.app.features.simkl.toTrackingExternalIds
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import kotlinx.coroutines.flow.StateFlow

/** Write seam for Simkl list/rating mutations; the default talks to [SimklMutationRepository]. */
interface SimklListWrites {
    suspend fun moveToList(media: TrackingMediaReference, status: TrackingListStatus): TrackingMutationResult
    suspend fun setRating(media: TrackingMediaReference, rating: Int): TrackingMutationResult
}

private object DefaultSimklListWrites : SimklListWrites {
    override suspend fun moveToList(media: TrackingMediaReference, status: TrackingListStatus): TrackingMutationResult =
        SimklMutationRepository.moveToList(ProfileRepository.activeProfileId, listOf(media), status)

    override suspend fun setRating(media: TrackingMediaReference, rating: Int): TrackingMutationResult =
        SimklMutationRepository.setRating(ProfileRepository.activeProfileId, media, rating)
}

internal fun ListStatus.toSimklListStatus(): SimklListStatus = when (this) {
    ListStatus.WATCHING -> SimklListStatus.WATCHING
    ListStatus.COMPLETED -> SimklListStatus.COMPLETED
    ListStatus.ON_HOLD -> SimklListStatus.ON_HOLD
    ListStatus.DROPPED -> SimklListStatus.DROPPED
    ListStatus.PLAN_TO_WATCH -> SimklListStatus.PLAN_TO_WATCH
}

internal fun SimklListStatus.toListStatus(): ListStatus = when (this) {
    SimklListStatus.WATCHING -> ListStatus.WATCHING
    SimklListStatus.COMPLETED -> ListStatus.COMPLETED
    SimklListStatus.ON_HOLD -> ListStatus.ON_HOLD
    SimklListStatus.DROPPED -> ListStatus.DROPPED
    SimklListStatus.PLAN_TO_WATCH -> ListStatus.PLAN_TO_WATCH
}

private fun ListStatus.toTrackingStatus(): TrackingListStatus = when (this) {
    ListStatus.WATCHING -> TrackingListStatus.WATCHING
    ListStatus.COMPLETED -> TrackingListStatus.COMPLETED
    ListStatus.ON_HOLD -> TrackingListStatus.ON_HOLD
    ListStatus.DROPPED -> TrackingListStatus.DROPPED
    ListStatus.PLAN_TO_WATCH -> TrackingListStatus.PLAN_TO_WATCH
}

private const val SIMKL_PAGE_SIZE = 50
private const val SORT_UPDATED = "UPDATED"
private const val SORT_TITLE = "TITLE"
private const val SORT_SCORE = "SCORE"

private fun SimklLibraryEntry.simklId(): String? =
    media?.toTrackingExternalIds()?.simkl?.toString()

private fun SimklLibraryEntry.trackingReference(): TrackingMediaReference? {
    val media = media ?: return null
    return TrackingMediaReference(
        kind = when {
            isMovieEntry() -> TrackingMediaKind.MOVIE
            mediaType == SimklMediaType.ANIME -> TrackingMediaKind.ANIME
            else -> TrackingMediaKind.SHOW
        },
        title = media.title,
        year = media.year,
        ids = media.toTrackingExternalIds(),
    )
}

private fun SimklLibraryEntry.updatedAtEpochMs(): Long =
    parseSimklUtcEpochMs(lastWatchedAt) ?: parseSimklUtcEpochMs(addedToWatchlistAt) ?: 0L

private fun SimklLibraryEntry.toListEntry(): ListEntry? {
    val item = toLibraryItem(listKey = "", lastSyncedAtEpochMs = null) ?: return null
    val isMovie = isMovieEntry()
    return ListEntry(
        key = ListEntryKey(ListServiceId.SIMKL, simklId() ?: item.id),
        title = item.name,
        subtitle = null,
        posterUrl = item.poster,
        type = if (isMovie) ListMediaType.MOVIE else ListMediaType.SERIES,
        anime = mediaType == SimklMediaType.ANIME,
        facts = listOfNotNull(item.releaseInfo),
        communityScore = null,
        status = status?.toListStatus(),
        score = userRating ?: 0,
        watchedEpisodes = watchedEpisodesCount,
        totalEpisodes = totalEpisodesCount,
        airing = notAiredEpisodesCount > 0,
        rewatching = false,
        rewatchCount = 0,
        catalogType = item.type,
        catalogId = item.id,
        siteUrl = item.trackingSourceUrl,
    )
}

/** Simkl list service over the local sync snapshot; writes go through the Simkl mutation repository and the title page's watched actions. */
class SimklListAdapter(
    private val entries: () -> List<SimklLibraryEntry>,
    override val connected: StateFlow<Boolean>,
    private val writes: SimklListWrites,
    private val history: ListHistoryWrites,
    private val signedInAs: () -> String?,
) : ListService {
    override val id = ListServiceId.SIMKL

    override val sorts: List<ListSort> = listOf(
        ListSort(SORT_UPDATED, "Last updated"),
        ListSort(SORT_TITLE, "Title"),
        ListSort(SORT_SCORE, "Your score"),
    )

    override fun covers(kind: ListTitleKind, meta: MetaDetails): Boolean =
        buildTrackingMediaReference(meta.type, meta.id).ids.hasAny

    override fun capabilities(type: ListMediaType): ListCapabilities = when (type) {
        ListMediaType.SERIES -> ListCapabilities(
            statuses = listOf(ListStatus.WATCHING, ListStatus.COMPLETED, ListStatus.ON_HOLD, ListStatus.DROPPED, ListStatus.PLAN_TO_WATCH),
            pickableStatuses = ListStatus.entries.toSet(),
            score = true,
            episodeProgress = true,
            rewatch = false,
            changeEntry = false,
            removeFromHistory = false,
        )
        ListMediaType.MOVIE -> {
            val statuses = listOf(ListStatus.PLAN_TO_WATCH, ListStatus.COMPLETED, ListStatus.DROPPED)
            ListCapabilities(
                statuses = statuses,
                pickableStatuses = statuses.toSet(),
                score = true,
                episodeProgress = false,
                rewatch = false,
                changeEntry = false,
                removeFromHistory = false,
            )
        }
    }

    override fun profileUrl(): String? = "https://simkl.com/"

    override suspend fun summary(): ListSummary {
        val all = entries()
        val counts = buildMap<ListStatus?, Int> {
            ListStatus.entries.forEach { listStatus ->
                put(listStatus, all.count { it.status?.toListStatus() == listStatus })
            }
            put(null, all.size)
        }
        return ListSummary(
            signedInAs = signedInAs(),
            counts = counts,
            extra = listOf(
                "Episodes watched" to all.sumOf { it.watchedEpisodesCount }.toString(),
                "Rated titles" to all.count { (it.userRating ?: 0) > 0 }.toString(),
            ),
        )
    }

    override suspend fun page(status: ListStatus?, sort: ListSort, offset: Int): ListPage {
        val matching = entries().filter { status == null || it.status?.toListStatus() == status }
        val title = { entry: SimklLibraryEntry -> entry.media?.title.orEmpty().lowercase() }
        val sorted = when (sort.key) {
            SORT_TITLE -> matching.sortedBy(title)
            SORT_SCORE -> matching.sortedWith(compareByDescending<SimklLibraryEntry> { it.userRating ?: 0 }.thenBy(title))
            else -> matching.sortedByDescending { it.updatedAtEpochMs() }
        }
        val mapped = sorted.mapNotNull { it.toListEntry() }
        val start = offset.coerceAtLeast(0)
        val slice = mapped.drop(start).take(SIMKL_PAGE_SIZE)
        val next = (start + SIMKL_PAGE_SIZE).takeIf { it < mapped.size }
        return ListPage(slice, next)
    }

    override suspend fun entryFor(meta: MetaDetails): ListEntry? {
        val ids = buildTrackingMediaReference(meta.type, meta.id).ids
        if (!ids.hasAny) return null
        val match = entries().firstOrNull { it.media?.toTrackingExternalIds()?.sharesIdentityWith(ids) == true }
            ?: return null
        return match.toListEntry()?.copy(catalogType = meta.type, catalogId = meta.id)
    }

    override suspend fun impact(entry: ListEntry, change: ListChange): ListChangeImpact = when (change) {
        is ListChange.SetStatus ->
            if (change.status == ListStatus.COMPLETED && entry.catalogType != null && entry.catalogId != null) {
                ListChangeImpact(history.completedImpact(entry.catalogType, entry.catalogId), hidesFromContinueWatching = true)
            } else {
                ListChangeImpact(0, false)
            }
        is ListChange.StepEpisodes -> ListChangeImpact(kotlin.math.abs(change.delta), false)
        else -> ListChangeImpact(0, false)
    }

    override suspend fun apply(entry: ListEntry, change: ListChange): ListEntry = when (change) {
        is ListChange.SetStatus -> {
            require(change.status in capabilities(entry.type).pickableStatuses) {
                "Simkl does not allow ${change.status} for this title"
            }
            val reference = referenceFor(entry)
            if (change.status == ListStatus.COMPLETED && entry.catalogType != null && entry.catalogId != null) {
                history.markCompleted(entry.catalogType, entry.catalogId, entry.title, entry.posterUrl)
            }
            checkWritten(writes.moveToList(reference, change.status.toTrackingStatus()))
            entry.copy(
                status = change.status,
                watchedEpisodes = if (change.status == ListStatus.COMPLETED && entry.totalEpisodes > 0) entry.totalEpisodes else entry.watchedEpisodes,
            )
        }
        is ListChange.SetScore -> {
            require(change.score in 0..10) { "Simkl scores range from 0 to 10" }
            checkWritten(writes.setRating(referenceFor(entry), change.score))
            entry.copy(score = change.score)
        }
        is ListChange.StepEpisodes -> {
            val type = entry.catalogType
            val catalogId = entry.catalogId
            require(type != null && catalogId != null) { "This Simkl entry has no Starlit title to update" }
            history.stepEpisodes(type, catalogId, change.delta)
            val max = if (entry.totalEpisodes > 0) entry.totalEpisodes else Int.MAX_VALUE
            entry.copy(watchedEpisodes = (entry.watchedEpisodes + change.delta).coerceIn(0, max))
        }
        is ListChange.LeaveStatus ->
            throw IllegalArgumentException("Simkl removes history when leaving a list, so it has no leave-status change")
        is ListChange.SetRewatching, ListChange.FinishRewatch ->
            throw IllegalArgumentException("Simkl has no rewatch tracking")
    }

    override suspend fun removeFromHistory(entry: ListEntry): Int =
        throw UnsupportedOperationException("Simkl removes history when leaving a list; use Starlit's watched controls instead")

    private fun referenceFor(entry: ListEntry): TrackingMediaReference {
        val snapshotEntry = entries().firstOrNull { it.simklId() == entry.key.id }
        return snapshotEntry?.trackingReference()
            ?: throw IllegalStateException("This title is no longer in your Simkl list")
    }

    private fun checkWritten(result: TrackingMutationResult) {
        if (result.attemptedCount == 0) throw IllegalStateException("Simkl change was not sent (profile changed?)")
        if (result.notFoundCount > 0) throw IllegalStateException("Simkl could not find this title")
    }
}

object SimklListService : ListService by SimklListAdapter(
    entries = {
        SimklSyncRepository.ensureLoaded()
        SimklSyncRepository.state.value.snapshot.entries
    },
    connected = SimklAuthRepository.also { it.ensureLoaded() }.isAuthenticated,
    writes = DefaultSimklListWrites,
    history = ListHistoryWrites(),
    signedInAs = { null },
)
