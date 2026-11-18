package com.nuvio.app.features.player.skip

import kotlinx.serialization.Serializable
import kotlin.math.abs

internal fun List<SkipInterval>.alignOpeningsToChapters(chapterStarts: List<Double>): List<SkipInterval> {
    val chapters = chapterStarts.filter { it.isFinite() && it >= 0 }.distinct().sorted().zipWithNext()
    if (chapters.isEmpty()) return this
    return map { interval ->
        if (interval.provider !in setOf("aniskip", "animeskip") ||
            interval.type !in setOf("op", "mixed-op")
        ) return@map interval
        val length = interval.endTime - interval.startTime
        // Refine an existing anime opening estimate only. Unnamed chapters alone
        // cannot identify an intro. Allow small release offsets, not other scenes.
        val chapter = chapters.singleOrNull { (start, end) ->
            abs(start - interval.startTime) <= 20.0 &&
                abs(end - interval.endTime) <= 20.0 &&
                abs((end - start) - length) <= 5.0
        }
        chapter?.let { interval.copy(startTime = it.first, endTime = it.second) } ?: interval
    }
}

@Serializable
data class PlaybackChapter(val startTime: Double, val title: String = "")

private val openingChapter = Regex("""(?:op|opening)(?:\s*(?:credits|theme))?\s*\d*""")
private val endingChapter = Regex("""(?:ed|ending)(?:\s*(?:credits|theme))?\s*\d*|(?:(?:end|closing)\s*)?credits""")
private val recapChapter = Regex("""recap|previously(?:\s+on\b.*)?""")
private val postCreditsChapter = Regex("""(?:post|mid|after)[\s-]+credits(?:\s+scene)?""")

internal fun resolvePlaybackSkipIntervals(
    providerIntervals: List<SkipInterval>,
    chapters: List<PlaybackChapter>,
    durationMs: Long,
    isMovie: Boolean,
): List<SkipInterval> {
    val duration = durationMs / 1000.0
    val ordered = chapters.filter { it.startTime.isFinite() && it.startTime >= 0 && it.startTime < duration }
        .sortedBy { it.startTime }.distinctBy { it.startTime }
    val explicitOpening = ordered.any { openingChapter.matches(it.title.trim().lowercase()) }
    val fromChapters = ordered.mapIndexedNotNull { index, chapter ->
        val title = chapter.title.trim().lowercase()
        val type = when {
            openingChapter.matches(title) || (title == "intro" && !explicitOpening) -> "intro"
            endingChapter.matches(title) -> if (isMovie) "movie-credits" else "outro"
            recapChapter.matches(title) -> "recap"
            postCreditsChapter.matches(title) -> "post-credits"
            else -> return@mapIndexedNotNull null
        }
        val next = ordered.getOrNull(index + 1)
        // A final movie credits chapter may include an unmarked scene.
        if (next == null && type == "movie-credits") return@mapIndexedNotNull null
        val end = next?.startTime ?: duration
        val length = end - chapter.startTime
        if (type == "post-credits") {
            if (length <= 0) return@mapIndexedNotNull null
        } else if (length !in 5.0..(if (type == "movie-credits") 900.0 else 240.0)) {
            return@mapIndexedNotNull null
        }
        if (ordered.size < 2 && type != "post-credits") return@mapIndexedNotNull null
        SkipInterval(chapter.startTime, end, type, "chapters")
    }
    val aligned = providerIntervals.alignOpeningsToChapters(ordered.map { it.startTime })
    fun category(interval: SkipInterval) =
        AutoSkipSegmentType.fromSkipIntervalType(interval.type)?.storedValue ?: interval.type

    val sourceCategories = fromChapters.map(::category).toSet()
    return (fromChapters + aligned.filter { interval ->
        category(interval) !in sourceCategories &&
            interval.hasValidSeekTimes() && interval.startTime < duration && interval.endTime <= duration
    }).protectPostCredits().sortedBy { it.startTime }
}
