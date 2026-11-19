package com.nuvio.app.features.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

object SubtitleForwarder {

    /**
     * Fetches addon subtitles for the given content and filters them by the user's
     * preferred and secondary language. Returns null on failure or timeout for
     * graceful degradation (external player launches without subtitles).
     */
    suspend fun fetchForExternalPlayer(
        type: String,
        videoId: String,
        preferredLanguage: String,
        secondaryLanguage: String?,
        timeoutMs: Long = 10_000L,
        contentId: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ): List<SubtitleInput>? {
        return try {
            withTimeoutOrNull(timeoutMs) {
                val lookup = SubtitleLookup(type, videoId, contentId, season = season, episode = episode)
                val allSubtitles = loadAddonSubtitles(addonSubtitleRequests(lookup.resolve()))

                val filtered = allSubtitles.filter { subtitle ->
                    languageMatchesPreference(subtitle.language, preferredLanguage) ||
                        (secondaryLanguage != null &&
                            languageMatchesPreference(subtitle.language, secondaryLanguage))
                }

                filtered.map { subtitle ->
                    SubtitleInput(
                        url = subtitle.url,
                        name = subtitle.display,
                        lang = subtitle.language,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
