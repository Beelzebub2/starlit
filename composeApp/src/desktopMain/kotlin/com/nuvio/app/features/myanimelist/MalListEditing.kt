package com.nuvio.app.features.myanimelist

/** The fields to change on MAL; null leaves that field as it is. */
internal data class MalListPatch(
    val status: MalStatus? = null,
    val score: Int? = null,
    val watchedEpisodes: Int? = null,
    val isRewatching: Boolean? = null,
    val numTimesRewatched: Int? = null,
)

/** Only the fields that differ between [from] and [to], or null when nothing changed. */
internal fun malListPatch(from: MalListStatus, to: MalListStatus): MalListPatch? {
    val patch = MalListPatch(
        status = to.status?.takeIf { it != from.status },
        score = to.score.takeIf { it != from.score },
        watchedEpisodes = to.watchedEpisodes.takeIf { it != from.watchedEpisodes },
        isRewatching = to.isRewatching.takeIf { it != from.isRewatching },
        numTimesRewatched = to.numTimesRewatched.takeIf { it != from.numTimesRewatched },
    )
    return patch.takeUnless { it == MalListPatch() }
}

/** A cancelled rewatch is not a completed rewatch; only an explicit finish increments MAL. */
internal fun MalListStatus.withCompletedRewatch(): MalListStatus =
    if (status == MalStatus.COMPLETED && isRewatching && numTimesRewatched < MAX_MAL_REWATCHES)
        copy(isRewatching = false, numTimesRewatched = numTimesRewatched + 1)
    else this

/**
 * Sets the watched-episode count the way MyAnimeList's own list does: the first episode moves a
 * planned title to Watching, and reaching the final episode marks it Completed (ending a rewatch).
 */
internal fun MalListStatus.withWatchedEpisodes(episodes: Int, totalEpisodes: Int): MalListStatus {
    val count = episodes.coerceIn(0, if (totalEpisodes > 0) totalEpisodes else MAX_MAL_EPISODES)
    val finished = totalEpisodes > 0 && count == totalEpisodes && count > watchedEpisodes
    val started = count > 0 && (status == null || status == MalStatus.PLAN_TO_WATCH)
    return when {
        finished -> withCompletedRewatch().copy(status = MalStatus.COMPLETED, watchedEpisodes = count, isRewatching = false)
        started -> copy(status = MalStatus.WATCHING, watchedEpisodes = count)
        else -> copy(watchedEpisodes = count)
    }
}

/** Changes the list status; Completed also fills in every episode when the total is known. */
internal fun MalListStatus.withStatus(newStatus: MalStatus, totalEpisodes: Int): MalListStatus {
    if (newStatus == status) return this
    val episodes = if (newStatus == MalStatus.COMPLETED && totalEpisodes > 0) totalEpisodes else watchedEpisodes
    return copy(status = newStatus, watchedEpisodes = episodes, isRewatching = false)
}

internal fun malScoreLabel(score: Int): String = when (score) {
    10 -> "Masterpiece"
    9 -> "Great"
    8 -> "Very good"
    7 -> "Good"
    6 -> "Fine"
    5 -> "Average"
    4 -> "Bad"
    3 -> "Very bad"
    2 -> "Horrible"
    1 -> "Appalling"
    else -> "No score"
}

internal fun malMediaTypeLabel(type: String?): String? = when (type?.lowercase()) {
    null, "", "unknown" -> null
    "tv" -> "TV"
    "tv_special" -> "TV special"
    "ova", "ona", "cm", "pv" -> type.uppercase()
    else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Short facts shown under a list entry's title, e.g. "TV · Spring 2016 · 10 eps". */
internal fun malEntryFacts(entry: MalAnimeListEntry): List<String> = buildList {
    malMediaTypeLabel(entry.mediaType)?.let(::add)
    val season = entry.startSeason?.replaceFirstChar { it.uppercase() }
    when {
        season != null && entry.startYear != null -> add("$season ${entry.startYear}")
        entry.startYear != null -> add(entry.startYear.toString())
    }
    if (entry.totalEpisodes > 0) add(if (entry.totalEpisodes == 1) "1 ep" else "${entry.totalEpisodes} eps")
}
