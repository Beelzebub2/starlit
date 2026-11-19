package com.nuvio.app.features.lists

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedControl
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.SkeletonBlock
import com.nuvio.app.core.ui.desktopPageHorizontalPaddingForWidth
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_remove
import nuvio.composeapp.generated.resources.airing_action_refresh
import nuvio.composeapp.generated.resources.auth_passkey_retry
import nuvio.composeapp.generated.resources.lists_stat_episodes
import nuvio.composeapp.generated.resources.lists_stat_days_watched
import nuvio.composeapp.generated.resources.lists_stat_mean_score
import nuvio.composeapp.generated.resources.lists_stat_episodes_watched
import nuvio.composeapp.generated.resources.lists_stat_movies_watched
import nuvio.composeapp.generated.resources.lists_stat_rated_titles
import nuvio.composeapp.generated.resources.lists_sort_updated
import nuvio.composeapp.generated.resources.lists_sort_title
import nuvio.composeapp.generated.resources.lists_sort_score
import nuvio.composeapp.generated.resources.lists_sort_air_date
import nuvio.composeapp.generated.resources.lists_change_check_failed
import nuvio.composeapp.generated.resources.lists_disconnected_body
import nuvio.composeapp.generated.resources.lists_disconnected_title
import nuvio.composeapp.generated.resources.lists_empty_body
import nuvio.composeapp.generated.resources.lists_empty_status_body
import nuvio.composeapp.generated.resources.lists_empty_status_title
import nuvio.composeapp.generated.resources.lists_empty_title
import nuvio.composeapp.generated.resources.lists_filter_all
import nuvio.composeapp.generated.resources.lists_footer_titles
import nuvio.composeapp.generated.resources.lists_load_failed
import nuvio.composeapp.generated.resources.lists_load_failed_title
import nuvio.composeapp.generated.resources.lists_no_services_body
import nuvio.composeapp.generated.resources.lists_no_services_title
import nuvio.composeapp.generated.resources.lists_open_on
import nuvio.composeapp.generated.resources.lists_remove_entry_message
import nuvio.composeapp.generated.resources.lists_remove_entry_title
import nuvio.composeapp.generated.resources.lists_remove_history_message
import nuvio.composeapp.generated.resources.lists_remove_history_title
import nuvio.composeapp.generated.resources.lists_removed_from
import nuvio.composeapp.generated.resources.lists_removed_history
import nuvio.composeapp.generated.resources.lists_sort_by
import nuvio.composeapp.generated.resources.lists_summary_titles
import nuvio.composeapp.generated.resources.lists_switch_to
import nuvio.composeapp.generated.resources.lists_tab_title
import nuvio.composeapp.generated.resources.settings_desktop_signed_in_as
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CardMinWidth = 500.dp

/** Writes started from the tab that must finish after it closes (pending edits flushed on dispose). */
private val detachedListWrites = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** What MyAnimeList's full entry editor needs from the tab. */
internal class ListEntryDetailsRequest(
    val entry: ListEntry,
    val saving: Boolean,
    val editor: ListEditor,
    val dismiss: () -> Unit,
    /** Asks to remove the entry from the list (with the tab's confirmation). */
    val remove: () -> Unit,
)

/**
 * Service-specific pieces of the lists tab. MyAnimeList supplies its connect prompt, its full entry editor and
 * removal from the list; the other services need none of them.
 */
internal class ListTabServiceHooks(
    /** Shown instead of the generic reconnect message while the service is disconnected. */
    val disconnected: (@Composable (Modifier) -> Unit)? = null,
    /** The service is still settling its account (e.g. refreshing a token); the tab shows its loading grid. */
    val busy: Boolean = false,
    /** Changes when the account behind the service changes, which starts the tab afresh. */
    val revision: Any? = null,
    val editDetails: (@Composable (ListEntryDetailsRequest) -> Unit)? = null,
    val removeEntry: (suspend (ListEntry) -> Unit)? = null,
)

