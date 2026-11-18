package com.nuvio.app.features.lists

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.myanimelist.MAX_MAL_EPISODES
import com.nuvio.app.features.myanimelist.MalAnimeListEntry
import com.nuvio.app.features.myanimelist.MalListPatch
import com.nuvio.app.features.myanimelist.MalListSort
import com.nuvio.app.features.myanimelist.MalListStatus
import com.nuvio.app.features.myanimelist.MalStatus
import com.nuvio.app.features.myanimelist.MyAnimeListRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

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
    val facts = buildList {
        type?.takeIf { it.isNotBlank() }?.let {
            add(if (it.length <= 3) it.uppercase(Locale.ROOT) else it.replaceFirstChar { c -> c.uppercase() })
        }
        (startYear ?: anime.startYear)?.let { add(it.toString()) }
    }
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

internal fun malStepPatch(entry: ListEntry, delta: Int): MalListPatch {
    val max = if (entry.totalEpisodes > 0) entry.totalEpisodes else MAX_MAL_EPISODES
    return MalListPatch(watchedEpisodes = (entry.watchedEpisodes + delta).coerceIn(0, max))
}

internal fun malProfileUrlFor(username: String): String =
    username.takeIf { it.matches(Regex("[A-Za-z0-9_-]{2,32}")) }
        ?.let { "https://myanimelist.net/animelist/$it" }
        ?: "https://myanimelist.net/"

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

    override fun capabilities(type: ListMediaType): ListCapabilities = ListCapabilities(
        statuses = listOf(ListStatus.WATCHING, ListStatus.COMPLETED, ListStatus.ON_HOLD, ListStatus.DROPPED, ListStatus.PLAN_TO_WATCH),
        pickableStatuses = ListStatus.entries.toSet(),
        score = true,
        episodeProgress = true,
        rewatch = true,
        changeEntry = true,
        removeFromHistory = false,
    )

    override fun profileUrl(): String? = malProfileUrlFor(MyAnimeListRepository.state.value.username)

    override suspend fun summary(): ListSummary? {
        val stats = MyAnimeListRepository.animeStatistics()
        val counts = buildMap<ListStatus?, Int> {
            MalStatus.entries.forEach { put(it.toListStatus(), stats.count(it)) }
            put(null, stats.count(null))
        }
        return ListSummary(
            signedInAs = MyAnimeListRepository.state.value.username.takeIf { it.isNotBlank() },
            counts = counts,
            extra = listOf(
                "Episodes" to stats.episodes.toString(),
                "Days watched" to String.format(Locale.ROOT, "%.1f", stats.days),
                "Mean score" to String.format(Locale.ROOT, "%.2f", stats.meanScore),
            ),
        )
    }

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

    override suspend fun apply(entry: ListEntry, change: ListChange): ListEntry {
        val malId = entry.key.id.toInt()
        val updated = when (change) {
            is ListChange.SetStatus -> MyAnimeListRepository.update(malId, status = change.status.toMalStatus())
            is ListChange.SetScore -> MyAnimeListRepository.update(malId, score = change.score)
            is ListChange.SetRewatching -> MyAnimeListRepository.update(malId, isRewatching = change.on)
            ListChange.FinishRewatch -> MyAnimeListRepository.finishRewatch(malId)
            is ListChange.StepEpisodes -> MyAnimeListRepository.update(malId, malStepPatch(entry, change.delta))
            is ListChange.LeaveStatus -> throw IllegalArgumentException("MyAnimeList has no leave-status change")
        }
        return entry.withMalListStatus(updated)
    }

    override suspend fun removeFromHistory(entry: ListEntry): Int =
        throw UnsupportedOperationException("MyAnimeList keeps no watch history to remove")
}
