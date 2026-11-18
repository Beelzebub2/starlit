package com.nuvio.app.features.lists

import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.flow.StateFlow

enum class ListServiceId(val storageId: String, val displayName: String) {
    MYANIMELIST("myanimelist", "MyAnimeList"), SIMKL("simkl", "Simkl"), TRAKT("trakt", "Trakt"), MDBLIST("mdblist", "MDBList")
}

enum class ListStatus { WATCHING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_WATCH }

enum class ListMediaType { MOVIE, SERIES }

data class ListTitleKind(val type: ListMediaType, val anime: Boolean)

data class ListCapabilities(
    val statuses: List<ListStatus>,
    val pickableStatuses: Set<ListStatus>,
    val score: Boolean,
    val episodeProgress: Boolean,
    val rewatch: Boolean,
    val changeEntry: Boolean,
    val removeFromHistory: Boolean,
)

data class ListSort(val key: String, val label: String)

data class ListEntryKey(val service: ListServiceId, val id: String)

data class ListEntry(
    val key: ListEntryKey,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val type: ListMediaType,
    val anime: Boolean,
    val facts: List<String>,
    val communityScore: Double?,
    val status: ListStatus?,
    val score: Int,
    val watchedEpisodes: Int,
    val totalEpisodes: Int,
    val airing: Boolean,
    val rewatching: Boolean,
    val rewatchCount: Int,
    val catalogType: String?,
    val catalogId: String?,
    val siteUrl: String?,
)

data class ListPage(val entries: List<ListEntry>, val nextOffset: Int?)

data class ListSummary(
    val signedInAs: String?,
    val counts: Map<ListStatus?, Int>,
    val extra: List<Pair<String, String>>,
)

sealed interface ListChange {
    data class SetStatus(val status: ListStatus) : ListChange
    data class LeaveStatus(val status: ListStatus) : ListChange
    data class SetScore(val score: Int) : ListChange
    data class StepEpisodes(val delta: Int) : ListChange
    data class SetRewatching(val on: Boolean) : ListChange
    data object FinishRewatch : ListChange
}

/** Number of history entries a change will add; > 1 needs confirmation. */
data class ListChangeImpact(val episodesMarked: Int, val hidesFromContinueWatching: Boolean)

interface ListService {
    val id: ListServiceId
    val connected: StateFlow<Boolean>
    val sorts: List<ListSort>
    fun covers(kind: ListTitleKind, meta: MetaDetails): Boolean
    fun capabilities(type: ListMediaType): ListCapabilities
    fun profileUrl(): String?
    suspend fun summary(): ListSummary?
    suspend fun page(status: ListStatus?, sort: ListSort, offset: Int): ListPage
    suspend fun entryFor(meta: MetaDetails): ListEntry?
    suspend fun impact(entry: ListEntry, change: ListChange): ListChangeImpact
    suspend fun apply(entry: ListEntry, change: ListChange): ListEntry
    suspend fun removeFromHistory(entry: ListEntry): Int
}