/** The tab's title: the only service's name, or "Lists" when several share the tab. */
@Composable internal fun listsTabTitle(tabServices: List<ListService>): String =
    tabServices.singleOrNull()?.id?.displayName ?: stringResource(Res.string.lists_tab_title)

/** The connected services shown in the lists tab for the active profile; loads every service and follows changes. */
@Composable internal fun rememberListsTabServices(): List<ListService> {
    val profile by ProfileRepository.state.collectAsState()
    LaunchedEffect(profile.activeProfile) { ensureListServicesLoaded() }
    val prefs by ListPreferencesRepository.state.collectAsState()
    val services = ListServices.all()
    services.forEach { service -> key(service.id) { service.connected.collectAsState().value } }
    return tabServices(services, prefs)
}

/**
 * Desktop lists tab: the MyAnimeList-style list editor for every service the user shows in the tab. One service
 * names the tab; several get "Lists" and a chip per service (the last chip picked is remembered per profile). A
 * service that disconnects while showing keeps its place and shows its reconnect message.
 *
 * Edits are bound to [profileId]: [activeProfile] is checked before every write, so an edit left pending when the
 * profile changes is dropped rather than written to the new profile's account.
 */
@Composable internal fun ListsTabContent(
    topChromePadding: Dp?,
    services: List<ListService>,
    preferences: ListPreferences,
    hooks: Map<ListServiceId, ListTabServiceHooks> = emptyMap(),
    profileId: Int = ProfileRepository.activeProfileId,
    activeProfile: () -> Int = { ProfileRepository.activeProfileId },
    onRememberService: (ListServiceId) -> Unit = { ListPreferencesRepository.setLastTabService(it) },
) {
    val connected = services.associate { service -> service.id to key(service.id) { service.connected.collectAsState().value } }
    val enabled = services
        .filter { it.id in preferences.inTab }
        .sortedBy { preferences.order.indexOf(it.id).let { index -> if (index < 0) Int.MAX_VALUE else index } }
    val connectedTab = enabled.filter { connected[it.id] == true }
    // The service on screen stays chosen, even if it disconnects, until the user picks another chip.
    var chosen by remember(profileId) { mutableStateOf<ListServiceId?>(null) }
    val current = enabled.firstOrNull { it.id == chosen }
        ?: connectedTab.firstOrNull { it.id == preferences.lastTabService }
        ?: connectedTab.firstOrNull()
        ?: enabled.firstOrNull()
    LaunchedEffect(profileId, current?.id) { if (chosen == null && current != null && connected[current.id] == true) chosen = current.id }
    val chips = enabled.filter { connected[it.id] == true || it.id == current?.id }
    val title = listsTabTitle(chips)

    if (current == null) {
        ListsPageFrame(title, topChromePadding, actions = {}) { inset ->
            ListsMessage(
                title = stringResource(Res.string.lists_no_services_title),
                body = stringResource(Res.string.lists_no_services_body),
                modifier = Modifier.padding(horizontal = inset),
            )
        }
        return
    }
    val hook = hooks[current.id]
    key(profileId, current.id, hook?.revision) {
        ListServicePage(
            service = current,
            hook = hook,
            title = title,
            topChromePadding = topChromePadding,
            profileId = profileId,
            activeProfile = activeProfile,
            chips = if (chips.size > 1) {
                { modifier ->
                    ListServiceChips(chips.map { it.id }, current.id, modifier) { id ->
                        if (id != current.id) {
                            chosen = id
                            onRememberService(id)
                        }
                    }
                }
            } else null,
        )
    }
}

private class PendingTabChange(val key: ListEntryKey, val change: ListChange, val impact: ListChangeImpact)

/** Watch-history entries "Remove from watch history" would delete for [entry]: its watched episodes, or 1 for a watched movie. */
internal fun historyEntryCount(entry: ListEntry): Int =
    if (entry.type == ListMediaType.MOVIE) (if (entry.watchedEpisodes > 0) 1 else 0) else entry.watchedEpisodes

