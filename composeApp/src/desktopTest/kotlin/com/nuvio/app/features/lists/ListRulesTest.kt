package com.nuvio.app.features.lists

import com.nuvio.app.features.details.MetaDetails
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListRulesTest {
    @Test fun titleKindUsesMetaTypeAndAnimeFlag() {
        val anime = MetaDetails(id = "tt1", type = "series", name = "A", genres = listOf("Anime"))
        assertEquals(ListTitleKind(ListMediaType.SERIES, anime = true), titleKind(anime, malResolved = false))
        val movie = MetaDetails(id = "tt2", type = "movie", name = "M")
        assertEquals(ListTitleKind(ListMediaType.MOVIE, anime = false), titleKind(movie, malResolved = false))
        assertEquals(ListTitleKind(ListMediaType.MOVIE, anime = true), titleKind(movie, malResolved = true))
    }

    @Test fun derivedStatusPrecedence() {
        assertEquals(ListStatus.DROPPED, derivedHistoryStatus(true, true, 5, 12, false))
        assertEquals(ListStatus.COMPLETED, derivedHistoryStatus(true, false, 12, 12, false))
        assertEquals(ListStatus.WATCHING, derivedHistoryStatus(false, false, 3, 12, false))
        assertEquals(ListStatus.PLAN_TO_WATCH, derivedHistoryStatus(true, false, 0, 12, false))
        assertNull(derivedHistoryStatus(false, false, 0, 12, false))
        assertEquals(ListStatus.COMPLETED, derivedHistoryStatus(false, false, 1, 1, true))
    }
}
