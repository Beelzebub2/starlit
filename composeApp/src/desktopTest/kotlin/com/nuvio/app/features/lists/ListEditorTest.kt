package com.nuvio.app.features.lists

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ListEditorTest {
    private fun entry(id: String, status: ListStatus? = ListStatus.WATCHING, score: Int = 0, watched: Int = 0, total: Int = 12) = ListEntry(
        key = ListEntryKey(ListServiceId.TRAKT, id), title = "Title $id", subtitle = null, posterUrl = null,
        type = ListMediaType.SERIES, anime = false, facts = emptyList(), communityScore = null, status = status,
        score = score, watchedEpisodes = watched, totalEpisodes = total, airing = false, rewatching = false,
        rewatchCount = 0, catalogType = "series", catalogId = "tt$id", siteUrl = null,
    )

    private class Write(val entry: ListEntry, val change: ListChange)

    /** A service that records every write and answers like a list service would. */
    private class Server(var fail: (ListChange) -> Boolean = { false }, val latencyMillis: Long = 0) {
        val writes = mutableListOf<Write>()
        suspend fun apply(entry: ListEntry, change: ListChange): ListEntry {
            writes += Write(entry, change)
            if (latencyMillis > 0) delay(latencyMillis)
            if (fail(change)) throw IOException("The service returned HTTP 500")
            return previewListChange(entry, change)
        }
    }

    private fun TestScope.editor(
        server: Server,
        debounceMillis: Long = 600,
        profile: () -> Int = { 1 },
        failures: MutableList<String> = mutableListOf(),
        scope: CoroutineScope = this,
        committed: MutableList<ListChange?> = mutableListOf(),
    ) = ListEditor(
        scope = scope,
        profileId = 1,
        apply = server::apply,
        debounceMillis = debounceMillis,
        activeProfile = profile,
        onFailure = { failures += it },
        onCommitted = { _, _, change -> committed += change },
    )

    private val key1 = ListEntryKey(ListServiceId.TRAKT, "1")

    @Test fun rapidStepsAreShownAtOnceAndWrittenAsOneStep() = runTest {
        val server = Server()
        val editor = editor(server, debounceMillis = 400)
        editor.show(listOf(entry("1", watched = 2)), append = false)

        repeat(3) { editor.edit(key1, ListChange.StepEpisodes(1)) }
        assertEquals(5, editor.entries.single().watchedEpisodes)
        assertTrue(editor.isSaving(key1))
        advanceTimeBy(300)
        assertTrue(server.writes.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf<ListChange>(ListChange.StepEpisodes(3)), server.writes.map { it.change })
        assertEquals(2, server.writes.single().entry.watchedEpisodes)
        assertEquals(5, editor.entries.single().watchedEpisodes)
        assertFalse(editor.isSaving(key1))
        assertFalse(editor.hasPendingWork)
    }

    @Test fun failedWriteRollsBackTheEntryAndItsQueuedEdits() = runTest {
        val failures = mutableListOf<String>()
        val server = Server(fail = { it is ListChange.SetScore })
        val editor = editor(server, failures = failures)
        editor.show(listOf(entry("1", score = 6, watched = 2)), append = false)

        editor.edit(key1, ListChange.SetScore(9))
        editor.edit(key1, ListChange.StepEpisodes(1))
        assertEquals(9, editor.entries.single().score)
        assertEquals(3, editor.entries.single().watchedEpisodes)
        advanceUntilIdle()

        // The step was made on top of the failed score, so it is rolled back and never written.
        assertEquals(listOf<ListChange>(ListChange.SetScore(9)), server.writes.map { it.change })
        assertEquals(6, editor.entries.single().score)
        assertEquals(2, editor.entries.single().watchedEpisodes)
        assertEquals(listOf("The service returned HTTP 500"), failures)
        assertFalse(editor.isSaving(key1))
    }

    @Test fun editMadeWhileWritingIsWrittenAfterwardsFromTheReturnedEntry() = runTest {
        val server = Server(latencyMillis = 1_000)
        val editor = editor(server, debounceMillis = 100)
        editor.show(listOf(entry("1")), append = false)

        editor.edit(key1, ListChange.SetScore(8))
        advanceTimeBy(150) // first write in flight
        editor.edit(key1, ListChange.StepEpisodes(1))
        advanceTimeBy(500) // the first answer hasn't arrived; both edits stay visible
        assertEquals(8, editor.entries.single().score)
        assertEquals(1, editor.entries.single().watchedEpisodes)

        advanceUntilIdle()
        assertEquals(listOf(ListChange.SetScore(8), ListChange.StepEpisodes(1)), server.writes.map { it.change })
        // The second write starts from the entry the first one returned.
        assertEquals(8, server.writes[1].entry.score)
        assertEquals(8, editor.entries.single().score)
        assertEquals(1, editor.entries.single().watchedEpisodes)
        assertFalse(editor.hasPendingWork)
    }

    @Test fun reloadKeepsUnsavedEditsAndSkipsRepeatedTitles() = runTest {
        val editor = editor(Server())
        editor.show(listOf(entry("1"), entry("2")), append = false)
        editor.edit(key1, ListChange.SetScore(10))

        // A refresh arrives before the write went out: the edit must still be visible and written.
        editor.show(listOf(entry("1"), entry("3")), append = false)
        assertEquals(10, editor.entry(key1)!!.score)
        editor.show(listOf(entry("3"), entry("4")), append = true)
        assertEquals(listOf("1", "3", "4"), editor.entries.map { it.key.id })

        advanceUntilIdle()
        assertEquals(10, editor.entry(key1)!!.score)
    }

    @Test fun flushWritesAnEditWhoseTimerWasCancelled() = runTest {
        val server = Server()
        val screen = CoroutineScope(coroutineContext + Job())
        val editor = editor(server, scope = screen)
        editor.show(listOf(entry("1")), append = false)
        editor.edit(key1, ListChange.SetStatus(ListStatus.DROPPED))

        // The screen closes: its scope (and the debounce timer) is cancelled before the write started.
        screen.coroutineContext[Job]!!.cancel()
        advanceUntilIdle()
        assertTrue(server.writes.isEmpty())

        editor.flush { write -> launch { write() } }
        advanceUntilIdle()
        assertEquals(listOf<ListChange>(ListChange.SetStatus(ListStatus.DROPPED)), server.writes.map { it.change })
    }

    @Test fun removeDeletesOnSuccessAndKeepsTheTitleOnFailure() = runTest {
        var fail = true
        val deleted = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var removed = 0
        val editor = editor(Server(), failures = failures)
        editor.show(listOf(entry("1"), entry("2")), append = false)
        val delete: suspend (ListEntry) -> Unit = {
            if (fail) throw IOException("offline")
            deleted += it.key.id
        }

        editor.remove(key1, delete) { removed++ }
        runCurrent()
        assertEquals(listOf("1", "2"), editor.entries.map { it.key.id })
        assertEquals(listOf("offline"), failures)
        assertEquals(0, removed)
        assertFalse(editor.hasPendingWork)

        fail = false
        editor.remove(key1, delete) { removed++ }
        advanceUntilIdle()
        assertEquals(listOf("2"), editor.entries.map { it.key.id })
        assertEquals(listOf("1"), deleted)
        assertEquals(1, removed)
        assertFalse(editor.hasPendingWork)
    }

    @Test fun failedRemovalStillWritesTheEditItInterrupted() = runTest {
        val server = Server()
        val failures = mutableListOf<String>()
        val editor = editor(server, failures = failures)
        editor.show(listOf(entry("1", score = 4)), append = false)

        editor.edit(key1, ListChange.SetScore(9))
        // The removal cancels the edit's timer, then fails.
        editor.remove(key1, delete = { throw IOException("offline") })
        advanceUntilIdle()

        assertEquals(listOf("offline"), failures)
        assertEquals(listOf<ListChange>(ListChange.SetScore(9)), server.writes.map { it.change })
        assertEquals(9, editor.entries.single().score)
        assertFalse(editor.hasPendingWork)
    }

    @Test fun removalLaunchedElsewhereStillRuns() = runTest {
        val editor = editor(Server(), scope = CoroutineScope(coroutineContext + Job()).also { it.coroutineContext[Job]!!.cancel() })
        editor.show(listOf(entry("1")), append = false)
        val deleted = mutableListOf<String>()
        // The screen's scope is gone; the removal runs in the launcher it is given.
        editor.remove(key1, delete = { deleted += it.key.id }, launch = { block -> launch { block() } })
        advanceUntilIdle()
        assertEquals(listOf("1"), deleted)
        assertTrue(editor.entries.isEmpty())
    }

    @Test fun afterEditsRunsOnceThePendingEditsAreWritten() = runTest {
        val order = mutableListOf<String>()
        val server = Server()
        val editor = ListEditor(
            scope = this, profileId = 1,
            apply = { e, c -> order += "write"; server.apply(e, c) },
            activeProfile = { 1 },
        )
        editor.show(listOf(entry("1", score = 2)), append = false)
        editor.edit(key1, ListChange.SetScore(7))

        val result = async { editor.afterEdits(key1) { current -> order += "block:${current.score}"; 42 } }
        advanceUntilIdle()
        assertEquals(listOf("write", "block:7"), order)
        assertEquals(42, result.await())
    }

    @Test fun editsLeftPendingWhenTheProfileChangesAreDropped() = runTest {
        var profile = 1
        val server = Server()
        val editor = editor(server, profile = { profile })
        editor.show(listOf(entry("1", score = 3)), append = false)
        editor.edit(key1, ListChange.SetScore(9))

        profile = 2
        advanceUntilIdle()
        assertTrue(server.writes.isEmpty())
        assertEquals(3, editor.entries.single().score)
        assertFalse(editor.hasPendingWork)
        assertNull(editor.afterEdits<String>(key1) { "never" })
    }

    @Test fun commitsReportTheChangeTheyWrote() = runTest {
        val committed = mutableListOf<ListChange?>()
        val editor = editor(Server(), committed = committed)
        editor.show(listOf(entry("1")), append = false)
        editor.edit(key1, ListChange.SetScore(5))
        advanceUntilIdle()
        editor.edit(key1, preview = { it.copy(score = 6) }, write = { it.copy(score = 6) })
        advanceUntilIdle()
        assertEquals(listOf<ListChange?>(ListChange.SetScore(5), null), committed)
    }
}