@Composable private fun ListServicePage(
    service: ListService,
    hook: ListTabServiceHooks?,
    title: String,
    topChromePadding: Dp?,
    profileId: Int,
    activeProfile: () -> Int,
    chips: (@Composable (Modifier) -> Unit)?,
) {
    val connected by service.connected.collectAsState()
    val busy = hook?.busy == true
    val name = service.id.displayName
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val statuses = remember(service) {
        (service.capabilities(ListMediaType.SERIES).statuses + service.capabilities(ListMediaType.MOVIE).statuses).distinct()
    }
    var statusKey by rememberSaveable { mutableStateOf("") }
    var sortKey by rememberSaveable { mutableStateOf(service.sorts.firstOrNull()?.key.orEmpty()) }
    val selectedStatus = statuses.firstOrNull { it.name == statusKey }
    val sort = service.sorts.firstOrNull { it.key == sortKey } ?: service.sorts.firstOrNull() ?: ListSort("", "")
    var refreshId by remember { mutableIntStateOf(0) }
    var summaryVersion by remember { mutableIntStateOf(0) }
    val loadFailed = stringResource(Res.string.lists_load_failed, name)
    val removedText = stringResource(Res.string.lists_removed_from, name)

    val editor = remember {
        ListEditor(
            scope = scope,
            profileId = profileId,
            apply = { entry, change -> service.apply(entry, change) },
            activeProfile = activeProfile,
            onFailure = { NuvioToastController.show(it) },
            onCommitted = { before, after, change ->
                if (change == ListChange.FinishRewatch) {
                    // A finished rewatch changes the totals and can move the title in the list; read both again.
                    refreshId++
                    summaryVersion++
                } else if (before.status != after.status) {
                    summaryVersion++
                }
            },
        )
    }
    DisposableEffect(editor) {
        // Don't lose an edit made just before leaving the tab; edits from another profile are dropped by the editor.
        onDispose { editor.flush { write -> detachedListWrites.launch { write() } } }
    }

    val listKey = Triple(selectedStatus, sort, refreshId)
    var requestedOffset by remember(listKey) { mutableIntStateOf(0) }
    var nextOffset by remember(listKey) { mutableStateOf<Int?>(null) }
    var loading by remember(listKey) { mutableStateOf(false) }
    var error by remember(listKey) { mutableStateOf<String?>(null) }
    var retryId by remember(listKey) { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<Pair<ListStatus?, ListSort>?>(null) }
    var summary by remember { mutableStateOf<ListSummary?>(null) }
    var editingKey by remember { mutableStateOf<ListEntryKey?>(null) }
    var removingKey by remember { mutableStateOf<ListEntryKey?>(null) }
    var removingHistoryKey by remember { mutableStateOf<ListEntryKey?>(null) }
    var confirming by remember { mutableStateOf<PendingTabChange?>(null) }
    val impactLock = remember { Mutex() }

    LaunchedEffect(listKey, requestedOffset, retryId, connected, busy) {
        if (!connected || busy) return@LaunchedEffect
        loading = true
        error = null
        try {
            val page = service.page(selectedStatus, sort, requestedOffset)
            editor.show(page.entries, append = requestedOffset > 0)
            nextOffset = page.nextOffset
            shown = selectedStatus to sort
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message?.takeIf { it.isNotBlank() } ?: loadFailed
        }
        loading = false
    }
    LaunchedEffect(connected, busy, refreshId, summaryVersion) {
        if (!connected || busy) return@LaunchedEffect
        // Let a burst of status changes settle before re-reading the totals.
        if (summaryVersion > 0) delay(1_000)
        try {
            summary = service.summary()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The totals are decoration; the list itself reports its own errors.
        }
    }

    // Every change is checked first; one that marks several episodes (or hides a show from Continue Watching) asks.
    fun request(key: ListEntryKey, change: ListChange) {
        if (confirming != null) return
        scope.launch {
            impactLock.withLock {
                // A change that waited for the lock must not apply or replace a confirmation that opened meanwhile.
                if (confirming != null) return@withLock
                val target = editor.entry(key) ?: return@withLock
                val impact = try {
                    service.impact(target, change)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Without the impact the change is not applied.
                    NuvioToastController.show(getString(Res.string.lists_change_check_failed, e.readableMessage()))
                    return@withLock
                }
                if (impact.needsConfirmation()) confirming = PendingTabChange(key, change, impact) else editor.edit(key, change)
            }
        }
    }

    val grid = rememberLazyGridState()
    val nearEnd by remember(grid) {
        derivedStateOf {
            val info = grid.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, nextOffset, loading, error) {
        val next = nextOffset
        if (nearEnd && next != null && !loading && error == null) requestedOffset = next
    }
    LaunchedEffect(selectedStatus, sort) { grid.scrollToItem(0) }

    ListsPageFrame(
        title = title,
        topChromePadding = topChromePadding,
        actions = {
            if (connected) {
                NuvioCompactActionButton(
                    label = stringResource(Res.string.airing_action_refresh),
                    icon = Icons.Rounded.Refresh,
                    onClick = { refreshId++ },
                    enabled = !loading && !editor.hasPendingWork,
                    tone = NuvioCompactActionTone.Secondary,
                    size = NuvioActionSize.Small,
                )
                service.profileUrl()?.let { url ->
                    Spacer(Modifier.width(6.dp))
                    NuvioCompactActionButton(
                        label = stringResource(Res.string.lists_open_on, name),
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        onClick = { uriHandler.openUri(url) },
                        tone = NuvioCompactActionTone.Ghost,
                        size = NuvioActionSize.Small,
                    )
                }
            }
        },
        top = { inset ->
            chips?.invoke(Modifier.padding(horizontal = inset).padding(top = 2.dp, bottom = 14.dp))
            if (connected) {
                ListSummaryRow(summary, Modifier.padding(horizontal = inset).padding(top = 2.dp, bottom = 16.dp))
                ListToolbar(
                    statuses = statuses,
                    selected = selectedStatus,
                    onSelect = { statusKey = it?.name.orEmpty() },
                    counts = summary?.counts,
                    sorts = service.sorts,
                    sort = sort,
                    onSort = { sortKey = it.key },
                    tagPrefix = service.id.tagPrefix,
                    modifier = Modifier.padding(horizontal = inset),
                )
                Spacer(Modifier.height(16.dp))
            }
        },
    ) { inset ->
        val awaitingFirstPage = shown != (selectedStatus to sort)
        when {
            busy -> ListsSkeletonGrid(inset)
            !connected -> hook?.disconnected?.invoke(Modifier.padding(horizontal = inset, vertical = 8.dp)) ?: ListsMessage(
                title = stringResource(Res.string.lists_disconnected_title, name),
                body = stringResource(Res.string.lists_disconnected_body, name),
                modifier = Modifier.padding(horizontal = inset),
            )
            awaitingFirstPage && error != null -> ListsMessage(
                title = stringResource(Res.string.lists_load_failed_title),
                body = error.orEmpty(),
                action = stringResource(Res.string.auth_passkey_retry),
                onAction = { retryId++ },
                modifier = Modifier.padding(horizontal = inset),
            )
            awaitingFirstPage || (editor.entries.isEmpty() && loading) -> ListsSkeletonGrid(inset)
            editor.entries.isEmpty() -> {
                val statusLabel = selectedStatus?.let { listStatusLabel(it) }
                ListsMessage(
                    title = statusLabel?.let { stringResource(Res.string.lists_empty_status_title, it) }
                        ?: stringResource(Res.string.lists_empty_title),
                    body = statusLabel?.let { stringResource(Res.string.lists_empty_status_body, it, name) }
                        ?: stringResource(Res.string.lists_empty_body, name),
                    modifier = Modifier.padding(horizontal = inset),
                )
            }
            else -> Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(CardMinWidth),
                    state = grid,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = inset, end = inset, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(editor.entries, key = { "${it.key.service.storageId}_${it.key.id}" }) { entry ->
                        val capabilities = service.capabilities(entry.type)
                        ListEntryCard(
                            entry = entry,
                            capabilities = capabilities,
                            saving = editor.isSaving(entry.key),
                            onChange = { change -> request(entry.key, change) },
                            onOpen = { (entry.siteUrl ?: service.profileUrl())?.let(uriHandler::openUri) },
                            onRemoveFromHistory = if (capabilities.removeFromHistory && historyEntryCount(entry) > 0) {
                                { removingHistoryKey = entry.key }
                            } else null,
                            onEditDetails = if (hook?.editDetails != null) ({ editingKey = entry.key }) else null,
                            onRemoveEntry = if (hook?.removeEntry != null) ({ removingKey = entry.key }) else null,
                        )
                    }
                    item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                        ListsFooter(
                            loadingMore = loading && requestedOffset > 0,
                            error = error,
                            hasMore = nextOffset != null,
                            count = editor.entries.size,
                            onRetry = { retryId++ },
                        )
                    }
                }
                if (loading && requestedOffset == 0) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = inset).height(2.dp).align(Alignment.TopCenter))
                }
                NuvioDesktopVerticalScrollbar(grid, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }

    confirming?.let { pending ->
        ListChangeConfirmation(
            service = service.id,
            impact = pending.impact,
            onConfirm = {
                confirming = null
                editor.edit(pending.key, pending.change)
            },
            onDismiss = { confirming = null },
        )
    }
    editingKey?.let { key ->
        val entry = editor.entry(key)
        val details = hook?.editDetails
        if (entry == null || details == null) {
            editingKey = null
        } else {
            details(
                ListEntryDetailsRequest(
                    entry = entry,
                    saving = editor.isSaving(key),
                    editor = editor,
                    dismiss = { editingKey = null },
                    remove = { removingKey = key },
                ),
            )
        }
    }
    removingKey?.let { key ->
        val entry = editor.entry(key)
        val removeEntry = hook?.removeEntry
        if (entry == null || removeEntry == null) {
            removingKey = null
        } else {
            ListsConfirmDialog(
                title = stringResource(Res.string.lists_remove_entry_title, name),
                message = stringResource(Res.string.lists_remove_entry_message, entry.title),
                onDismiss = { removingKey = null },
                onConfirm = {
                    removingKey = null
                    editingKey = null
                    // A confirmed removal finishes even if the tab closes right away.
                    editor.remove(key, delete = removeEntry, launch = { block -> detachedListWrites.launch { block() } }) {
                        summaryVersion++
                        NuvioToastController.show(removedText)
                    }
                },
            )
        }
    }
    removingHistoryKey?.let { key ->
        val entry = editor.entry(key)
        if (entry == null) {
            removingHistoryKey = null
        } else {
            val count = historyEntryCount(entry)
            ListsConfirmDialog(
                title = stringResource(Res.string.lists_remove_history_title),
                message = pluralStringResource(Res.plurals.lists_remove_history_message, count, count, entry.title, name),
                onDismiss = { removingHistoryKey = null },
                onConfirm = {
                    removingHistoryKey = null
                    // A confirmed removal finishes even if the tab closes right away.
                    detachedListWrites.launch {
                        withContext(NonCancellable) {
                            try {
                                val removed = editor.afterEdits(key) { service.removeFromHistory(it) } ?: return@withContext
                                NuvioToastController.show(getPluralString(Res.plurals.lists_removed_history, removed, removed))
                                // The service's status follows its history, so read the list again.
                                refreshId++
                                summaryVersion++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                NuvioToastController.show(e.readableMessage())
                            }
                        }
                    }
                },
            )
        }
    }
}

