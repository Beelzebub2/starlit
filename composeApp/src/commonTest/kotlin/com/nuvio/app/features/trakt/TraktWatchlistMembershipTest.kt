package com.nuvio.app.features.trakt

import com.nuvio.app.features.library.LibraryItem
import kotlin.test.Test
import kotlin.test.assertEquals

class TraktWatchlistMembershipTest {
    private val watchlist = TraktListTab(key = "trakt:watchlist", title = "Watchlist", type = TraktListType.WATCHLIST)
    private val favourites = TraktListTab(key = "trakt:list:1", title = "Favourites", type = TraktListType.PERSONAL)
    private val later = TraktListTab(key = "trakt:list:2", title = "Later", type = TraktListType.PERSONAL)
    private val show = LibraryItem(id = "tt1", type = "series", name = "Show", imdbId = "tt1", savedAtEpochMs = 10L)
    private val other = LibraryItem(id = "tt2", type = "series", name = "Other", imdbId = "tt2", savedAtEpochMs = 20L)

    @Test
    fun `watchlist only membership keeps every other list exactly as it is`() {
        val tabKeys = listOf(watchlist.key, favourites.key, later.key)

        val added = watchlistOnlyMembership(
            tabKeys = tabKeys,
            membership = setOf(favourites.key, "trakt:list:9"),
            watchlistKey = watchlist.key,
            inWatchlist = true,
        )
        assertEquals(
            mapOf(watchlist.key to true, favourites.key to true, later.key to false, "trakt:list:9" to true),
            added,
        )

        val removed = watchlistOnlyMembership(
            tabKeys = tabKeys,
            membership = setOf(watchlist.key, favourites.key),
            watchlistKey = watchlist.key,
            inWatchlist = false,
        )
        assertEquals(mapOf(watchlist.key to false, favourites.key to true, later.key to false), removed)
    }

    @Test
    fun `adding to and removing from the watchlist leaves personal lists untouched`() {
        val state = TraktLibraryRepository.rebuildUiState(
            listTabs = listOf(watchlist, favourites, later),
            entriesByList = mapOf(favourites.key to listOf(show, other), later.key to listOf(other)),
        )

        val added = TraktLibraryRepository.watchlistOnlyState(state, show, inWatchlist = true)
        assertEquals(listOf("tt1"), added.entriesByList.getValue(watchlist.key).map(LibraryItem::id))
        assertEquals(setOf("tt1", "tt2"), added.entriesByList.getValue(favourites.key).map(LibraryItem::id).toSet())
        assertEquals(listOf("tt2"), added.entriesByList.getValue(later.key).map(LibraryItem::id))

        val removed = TraktLibraryRepository.watchlistOnlyState(added, show, inWatchlist = false)
        assertEquals(emptyList<String>(), removed.entriesByList.getValue(watchlist.key).map(LibraryItem::id))
        assertEquals(setOf("tt1", "tt2"), removed.entriesByList.getValue(favourites.key).map(LibraryItem::id).toSet())
        assertEquals(listOf("tt2"), removed.entriesByList.getValue(later.key).map(LibraryItem::id))
    }
}
