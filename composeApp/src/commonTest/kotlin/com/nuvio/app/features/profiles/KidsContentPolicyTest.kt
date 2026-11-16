package com.nuvio.app.features.profiles

import com.nuvio.app.features.home.HomeCatalogParser
import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KidsContentPolicyTest {
    @Test
    fun blocksRestrictedGenresAndAdultRatings() {
        assertTrue(KidsContentPolicy.isRestricted(genres = listOf("Horror")))
        assertTrue(KidsContentPolicy.isRestricted(genres = listOf("Comedy", "Ação")))
        assertTrue(KidsContentPolicy.isRestricted(genres = listOf("Crime, Thriller")))
        assertTrue(KidsContentPolicy.isRestricted(genres = emptyList(), ageRating = "TV-MA"))
        assertTrue(KidsContentPolicy.isRestricted(genres = emptyList(), ageRating = "12"))
        assertTrue(KidsContentPolicy.isRestricted(genres = emptyList(), ageRating = "PT:16"))
        assertTrue(KidsContentPolicy.isRestricted(genres = emptyList(), adult = true))
        assertTrue(KidsContentPolicy.isRestricted(MetaPreview(id = "horror", type = "movie", name = "Horror", genreIds = listOf(27))))
    }

    @Test
    fun keepsClearlyMildItemsAndDisablesFilteringForOtherProfiles() {
        assertFalse(KidsContentPolicy.isRestricted(genres = listOf("Animation", "Family"), ageRating = "G"))
        assertFalse(KidsContentPolicy.isRestricted(genres = listOf("Documentary"), ageRating = "TV-Y"))
        val safe = MetaPreview(id = "safe", type = "movie", name = "Family film", genres = listOf("Family"))
        val restricted = MetaPreview(id = "restricted", type = "movie", name = "Horror film", genres = listOf("Horror"))
        assertEquals(listOf(safe), KidsContentPolicy.filter(listOf(safe, restricted), enabled = true))
        assertEquals(listOf(safe, restricted), KidsContentPolicy.filter(listOf(safe, restricted), enabled = false))
    }

    @Test
    fun catalogMetadataCarriesAgeAndAdultFlags() {
        val catalog = HomeCatalogParser.parseCatalogResponse(
            """{"metas":[
                {"id":"mild","type":"movie","name":"Family film","genres":["Family"],"ageRating":"G"},
                {"id":"mature","type":"movie","name":"Mature film","behaviorHints":{"adult":true}},
                {"id":"rated","type":"series","name":"Rated show","app_extras":{"certification":"TV-MA"}}
            ]}""",
        )
        assertEquals(3, catalog.rawItemCount)
        assertEquals("G", catalog.items[0].ageRating)
        assertTrue(catalog.items[1].isAdult)
        assertEquals("TV-MA", catalog.items[2].ageRating)
        assertEquals(listOf("mild"), KidsContentPolicy.filter(catalog.items, enabled = true).map { it.id })
    }
}