/** Header (title + actions), an optional block under it, and the page body, with the desktop page inset. */
@Composable private fun ListsPageFrame(
    title: String,
    topChromePadding: Dp?,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    top: @Composable (inset: Dp) -> Unit = {},
    body: @Composable (inset: Dp) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val inset = desktopPageHorizontalPaddingForWidth(maxWidth.value)
        Column(Modifier.fillMaxSize()) {
            NuvioScreenHeader(
                title = title,
                modifier = Modifier.padding(horizontal = inset),
                topPadding = topChromePadding,
                actions = actions,
            )
            top(inset)
            Box(Modifier.weight(1f).fillMaxWidth()) { body(inset) }
        }
    }
}

@Composable private fun ListServiceChips(
    services: List<ListServiceId>,
    selected: ListServiceId,
    modifier: Modifier = Modifier,
    onSelect: (ListServiceId) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        services.forEach { id ->
            val isSelected = id == selected
            val shape = RoundedCornerShape(NuvioTokens.Radius.full)
            val description = stringResource(Res.string.lists_switch_to, id.displayName)
            Surface(
                onClick = { onSelect(id) },
                modifier = Modifier
                    .testTag("lists-chip-${id.storageId}")
                    .height(36.dp)
                    .semantics {
                        contentDescription = description
                        this.selected = isSelected
                    },
                shape = shape,
                color = if (isSelected) colors.textPrimary.copy(alpha = 0.12f) else colors.textPrimary.copy(alpha = 0.04f),
                border = BorderStroke(NuvioTokens.Border.thin, if (isSelected) colors.textPrimary.copy(alpha = 0.32f) else colors.borderSubtle),
            ) {
                Row(
                    Modifier.padding(start = 6.dp, end = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ListLogo(id, contentDescription = null, size = 24.dp)
                    Text(
                        id.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) colors.textPrimary else colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** "Signed in as …" followed by the service's totals, e.g. "912 titles  2,650 episodes". */
@Composable internal fun ListSummaryRow(summary: ListSummary?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        summary?.signedInAs?.takeIf { it.isNotBlank() }?.let { name ->
            Text(stringResource(Res.string.settings_desktop_signed_in_as, name), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        val total = summary?.counts?.get(null) ?: 0
        if (summary != null && total > 0) {
            ListStat("%,d".format(total), pluralStringResource(Res.plurals.lists_summary_titles, total))
            summary.extra.forEach { (label, value) -> ListStat(value.toIntOrNull()?.let { "%,d".format(it) } ?: value, listStatLabel(label)) }
        }
    }
}

/** Display text for a summary figure: services hand over English identifiers, which map to localized labels. */
@Composable private fun listStatLabel(label: String): String = when (label) {
    "episodes" -> stringResource(Res.string.lists_stat_episodes)
    "days watched" -> stringResource(Res.string.lists_stat_days_watched)
    "mean score" -> stringResource(Res.string.lists_stat_mean_score)
    "episodes watched" -> stringResource(Res.string.lists_stat_episodes_watched)
    "movies watched" -> stringResource(Res.string.lists_stat_movies_watched)
    "rated titles" -> stringResource(Res.string.lists_stat_rated_titles)
    else -> label
}

/** Display text for a sort option, keyed by [ListSort.key]; unknown keys keep the service's own label. */
@Composable private fun listSortLabel(sort: ListSort): String = when (sort.key) {
    "UPDATED" -> stringResource(Res.string.lists_sort_updated)
    "TITLE" -> stringResource(Res.string.lists_sort_title)
    "SCORE" -> stringResource(Res.string.lists_sort_score)
    "START_DATE" -> stringResource(Res.string.lists_sort_air_date)
    else -> sort.label
}

@Composable private fun ListStat(value: String, label: String) {
    val colors = MaterialTheme.nuvio.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(bottom = 2.dp))
    }
}

/** Status tabs ("All" plus the service's statuses, with counts) and the sort menu; sort moves to its own row on narrow windows. */
@Composable internal fun ListToolbar(
    statuses: List<ListStatus>,
    selected: ListStatus?,
    onSelect: (ListStatus?) -> Unit,
    counts: Map<ListStatus?, Int>?,
    sorts: List<ListSort>,
    sort: ListSort,
    onSort: (ListSort) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // A separate sort row keeps every category visible on smaller windows.
        if (maxWidth < 980.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListStatusTabs(statuses, selected, onSelect, counts, tagPrefix, Modifier.fillMaxWidth())
                if (sorts.isNotEmpty()) Box(Modifier.align(Alignment.End)) { ListSortControl(sorts, sort, onSort) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ListStatusTabs(statuses, selected, onSelect, counts, tagPrefix, Modifier.weight(1f))
                if (sorts.isNotEmpty()) ListSortControl(sorts, sort, onSort)
            }
        }
    }
}

@Composable private fun ListStatusTabs(
    statuses: List<ListStatus>,
    selected: ListStatus?,
    onSelect: (ListStatus?) -> Unit,
    counts: Map<ListStatus?, Int>?,
    tagPrefix: String,
    modifier: Modifier,
) {
    val total = counts?.get(null) ?: 0
    val all = stringResource(Res.string.lists_filter_all)
    Row(modifier.horizontalScroll(rememberScrollState())) {
        NuvioSegmentedControl(
            segments = (listOf<ListStatus?>(null) + statuses).map { status ->
                NuvioSegment(
                    key = status,
                    label = status?.let { listStatusLabel(it) } ?: all,
                    count = if (counts != null && total > 0) "%,d".format(counts[status] ?: 0) else null,
                    testTag = "$tagPrefix-filter-${status?.name ?: "ALL"}",
                )
            },
            selected = selected,
            onSelect = onSelect,
        )
    }
}

@Composable private fun ListSortControl(sorts: List<ListSort>, sort: ListSort, onSort: (ListSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        NuvioCompactActionButton(
            label = listSortLabel(sort),
            icon = Icons.AutoMirrored.Rounded.Sort,
            trailingIcon = Icons.Rounded.ExpandMore,
            accessibilityLabel = stringResource(Res.string.lists_sort_by, listSortLabel(sort)),
            onClick = { open = true },
            tone = NuvioCompactActionTone.Secondary,
            size = NuvioActionSize.Small,
        )
        Menu(expanded = open, onDismissRequest = { open = false }) {
            sorts.forEach { option ->
                MenuItem(text = listSortLabel(option), selected = option == sort, onClick = { open = false; onSort(option) })
            }
        }
    }
}

@Composable private fun ListsFooter(loadingMore: Boolean, error: String?, hasMore: Boolean, count: Int, onRetry: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        when {
            loadingMore -> NuvioLoadingIndicator(size = 22.dp)
            error != null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = colors.danger)
                TextButton(onClick = onRetry) { Text(stringResource(Res.string.auth_passkey_retry)) }
            }
            !hasMore -> Text(
                pluralStringResource(Res.plurals.lists_footer_titles, count, "%,d".format(count)),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
            )
        }
    }
}

