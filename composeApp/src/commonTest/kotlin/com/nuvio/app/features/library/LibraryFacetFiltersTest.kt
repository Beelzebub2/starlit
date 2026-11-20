package com.nuvio.app.features.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryFacetFiltersTest {
    private fun item(
        id: String,
        genre: String,
        releaseInfo: String,
    ) = LibraryItem(
        id = id,
        type = "movie",
        name = id,
        genres = listOf(genre),
        releaseInfo = releaseInfo,
        savedAtEpochMs = 0,
    )

    private val sections = listOf(
        LibrarySection("local", "Library", listOf(
            item("seen", "Action", "2022"),
            item("unseen", "Drama", "2023"),
            item("also-seen", "Action", "2023"),
        )),
    )

    private fun projection(
        genre: String? = null,
        year: String? = null,
        watched: LibraryWatchedFilter = LibraryWatchedFilter.ALL,
    ) = buildLibraryVerticalProjection(
        sections = sections,
        sourceMode = LibrarySourceMode.LOCAL,
        selectedSectionKey = null,
        selectedType = null,
        sortOption = LibrarySortOption.ADDED_DESC,
        selectedGenre = genre,
        selectedYear = year,
        watchedFilter = watched,
        isWatched = { it.id in setOf("seen", "also-seen") },
    )

    @Test fun genreAndYearCombineAsAnIntersection() {
        assertEquals(listOf("also-seen"), projection("Action", "2023").entries.map { it.item.id })
    }

    @Test fun watchedFilterPreservesOnlyWatchedEntries() {
        assertEquals(setOf("seen", "also-seen"), projection(watched = LibraryWatchedFilter.WATCHED).entries.map { it.item.id }.toSet())
        assertEquals(listOf("unseen"), projection(watched = LibraryWatchedFilter.UNWATCHED).entries.map { it.item.id })
    }

    @Test fun filtersExposeCountsAndDoNotSilentlyRemoveAllOptions() {
        val selection = projection("Action", "2023")
        assertEquals(2, selection.availableGenres.first { it.key == "Action" }.count)
        assertEquals(1, selection.availableYears.first { it.key == "2023" }.count)
        assertEquals("Action", selection.selectedGenre)
        assertEquals("2023", selection.selectedYear)
    }

    @Test fun staleFilterValuesAreIgnored() {
        assertEquals(3, projection("Nonexistent", "1899").entries.size)
        assertEquals(null, projection("Nonexistent").selectedGenre)
    }
}
