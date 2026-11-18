package com.nuvio.app.features.player.skip

import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration

internal data class InternalSkipAction(
    val targetMs: Long,
    val skipsToPostCredits: Boolean,
)

internal fun SkipInterval.internalSkipAction(
    intervals: List<SkipInterval>,
    durationMs: Long = 0L,
): InternalSkipAction? {
    if (isShortPlaceholderDuration(durationMs)) return null
    val normalizedType = type.trim().lowercase()
    if (normalizedType == "post-credits") return null
    val isMovieCredits = normalizedType == "movie-credits"
    // Episode providers may use Double.MAX_VALUE as an open-ended segment sentinel.
    if (isMovieCredits && !hasValidSeekTimes()) return null
    // Repository intervals (including post-credits) remain intact for external players.
    val canHavePostCredits = isMovieCredits || normalizedType in PlayerNextEpisodeRules.OUTRO_SEGMENT_TYPES
    val scene = if (canHavePostCredits) {
        intervals.filter {
            it.type.trim().lowercase() == "post-credits" && it.hasValidSeekTimes() &&
                it.startTime >= endTime &&
                (durationMs <= 0L || it.startTime < durationMs / 1000.0)
        }.minByOrNull { it.startTime }
    } else null
    val hasPostCreditsTail = canHavePostCredits && hasValidSeekTimes() && durationMs > 0L &&
        durationMs - (endTime * 1000.0).toLong() > POST_CREDITS_GAP_MS
    return InternalSkipAction(
        targetMs = ((scene?.startTime ?: endTime) * 1000.0).toLong(),
        skipsToPostCredits = scene != null || hasPostCreditsTail,
    )
}

internal fun List<SkipInterval>.protectPostCredits(): List<SkipInterval> {
    val scenes = filter { it.type == "post-credits" && it.hasValidSeekTimes() }
    if (scenes.isEmpty()) return this
    return mapNotNull { interval ->
        if (interval.type !in PlayerNextEpisodeRules.OUTRO_SEGMENT_TYPES && interval.type != "movie-credits") {
            return@mapNotNull interval
        }
        val scene = scenes.filter { it.startTime < interval.endTime && it.endTime > interval.startTime }
            .minByOrNull { it.startTime } ?: return@mapNotNull interval
        interval.copy(endTime = scene.startTime).takeIf { it.hasValidSeekTimes() }
    }
}

private const val POST_CREDITS_GAP_MS = 5_000L

internal fun SkipInterval.hasValidSeekTimes(): Boolean =
    startTime.isFinite() && endTime.isFinite() && startTime >= 0 && endTime > startTime &&
        endTime * 1000.0 < Long.MAX_VALUE.toDouble()
