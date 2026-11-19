package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    private val show = TrackingMediaReference(TrackingMediaKind.SHOW, "Show", ids = TrackingExternalIds(imdb = "tt7", tmdb = 7))

    private fun service(harness: MdbListSyncTestHarness) =
        MdbListDroppedService(harness.http.api, harness.repository) { mdbListTimestamp(MDBLIST_TEST_TIME) }

    @Test
    fun dropPostsToDroppedAndRecordsTheShow() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.http.reply(body = """{"updated":{"shows":1},"not_found":{}}""")

        service(harness).set(harness.repository.currentScope(), show, dropped = true)

        val request = harness.http.engine.requests.single()
        assertEquals("/sync/dropped", request.path)
        val sent = mdbListResponseElement(request.body).objectValue().arrayValue("shows").single().objectValue()
        assertEquals("tt7", sent.objectValue("ids")?.text("imdb"))
        assertEquals(7L, sent.objectValue("ids")?.number("tmdb"))
        assertEquals(MDBLIST_TEST_TIME.let { mdbListTimestamp(it) }, mdbListTimestamp(sent.text("dropped_at")!!))
        val dropped = harness.repository.currentSnapshot()!!.dropped
        assertEquals(listOf("tt7"), dropped.map { it.ids.imdb })
        assertTrue(dropped.single().season == null)
    }

    @Test
    fun undropPostsToRemoveAndClearsTheShowIgnoringNotFound() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed(
            harness.snapshot().copy(
                dropped = listOf(MdbListDroppedRecord(MdbListIds(imdb = "tt7", tmdb = 7)), MdbListDroppedRecord(MdbListIds(imdb = "tt8")))
            )
        )
        harness.http.reply(body = """{"deleted":{"shows":0},"not_found":{"shows":[{"ids":{"imdb":"tt7"}}]}}""")

        service(harness).set(harness.repository.currentScope(), show, dropped = false)

        val request = harness.http.engine.requests.single()
        assertEquals("/sync/dropped/remove", request.path)
        assertTrue(mdbListResponseElement(request.body).objectValue().arrayValue("shows").single().objectValue().get("dropped_at") == null)
        assertEquals(listOf("tt8"), harness.repository.currentSnapshot()!!.dropped.map { it.ids.imdb })
    }

    @Test
    fun dropNotFoundThrowsAndLeavesTheCachedDroppedSetAlone() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.http.reply(body = """{"updated":{"shows":0},"not_found":{"shows":[{"ids":{"imdb":"tt7"}}]}}""")

        assertFailsWith<IllegalStateException> {
            service(harness).set(harness.repository.currentScope(), show, dropped = true)
        }
        assertTrue(harness.repository.currentSnapshot()!!.dropped.isEmpty())
    }
}
