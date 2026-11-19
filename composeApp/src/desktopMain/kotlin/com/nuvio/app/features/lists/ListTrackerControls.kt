package com.nuvio.app.features.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.mdblist.MdbListTracker
import com.nuvio.app.features.myanimelist.MalTitleLinkHost
import com.nuvio.app.features.myanimelist.MyAnimeListRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_continue
import nuvio.composeapp.generated.resources.lists_confirm_dropped_hides
import nuvio.composeapp.generated.resources.lists_confirm_episodes
import nuvio.composeapp.generated.resources.lists_confirm_hides
import nuvio.composeapp.generated.resources.lists_confirm_title
import nuvio.composeapp.generated.resources.lists_updated
import org.jetbrains.compose.resources.stringResource

/**
 * MyAnimeList-only pieces the generic tracker needs. The MAL feature supplies them together with its own
 * setup and search dialogs (see [MalTitleLinkHost]); other services identify titles by ids and need none of this.
 */
internal class MalTitleHooks(
    val connected: Boolean,
    val busy: Boolean,
    /** Bumped when the user links a MAL entry, so the id is resolved again. */
    val linkRevision: Int,
    val resolveId: suspend (MetaDetails) -> Int?,
    val onConnect: () -> Unit,
    val onChooseEntry: () -> Unit,
    /** MAL connection generation; a reconnect resolves the id again. */
    val revision: Long = 0,
)

/** Desktop detail-hero tracker for the title: the user's first candidate list service, switchable per page view. */
@Composable internal fun ListTrackerControls(meta: MetaDetails) {
    val profile by ProfileRepository.state.collectAsState()
    LaunchedEffect(profile.activeProfile) { ensureListServicesLoaded() }
    val prefs by ListPreferencesRepository.state.collectAsState()
    MalTitleLinkHost(meta) { mal ->
        key(ProfileRepository.activeProfileId) { ListTrackerControls(meta, mal, ListServices.all(), prefs) }
    }
}

internal fun ensureListServicesLoaded() {
    ListPreferencesRepository.ensureLoaded()
    MyAnimeListRepository.ensureLoaded()
    SimklAuthRepository.ensureLoaded()
    TraktAuthRepository.ensureLoaded()
    MdbListTracker.ensureLoaded()
}

