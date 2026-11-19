package com.nuvio.app.features.mdblist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MdbListDroppedServiceTest {
    @Test
    fun payloadNamesTheShowAndOnlyAddsADropDateWhenDropping() {
        val ids = MdbListIds(imdb = "tt0903747", tmdb = 1396)
        assertEquals(
            """{"shows":[{"ids":{"imdb":"tt0903747","tmdb":1396},"dropped_at":"2025-10-15T20:00:00Z"}]}""",
            mdbListDroppedPayload(ids, "2025-10-15T20:00:00Z"),
        )
        assertEquals(
            """{"shows":[{"ids":{"imdb":"tt0903747","tmdb":1396}}]}""",
            mdbListDroppedPayload(ids, null),
        )
    }

    @Test
    fun notFoundAndErrorsAreCounted() {
        assertEquals(0, mdbListDroppedNotFound("""{"updated":{"shows":1},"not_found":{"shows":[]},"errors":[]}"""))
        assertEquals(1, mdbListDroppedNotFound("""{"updated":{"shows":0},"not_found":{"shows":[{"ids":{"imdb":"tt1"}}]}}"""))
        assertEquals(2, mdbListDroppedNotFound("""{"not_found":{"shows":1},"errors":["bad"]}"""))
        assertFailsWith<MdbListDecodingException> { mdbListDroppedNotFound("[]") }
    }
}
