package com.nuvio.app.features.lists

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.myanimelist.MalAnimeListEntry
import com.nuvio.app.features.myanimelist.MalAnimeStatistics
import com.nuvio.app.features.myanimelist.MalListPatch
import com.nuvio.app.features.myanimelist.MalListSort
import com.nuvio.app.features.myanimelist.MalListStatus
import com.nuvio.app.features.myanimelist.MalStatus
import com.nuvio.app.features.myanimelist.MyAnimeListRepository
import com.nuvio.app.features.myanimelist.malEntryFacts
import com.nuvio.app.features.myanimelist.malListPatch
import com.nuvio.app.features.myanimelist.withStatus
import com.nuvio.app.features.myanimelist.withWatchedEpisodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal fun MalStatus.toListStatus(): ListStatus = when (this) {
    MalStatus.WATCHING -> ListStatus.WATCHING
    MalStatus.COMPLETED -> ListStatus.COMPLETED
    MalStatus.ON_HOLD -> ListStatus.ON_HOLD
    MalStatus.DROPPED -> ListStatus.DROPPED
    MalStatus.PLAN_TO_WATCH -> ListStatus.PLAN_TO_WATCH
}

internal fun ListStatus.toMalStatus(): MalStatus = when (this) {
    ListStatus.WATCHING -> MalStatus.WATCHING
    ListStatus.COMPLETED -> MalStatus.COMPLETED
    ListStatus.ON_HOLD -> MalStatus.ON_HOLD
    ListStatus.DROPPED -> MalStatus.DROPPED
    ListStatus.PLAN_TO_WATCH -> MalStatus.PLAN_TO_WATCH
}

internal fun malSiteUrl(id: Int): String = "https://myanimelist.net/anime/$id"

internal fun MalAnimeListEntry.toListEntry(): ListEntry {
    val type = mediaType ?: anime.mediaType
    // The same "TV · Fall 2025 · 12 eps" line the MyAnimeList tab has always shown.
    val facts = malEntryFacts(copy(mediaType = type, startYear = startYear ?: anime.startYear))
    return ListEntry(
        key = ListEntryKey(ListServiceId.MYANIMELIST, anime.id.toString()),
        title = anime.title,
        subtitle = englishTitle?.takeIf { it.isNotBlank() && it != anime.title },
        posterUrl = imageUrl,
        type = if (type.equals("movie", ignoreCase = true)) ListMediaType.MOVIE else ListMediaType.SERIES,
        anime = true,
        facts = facts,
        communityScore = meanScore,
        status = listStatus.status?.toListStatus(),
        score = listStatus.score,
        watchedEpisodes = listStatus.watchedEpisodes,
        totalEpisodes = totalEpisodes,
        airing = airing,
        rewatching = listStatus.isRewatching,
        rewatchCount = listStatus.numTimesRewatched,
        catalogType = null,
        catalogId = null,
        siteUrl = malSiteUrl(anime.id),
    )
}

internal fun ListEntry.withMalListStatus(status: MalListStatus): ListEntry = copy(
    status = status.status?.toListStatus(),
    score = status.score,
    watchedEpisodes = status.watchedEpisodes,
    rewatching = status.isRewatching,
    rewatchCount = status.numTimesRewatched,
)

internal fun ListEntry.malListStatus(): MalListStatus =
    MalListStatus(status?.toMalStatus(), score, watchedEpisodes, rewatching, rewatchCount)

/**
 * The episode step as MyAnimeList's own list applies it: the count is clamped to the title's length, the first
 * episode moves a planned title to Watching and the last one marks it Completed.
 */
internal fun malStepPatch(entry: ListEntry, delta: Int): MalListPatch {
    val current = entry.malListStatus()
    val target = current.withWatchedEpisodes(current.watchedEpisodes + delta, entry.totalEpisodes)
    return malListPatch(current, target) ?: MalListPatch(watchedEpisodes = target.watchedEpisodes)
}

/** Status change as the MyAnimeList tab always made it: Completed fills in every episode when the total is known. */
internal fun malStatusPatch(entry: ListEntry, status: ListStatus): MalListPatch? {
    val current = entry.malListStatus()
    return malListPatch(current, current.withStatus(status.toMalStatus(), entry.totalEpisodes))
}

/** The tab's header figures for a MyAnimeList account; labels sit after their values ("2,650 episodes"). */
internal fun malListSummary(username: String?, stats: MalAnimeStatistics): ListSummary = ListSummary(
    signedInAs = username?.takeIf { it.isNotBlank() && it != "Connected" },
    counts = buildMap {
        MalStatus.entries.forEach { put(it.toListStatus(), stats.count(it)) }
        put(null, stats.count(null))
    },
    extra = buildList {
        add("episodes" to stats.episodes.toString())
        add("days watched" to "%.1f".format(stats.days))
        if (stats.meanScore > 0) add("mean score" to "%.2f".format(stats.meanScore))
    },
)

