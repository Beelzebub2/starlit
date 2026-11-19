package com.nuvio.app.features.lists

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.nuvio.app.features.myanimelist.MAX_MAL_EPISODES
import com.nuvio.app.features.myanimelist.withCompletedRewatch
import com.nuvio.app.features.myanimelist.withStatus
import com.nuvio.app.features.myanimelist.withWatchedEpisodes
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What [change] is expected to do to [entry], shown at once while the write is on its way. MyAnimeList follows its
 * own list rules (Completed fills every episode, the first episode starts watching, the last one completes); the
 * service's answer replaces the preview when the write finishes.
 */
internal fun previewListChange(entry: ListEntry, change: ListChange): ListEntry {
    if (entry.key.service == ListServiceId.MYANIMELIST) {
        val current = entry.malListStatus()
        val next = when (change) {
            is ListChange.SetStatus -> current.withStatus(change.status.toMalStatus(), entry.totalEpisodes)
            is ListChange.StepEpisodes -> current.withWatchedEpisodes(current.watchedEpisodes + change.delta, entry.totalEpisodes)
            is ListChange.SetScore -> current.copy(score = change.score)
            is ListChange.SetRewatching -> current.copy(isRewatching = change.on)
            ListChange.FinishRewatch -> current.withCompletedRewatch()
            is ListChange.LeaveStatus -> current
        }
        return entry.withMalListStatus(next)
    }
    val maxEpisodes = if (entry.totalEpisodes > 0) entry.totalEpisodes else MAX_MAL_EPISODES
    return when (change) {
        is ListChange.SetStatus -> entry.copy(
            status = change.status,
            watchedEpisodes = if (change.status == ListStatus.COMPLETED && entry.totalEpisodes > 0) entry.totalEpisodes else entry.watchedEpisodes,
        )
        is ListChange.LeaveStatus -> if (entry.status == change.status) entry.copy(status = null) else entry
        is ListChange.SetScore -> entry.copy(score = change.score)
        is ListChange.StepEpisodes -> entry.copy(watchedEpisodes = (entry.watchedEpisodes + change.delta).coerceIn(0, maxEpisodes))
        is ListChange.SetRewatching -> entry.copy(rewatching = change.on)
        ListChange.FinishRewatch -> if (entry.rewatching) entry.copy(rewatching = false, rewatchCount = entry.rewatchCount + 1) else entry
    }
}

/**
 * Holds the entries shown in the lists tab and writes edits to the service. Every edit shows at once; edits to one
 * entry wait [debounceMillis] after the last one and are then written one at a time, each to the entry the previous
 * write returned (consecutive episode steps are sent as one step). A failed write rolls the entry back.
 *
 * Edits belong to [profileId]: if another Starlit profile is active when a write would start, the pending edits are
 * dropped instead of reaching the new profile's account. All calls are expected on one thread (the UI thread).
 */