@Composable private fun ListsSkeletonGrid(inset: Dp) {
    val colors = MaterialTheme.nuvio.colors
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CardMinWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = inset, end = inset, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        userScrollEnabled = false,
    ) {
        items(9) {
            val shape = RoundedCornerShape(NuvioTokens.Radius.xl)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceCard, shape)
                    .border(NuvioTokens.Border.thin, colors.borderSubtle, shape)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SkeletonBlock(width = ListPosterWidth, height = ListPosterHeight, cornerRadius = NuvioTokens.Radius.md)
                Column(Modifier.weight(1f).height(ListPosterHeight)) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.8f), height = 18.dp)
                    Spacer(Modifier.height(8.dp))
                    SkeletonBlock(Modifier.fillMaxWidth(0.45f), height = 12.dp)
                    Spacer(Modifier.weight(1f))
                    SkeletonBlock(Modifier.fillMaxWidth(), height = 4.dp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonBlock(width = 110.dp, height = ListControlHeight, cornerRadius = 17.dp)
                        SkeletonBlock(width = 64.dp, height = ListControlHeight, cornerRadius = 17.dp)
                    }
                }
            }
        }
    }
}

@Composable private fun ListsMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = MaterialTheme.nuvio.colors
    Column(modifier.padding(top = 32.dp).widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            NuvioCompactActionButton(
                label = action,
                icon = Icons.Rounded.Refresh,
                onClick = onAction,
                tone = NuvioCompactActionTone.Secondary,
                size = NuvioActionSize.Small,
            )
        }
    }
}

@Composable private fun ListsConfirmDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    DialogSurface(onDismissRequest = onDismiss, title = title, message = message) {
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(text = stringResource(Res.string.action_remove), onClick = onConfirm, style = DialogButtonStyle.Destructive)
        }
    }
}