internal fun malProfileUrlFor(username: String): String =
    username.takeIf { it.matches(Regex("[A-Za-z0-9_-]{2,32}")) }
        ?.let { "https://myanimelist.net/animelist/$it" }
        ?: "https://myanimelist.net/"

/**
 * Writes [change] to MyAnimeList with MAL's own list rules (see [malStatusPatch] and [malStepPatch]) through
 * [update] and [finishRewatch], and returns the entry with MAL's answer.
 */
internal suspend fun applyMalChange(
    entry: ListEntry,
    change: ListChange,
    update: suspend (id: Int, patch: MalListPatch) -> MalListStatus,
    finishRewatch: suspend (id: Int) -> MalListStatus,
): ListEntry {
    val malId = entry.key.id.toInt()
    val updated = when (change) {
        is ListChange.SetStatus -> update(malId, malStatusPatch(entry, change.status) ?: return entry)
        is ListChange.SetScore -> update(malId, MalListPatch(score = change.score))
        is ListChange.SetRewatching -> update(malId, MalListPatch(isRewatching = change.on))
        ListChange.FinishRewatch -> finishRewatch(malId)
        is ListChange.StepEpisodes -> update(malId, malStepPatch(entry, change.delta))
        is ListChange.LeaveStatus -> throw IllegalArgumentException("MyAnimeList has no leave-status change")
    }
    return entry.withMalListStatus(updated)
}

/** MyAnimeList supports every list feature except removing watch history; movies and series alike. */
internal val malListCapabilities = ListCapabilities(
    statuses = listOf(ListStatus.WATCHING, ListStatus.COMPLETED, ListStatus.ON_HOLD, ListStatus.DROPPED, ListStatus.PLAN_TO_WATCH),
    pickableStatuses = ListStatus.entries.toSet(),
    score = true,
    episodeProgress = true,
    rewatch = true,
    changeEntry = true,
    removeFromHistory = false,
)

object MalListService : ListService {
    override val id = ListServiceId.MYANIMELIST

    override val connected: StateFlow<Boolean> = MyAnimeListRepository.state
        .map { it.connected }
        .stateIn(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            SharingStarted.Eagerly,
            MyAnimeListRepository.state.value.connected,
        )

    override val sorts: List<ListSort> = MalListSort.entries.map { ListSort(it.name, it.label) }

    override fun covers(kind: ListTitleKind, meta: MetaDetails): Boolean = kind.anime

    override fun capabilities(type: ListMediaType): ListCapabilities = malListCapabilities

    override fun profileUrl(): String? = malProfileUrlFor(MyAnimeListRepository.state.value.username)

    override suspend fun summary(): ListSummary? =
        malListSummary(MyAnimeListRepository.state.value.username, MyAnimeListRepository.animeStatistics())

    override suspend fun page(status: ListStatus?, sort: ListSort, offset: Int): ListPage {
        val malSort = MalListSort.entries.firstOrNull { it.name == sort.key } ?: MalListSort.UPDATED
        val page = MyAnimeListRepository.animeList(status?.toMalStatus(), offset, malSort)
        return ListPage(page.entries.map { it.toListEntry() }, page.nextOffset)
    }

    override suspend fun entryFor(meta: MetaDetails): ListEntry? {
        val malId = MyAnimeListRepository.resolveId(meta) ?: return null
        val status = MyAnimeListRepository.listStatus(malId) ?: return null
        return ListEntry(
            key = ListEntryKey(id, malId.toString()),
            title = meta.name,
            subtitle = null,
            posterUrl = meta.poster,
            type = if (meta.type.equals("movie", ignoreCase = true)) ListMediaType.MOVIE else ListMediaType.SERIES,
            anime = true,
            facts = emptyList(),
            communityScore = null,
            status = null,
            score = 0,
            watchedEpisodes = 0,
            totalEpisodes = 0,
            airing = false,
            rewatching = false,
            rewatchCount = 0,
            catalogType = meta.type,
            catalogId = meta.id,
            siteUrl = malSiteUrl(malId),
        ).withMalListStatus(status)
    }

    override suspend fun impact(entry: ListEntry, change: ListChange): ListChangeImpact =
        ListChangeImpact(0, false)

    override suspend fun apply(entry: ListEntry, change: ListChange): ListEntry = applyMalChange(
        entry, change,
        update = { id, patch -> MyAnimeListRepository.update(id, patch) },
        finishRewatch = { id -> MyAnimeListRepository.finishRewatch(id) },
    )

    override suspend fun removeFromHistory(entry: ListEntry): Int =
        throw UnsupportedOperationException("MyAnimeList keeps no watch history to remove")
}