internal class ListEditor(
    private val scope: CoroutineScope,
    val profileId: Int,
    private val apply: suspend (ListEntry, ListChange) -> ListEntry,
    private val debounceMillis: Long = 600,
    private val activeProfile: () -> Int = { ProfileRepository.activeProfileId },
    private val onFailure: (String) -> Unit = {},
    private val onCommitted: (before: ListEntry, after: ListEntry) -> Unit = { _, _ -> },
) {
    private class Edit(
        val change: ListChange?,
        val preview: (ListEntry) -> ListEntry,
        val write: suspend (ListEntry) -> ListEntry,
    )

    val entries = mutableStateListOf<ListEntry>()
    private val busy = mutableStateMapOf<ListEntryKey, Boolean>()
    /** The service's last known state of each entry being edited. */
    private val confirmed = HashMap<ListEntryKey, ListEntry>()
    private val queued = HashMap<ListEntryKey, ArrayDeque<Edit>>()
    /** Debounce timers; an entry in here has edits whose write has not started yet. */
    private val waiting = HashMap<ListEntryKey, Job>()
    private val locks = HashMap<ListEntryKey, Mutex>()

    fun entry(key: ListEntryKey): ListEntry? = entries.firstOrNull { it.key == key }
    fun isSaving(key: ListEntryKey): Boolean = busy[key] == true
    val hasPendingWork: Boolean get() = busy.isNotEmpty()

    /** Shows a page from the service. Entries with unsaved edits keep the edited values. */
    fun show(page: List<ListEntry>, append: Boolean) {
        if (!append) entries.clear()
        val seen = entries.mapTo(HashSet()) { it.key }
        page.forEach { entry ->
            // Paging can repeat a title when the list changes between pages.
            if (!seen.add(entry.key)) return@forEach
            entries += if (entry.key in busy) displayed(entry.key, confirmed[entry.key] ?: entry) else entry
        }
    }

    fun edit(key: ListEntryKey, change: ListChange) =
        enqueue(key, Edit(change, preview = { previewListChange(it, change) }, write = { apply(it, change) }))

    /** An edit that is not a single [ListChange] (e.g. MyAnimeList's full entry editor), queued like any other. */
    fun edit(key: ListEntryKey, preview: (ListEntry) -> ListEntry, write: suspend (ListEntry) -> ListEntry) =
        enqueue(key, Edit(change = null, preview = preview, write = write))

    private fun enqueue(key: ListEntryKey, edit: Edit) {
        val index = entries.indexOfFirst { it.key == key }
        if (index < 0) return
        val shown = entries[index]
        // A change that would not alter the entry (e.g. picking the current status) is not sent.
        if (edit.change != null && edit.preview(shown) == shown) return
        confirmed.getOrPut(key) { shown }
        queued.getOrPut(key) { ArrayDeque() }.addLast(edit)
        entries[index] = edit.preview(shown)
        busy[key] = true
        waiting.remove(key)?.cancel()
        waiting[key] = scope.launch {
            delay(debounceMillis)
            // A newer edit would have cancelled this timer before it got here.
            waiting.remove(key)
            // Once writing starts, finish it even if the screen closes, so the service and the UI agree.
            withContext(NonCancellable) { commit(key) }
        }
    }

    /** Writes edits still waiting on their timer, e.g. when the tab closes. Edits from another profile are dropped. */
    fun flush(launch: (suspend () -> Unit) -> Unit) {
        waiting.keys.toList().forEach { key ->
            waiting.remove(key)?.cancel()
            launch { commit(key) }
        }
    }

    /**
     * Runs [block] on the entry once its pending edits are written, so it never races them. Returns null when the
     * entry is gone or the profile changed; [block]'s own failure is thrown to the caller.
     */
    suspend fun <T> afterEdits(key: ListEntryKey, block: suspend (ListEntry) -> T): T? {
        waiting.remove(key)?.cancel()
        commit(key)
        return lockFor(key).withLock {
            if (activeProfile() != profileId) return@withLock null
            val entry = confirmed[key] ?: entry(key) ?: return@withLock null
            block(entry)
        }
    }

    /** Removes the entry from the service's list with [delete], then from the tab. */
    fun remove(key: ListEntryKey, delete: suspend (ListEntry) -> Unit, onRemoved: () -> Unit = {}) {
        if (entry(key) == null) return
        waiting.remove(key)?.cancel()
        busy[key] = true
        scope.launch {
            withContext(NonCancellable) {
                lockFor(key).withLock {
                    if (activeProfile() != profileId) {
                        drop(key)
                        return@withLock
                    }
                    val target = confirmed[key] ?: entry(key)
                    if (target == null) {
                        busy.remove(key)
                        return@withLock
                    }
                    try {
                        delete(target)
                        entries.removeAll { it.key == key }
                        queued.remove(key); confirmed.remove(key); busy.remove(key)
                        onRemoved()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        busy.remove(key)
                        onFailure(e.readableMessage())
                    }
                }
                // A removal that failed must not drop an edit it interrupted.
                if (queued[key]?.isNotEmpty() == true && entry(key) != null) commit(key)
            }
        }
    }

    private fun lockFor(key: ListEntryKey) = locks.getOrPut(key) { Mutex() }

    private suspend fun commit(key: ListEntryKey) = lockFor(key).withLock {
        while (true) {
            // A newer edit restarted the timer: let it write once the user has paused.
            if (key in waiting) break
            val queue = queued[key] ?: break
            if (queue.isEmpty()) break
            if (activeProfile() != profileId) {
                drop(key)
                return@withLock
            }
            val edit = queue.removeFirst().let { first ->
                val step = first.change as? ListChange.StepEpisodes ?: return@let first
                var delta = step.delta
                while ((queue.firstOrNull()?.change as? ListChange.StepEpisodes) != null) {
                    delta += (queue.removeFirst().change as ListChange.StepEpisodes).delta
                }
                val merged = ListChange.StepEpisodes(delta)
                if (delta == 0) null else Edit(merged, { previewListChange(it, merged) }, { apply(it, merged) })
            } ?: continue
            val base = confirmed[key] ?: break
            try {
                val result = edit.write(base)
                confirmed[key] = result
                onCommitted(base, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Later edits were made on top of the one that failed; roll them back with it.
                queue.clear()
                waiting.remove(key)?.cancel()
                onFailure(e.readableMessage())
                break
            }
        }
        settle(key)
    }

    /** Shows the confirmed entry plus any edits still queued; clears the saving state once nothing is left. */
    private fun settle(key: ListEntryKey) {
        val base = confirmed[key] ?: return
        val idle = queued[key].isNullOrEmpty() && key !in waiting
        val index = entries.indexOfFirst { it.key == key }
        if (index >= 0) entries[index] = displayed(key, base)
        if (idle) {
            busy.remove(key)
            confirmed.remove(key)
            queued.remove(key)
        }
    }

    private fun displayed(key: ListEntryKey, base: ListEntry): ListEntry =
        queued[key].orEmpty().fold(base) { entry, edit -> edit.preview(entry) }

    /** Forgets edits made in a profile that is no longer active and shows the last confirmed state. */
    private fun drop(key: ListEntryKey) {
        waiting.remove(key)?.cancel()
        queued.remove(key)
        busy.remove(key)
        val base = confirmed.remove(key) ?: return
        val index = entries.indexOfFirst { it.key == key }
        if (index >= 0) entries[index] = base
    }
}

internal fun Exception.readableMessage(): String = message?.takeIf { it.isNotBlank() } ?: (this::class.simpleName ?: "Error")