/** Testable body of [ListTrackerControls]: [services] and [prefs] are injected, no live services are touched. */
@Composable internal fun ListTrackerControls(
    meta: MetaDetails,
    mal: MalTitleHooks,
    services: List<ListService>,
    prefs: ListPreferences,
) {
    // Subscribe to every service's connection so a sign-out elsewhere re-picks the candidates.
    services.forEach { it.connected.collectAsState().value }
    var malId by remember(meta.type, meta.id) { mutableStateOf<Int?>(null) }
    var malResolving by remember(meta.type, meta.id) { mutableStateOf(true) }
    LaunchedEffect(meta.type, meta.id, mal.linkRevision, mal.revision) {
        malResolving = true
        malId = try { mal.resolveId(meta) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        malResolving = false
    }
    val kind = titleKind(meta, malResolved = malId != null)
    val candidates = titleCandidates(services, prefs, kind, meta)
    // Page-local choice; when the chosen service disconnects it drops out of the candidates and the next one shows.
    var chosen by remember(meta.type, meta.id) { mutableStateOf<ListServiceId?>(null) }
    val current = candidates.firstOrNull { it.id == chosen } ?: candidates.firstOrNull()
    if (current == null) {
        if (kind.anime && !mal.connected) ListConnectPrompt(ListServiceId.MYANIMELIST, mal.onConnect, enabled = !mal.busy)
        return
    }
    key(meta.type, meta.id, current.id) {
        ListServiceTracker(
            meta = meta,
            kind = kind,
            service = current,
            otherServices = candidates.filter { it.id != current.id }.map { it.id },
            mal = mal,
            malId = malId,
            malResolving = malResolving,
            onSwitch = { chosen = it },
        )
    }
}

private class PendingChange(val target: ListEntry, val change: ListChange, val impact: ListChangeImpact)

internal fun ListChangeImpact.needsConfirmation(): Boolean = episodesMarked > 1 || hidesFromContinueWatching

/** The entry a change applies to when the title is not on the service's list yet. */
internal fun newListEntryFor(service: ListServiceId, meta: MetaDetails, kind: ListTitleKind, malId: Int?): ListEntry = ListEntry(
    key = ListEntryKey(service, if (service == ListServiceId.MYANIMELIST && malId != null) malId.toString() else "${meta.type}:${meta.id}"),
    title = meta.name,
    subtitle = null,
    posterUrl = meta.poster,
    type = kind.type,
    anime = kind.anime,
    facts = emptyList(),
    communityScore = null,
    status = null,
    score = 0,
    watchedEpisodes = 0,
    totalEpisodes = 0,
    airing = false,
    rewatching = false,
    rewatchCount = 0,
    catalogType = meta.type,
    catalogId = meta.id,
    siteUrl = null,
)

@Composable private fun ListServiceTracker(
    meta: MetaDetails,
    kind: ListTitleKind,
    service: ListService,
    otherServices: List<ListServiceId>,
    mal: MalTitleHooks,
    malId: Int?,
    malResolving: Boolean,
    onSwitch: (ListServiceId) -> Unit,
) {
    val isMal = service.id == ListServiceId.MYANIMELIST
    val waitingForMal = isMal && malResolving
    val linked = !isMal || malId != null
    val scope = rememberCoroutineScope()
    var entry by remember { mutableStateOf<ListEntry?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingChange?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val updatedText = stringResource(Res.string.lists_updated, service.id.displayName)

    LaunchedEffect(malId, waitingForMal, reload) {
        if (waitingForMal) return@LaunchedEffect
        if (!linked) {
            entry = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            entry = service.entryFor(meta)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.readableMessage()
        } finally {
            loading = false
        }
    }

    val working = loading || busy || waitingForMal || (isMal && mal.busy)

    suspend fun commit(target: ListEntry, change: ListChange) {
        entry = service.apply(target, change)
        NuvioToastController.show(updatedText)
    }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.readableMessage()
            } finally {
                busy = false
            }
        }
    }

    fun request(change: ListChange) {
        if (working) return
        val target = entry ?: newListEntryFor(service.id, meta, kind, malId)
        run {
            // If the impact cannot be worked out the change is not applied; the error shows inline.
            val impact = service.impact(target, change)
            if (impact.needsConfirmation()) pending = PendingChange(target, change, impact) else commit(target, change)
        }
    }

    ListTrackerBar(
        service = service.id,
        otherServices = otherServices,
        capabilities = service.capabilities(entry?.type ?: kind.type),
        entry = entry,
        working = working,
        error = error,
        onSwitch = onSwitch,
        onChange = ::request,
        onChooseEntry = if (service.capabilities(kind.type).changeEntry) mal.onChooseEntry else null,
        onRetry = { error = null; reload++ },
        linked = linked,
    )

    pending?.let { confirm ->
        ListChangeConfirmation(
            service = service.id,
            change = confirm.change,
            impact = confirm.impact,
            onConfirm = {
                pending = null
                run { commit(confirm.target, confirm.change) }
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable internal fun ListChangeConfirmation(
    service: ListServiceId,
    change: ListChange,
    impact: ListChangeImpact,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.lists_confirm_title, service.displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (impact.episodesMarked > 1) Text(stringResource(Res.string.lists_confirm_episodes, impact.episodesMarked))
                if (impact.hidesFromContinueWatching) {
                    Text(
                        stringResource(
                            if (change == ListChange.SetStatus(ListStatus.DROPPED)) Res.string.lists_confirm_dropped_hides
                            else Res.string.lists_confirm_hides,
                        ),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.action_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

