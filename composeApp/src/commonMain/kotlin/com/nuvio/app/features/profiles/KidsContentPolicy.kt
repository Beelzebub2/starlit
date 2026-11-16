package com.nuvio.app.features.profiles

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview

/**
 * Best-effort classification of provider metadata, not a parental lock or an
 * independent content-rating service. Unknown/unlabelled titles cannot be certified safe.
 */
object KidsContentPolicy {
    private val restrictedGenres = setOf(
        "horror", "terror", "horreur", "horrour", "thriller", "suspense", "crime", "criminal",
        "crimen", "krimi", "war", "war & politics", "guerra", "guerre", "krieg", "action",
        "acao", "ação", "accion", "acción", "adult", "adults only", "mature", "erotica",
        "erotic", "erotico", "erótico", "ecchi", "hentai", "gore", "slasher", "violent",
        "violence", "violencia", "violência", "sexual", "pornography", "pornographic",
    )
    private val restrictedRatings = setOf(
        "R", "NC-17", "TV-MA", "TV-14", "PG-13", "M", "MA", "MA15+", "M/14", "M/16", "M/18",
        "12A", "12", "12+", "M/12", "13", "14", "15", "16", "17", "18", "18+", "21", "21+", "AO",
    )
    // TMDB genres: Action, Crime, Horror, Thriller, War, Action & Adventure,
    // and War & Politics. Prefer genre IDs when a list lacks translated names.
    private val restrictedTmdbGenreIds = setOf(28, 80, 27, 53, 10752, 10759, 10768)

    fun isEnabled(): Boolean = ProfileRepository.state.value.activeProfile?.kidsContentFilterEnabled == true

    fun isRestricted(
        genres: List<String>,
        ageRating: String? = null,
        adult: Boolean = false,
    ): Boolean {
        if (adult) return true
        if (genres.any { raw ->
                raw.lowercase().split(',', ';', '/', '|').any { genre ->
                    genre.trim() in restrictedGenres
                }
            }) return true
        val rating = ageRating?.trim()?.uppercase().orEmpty()
        if (rating in restrictedRatings) return true
        val normalized = rating.substringAfterLast(':').trim()
        return normalized in restrictedRatings ||
            normalized.matches(Regex("(?:1[2-9]|2[0-9])\\+?"))
    }

    fun isRestricted(preview: MetaPreview): Boolean =
        isRestricted(preview.genres, preview.ageRating, preview.isAdult) ||
            preview.genreIds.any { it in restrictedTmdbGenreIds }

    fun isRestricted(meta: MetaDetails): Boolean =
        isRestricted(meta.genres, meta.ageRating, meta.isAdult)

    fun filter(items: List<MetaPreview>, enabled: Boolean = isEnabled()): List<MetaPreview> =
        if (enabled) items.filterNot(::isRestricted) else items
}
