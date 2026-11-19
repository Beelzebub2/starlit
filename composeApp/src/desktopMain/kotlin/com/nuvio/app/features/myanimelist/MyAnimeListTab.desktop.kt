package com.nuvio.app.features.myanimelist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSectionLabel
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.DialogTextField
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSwitchColors
import com.nuvio.app.features.lists.ListChange
import com.nuvio.app.features.lists.ListEntry
import com.nuvio.app.features.lists.ListEntryCard
import com.nuvio.app.features.lists.ListEntryDetailsRequest
import com.nuvio.app.features.lists.ListPill
import com.nuvio.app.features.lists.ListPreferencesRepository
import com.nuvio.app.features.lists.ListServiceId
import com.nuvio.app.features.lists.ListServices
import com.nuvio.app.features.lists.ListSort
import com.nuvio.app.features.lists.ListStepButton
import com.nuvio.app.features.lists.ListSummary
import com.nuvio.app.features.lists.ListSummaryRow
import com.nuvio.app.features.lists.ListTabServiceHooks
import com.nuvio.app.features.lists.ListToolbar
import com.nuvio.app.features.lists.ListsTabContent
import com.nuvio.app.features.lists.listStatusAccent
import com.nuvio.app.features.lists.malListCapabilities
import com.nuvio.app.features.lists.malListStatus
import com.nuvio.app.features.lists.malListSummary
import com.nuvio.app.features.lists.previewListChange
import com.nuvio.app.features.lists.rememberListsTabServices
import com.nuvio.app.features.lists.toListEntry
import com.nuvio.app.features.lists.toListStatus
import com.nuvio.app.features.lists.toMalStatus
import com.nuvio.app.features.lists.withMalListStatus
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.compose_player_episodes
import nuvio.composeapp.generated.resources.mal_rewatch_count
import nuvio.composeapp.generated.resources.mal_rewatch_finish
import nuvio.composeapp.generated.resources.mal_rewatch_in_progress
import org.jetbrains.compose.resources.stringResource

private val ScoreGold = Color(0xFFF5C518)

/** MAL's list colours, brightened slightly so they read on Starlit's dark surfaces (shared with every service's cards). */
internal fun malStatusColor(status: MalStatus?): Color = listStatusAccent(status?.toListStatus())

/**
 * Optional desktop navigation destination: the provider-aware lists tab. MyAnimeList adds its connect prompt, its
 * full entry editor and removal from the list; edits are shown immediately and saved in the background.
 */
@Composable
internal actual fun MyAnimeListTabContent(topChromePadding: Dp?) {
    // Loads every list service for the active profile and follows their connections.
    rememberListsTabServices()
    val profile = ProfileRepository.state.collectAsState().value
    val preferences = ListPreferencesRepository.state.collectAsState().value
    val connection = MyAnimeListRepository.state.collectAsState().value
    var setup by remember { mutableStateOf(false) }
    ListsTabContent(
        topChromePadding = topChromePadding,
        services = ListServices.all(),
        preferences = preferences,
        hooks = mapOf(ListServiceId.MYANIMELIST to malTabHooks(connection, onConnect = { setup = true })),
        profileId = profile.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId,
    )
    if (setup) MalSetupDialog(connection, onDismiss = { setup = false })
}

private fun malTabHooks(connection: MalConnectionState, onConnect: () -> Unit) = ListTabServiceHooks(
    disconnected = { modifier -> MalConnectPrompt(connection, onConnect, modifier) },
    busy = connection.busy && !connection.connecting,
    revision = connection.revision,
    editDetails = { request -> MalEntryDetails(request) },
    removeEntry = { entry -> MyAnimeListRepository.remove(entry.key.id.toInt()) },
)

private fun ListEntry.toMalAnimeListEntry(): MalAnimeListEntry = MalAnimeListEntry(
    anime = MalAnime(key.id.toInt(), title),
    imageUrl = posterUrl,
    totalEpisodes = totalEpisodes,
    listStatus = malListStatus(),
    englishTitle = subtitle,
    meanScore = communityScore,
    airing = airing,
)

/** MyAnimeList's full entry editor for a tab card; saving goes through the tab's editor like every other change. */
@Composable
private fun MalEntryDetails(request: ListEntryDetailsRequest) {
    val entry = request.entry
    val id = entry.key.id.toIntOrNull() ?: return request.dismiss()
    MalEntryEditorDialog(
        entry = entry.toMalAnimeListEntry(),
        facts = entry.facts,
        onDismiss = request.dismiss,
        onSave = { draft ->
            request.dismiss()
            request.editor.edit(
                entry.key,
                preview = { it.withMalListStatus(draft) },
                write = { current ->
                    val patch = malListPatch(current.malListStatus(), draft)
                    if (patch == null) current else current.withMalListStatus(MyAnimeListRepository.update(id, patch))
                },
            )
        },
        onFinishRewatch = {
            if (!request.saving) {
                request.dismiss()
                request.editor.edit(entry.key, ListChange.FinishRewatch)
            }
        },
        onRemove = request.remove,
    )
}

/** A MyAnimeList entry drawn with the lists tab's card; [onChange] receives MAL's own list rules for each change. */
@Composable
internal fun MalEntryCard(
    entry: MalAnimeListEntry,
    saving: Boolean,
    onChange: ((MalAnimeListEntry) -> MalListStatus) -> Unit,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    ListEntryCard(
        entry = entry.toListEntry(),
        capabilities = malListCapabilities,
        saving = saving,
        onChange = { change -> onChange { current -> previewListChange(current.toListEntry(), change).malListStatus() } },
        onOpen = onOpen,
        onRemoveFromHistory = null,
        onEditDetails = onEdit,
        onRemoveEntry = onRemove,
    )
}

@Composable
internal fun MalProfileSummary(username: String?, stats: MalAnimeStatistics?, modifier: Modifier = Modifier) = ListSummaryRow(
    summary = stats?.let { malListSummary(username, it) }
        ?: ListSummary(username?.takeIf { it.isNotBlank() && it != "Connected" }, emptyMap(), emptyList()),
    modifier = modifier,
)

@Composable
internal fun MalListToolbar(
    selected: MalStatus?,
    onSelect: (MalStatus?) -> Unit,
    stats: MalAnimeStatistics?,
    sort: MalListSort,
    onSort: (MalListSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sorts = remember { MalListSort.entries.map { ListSort(it.name, it.label) } }
    ListToolbar(
        statuses = MalStatus.entries.map { it.toListStatus() },
        selected = selected?.toListStatus(),
        onSelect = { onSelect(it?.toMalStatus()) },
        counts = stats?.let { malListSummary(null, it).counts },
        sorts = sorts,
        sort = sorts.first { it.key == sort.name },
        onSort = { option -> onSort(MalListSort.valueOf(option.key)) },
        tagPrefix = "mal",
        modifier = modifier,
    )
}

@Composable
private fun MalConnectPrompt(connection: MalConnectionState, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(NuvioTokens.Radius.xl)
    Column(
        modifier
            .widthIn(max = 560.dp)
            .clip(shape)
            .background(colors.surfaceCard)
            .border(NuvioTokens.Border.thin, colors.borderSubtle, shape)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Connect your MyAnimeList",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Browse your list here, update statuses, scores and episode progress, and every change syncs straight to MyAnimeList.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(4.dp))
        if (connection.connecting) {
            Text("Waiting for you to approve access in your browser…", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = MyAnimeListRepository::cancelConnect) { Text(stringResource(Res.string.action_cancel)) }
        } else {
            NuvioCompactActionButton(
                label = "Connect MyAnimeList",
                icon = Icons.Rounded.Link,
                onClick = onConnect,
                enabled = !connection.busy,
                tone = NuvioCompactActionTone.Emphasized,
            )
        }
        connection.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.danger) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MalEntryEditorDialog(
    entry: MalAnimeListEntry,
    onDismiss: () -> Unit,
    onSave: (MalListStatus) -> Unit,
    onFinishRewatch: () -> Unit = {},
    onRemove: () -> Unit,
    /** The facts line under the title; the lists tab passes the entry's own facts. */
    facts: List<String> = malEntryFacts(entry),
) {
    val colors = MaterialTheme.nuvio.colors
    val total = entry.totalEpisodes
    var draft by remember(entry.anime.id) { mutableStateOf(entry.listStatus) }
    var episodesText by remember(entry.anime.id) { mutableStateOf(entry.listStatus.watchedEpisodes.toString()) }
    var rewatchText by remember(entry.anime.id) { mutableStateOf(entry.listStatus.numTimesRewatched.toString()) }
    fun update(next: MalListStatus) {
        draft = next
        episodesText = next.watchedEpisodes.toString()
        rewatchText = next.numTimesRewatched.toString()
    }
    DialogSurface(onDismissRequest = onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp, 80.dp).clip(RoundedCornerShape(NuvioTokens.Radius.sm)).background(colors.skeleton)) {
                NuvioAsyncImage(entry.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    entry.anime.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                entry.englishTitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (facts.isNotEmpty()) {
                    Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                }
            }
        }

        DialogSectionLabel("Status")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MalStatus.entries.forEach { status ->
                val selected = draft.status == status
                val accent = malStatusColor(status)
                ListPill(
                    onClick = { update(draft.withStatus(status, total)) },
                    container = if (selected) accent.copy(alpha = 0.2f) else colors.textPrimary.copy(alpha = 0.05f),
                    border = if (selected) accent else colors.borderSubtle,
                    accessibilityLabel = status.label + if (selected) ", selected" else "",
                ) {
                    Box(Modifier.size(8.dp).background(accent, CircleShape))
                    Text(
                        status.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else null,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }

        DialogSectionLabel("Episodes watched")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ListStepButton(Icons.Rounded.Remove, "One episode less", enabled = draft.watchedEpisodes > 0) {
                update(draft.withWatchedEpisodes(draft.watchedEpisodes - 1, total))
            }
            DialogTextField(
                value = episodesText,
                onValueChange = { text ->
                    val digits = text.filter(Char::isDigit).take(5)
                    episodesText = digits
                    digits.toIntOrNull()?.let { count ->
                        draft = draft.withWatchedEpisodes(count, total)
                        if (draft.watchedEpisodes != count) episodesText = draft.watchedEpisodes.toString()
                    }
                },
                label = stringResource(Res.string.compose_player_episodes),
                modifier = Modifier.width(110.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
            )
            ListStepButton(Icons.Rounded.Add, "One more episode", enabled = total <= 0 || draft.watchedEpisodes < total) {
                update(draft.withWatchedEpisodes(draft.watchedEpisodes + 1, total))
            }
            Text(
                if (total > 0) "of $total" else "Total not announced",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }

        DialogSectionLabel("Your score")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..10).forEach { score ->
                val selected = draft.score == score
                Surface(
                    onClick = { draft = draft.copy(score = if (selected) 0 else score) },
                    modifier = Modifier.size(width = 34.dp, height = 34.dp).semantics {
                        contentDescription = "$score, ${malScoreLabel(score)}" + if (selected) ", selected" else ""
                    },
                    shape = RoundedCornerShape(NuvioTokens.Radius.md),
                    color = if (selected) ScoreGold else colors.textPrimary.copy(alpha = 0.05f),
                    border = BorderStroke(NuvioTokens.Border.thin, if (selected) ScoreGold else colors.borderSubtle),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "$score",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) Color(0xFF1A1400) else colors.textPrimary,
                        )
                    }
                }
            }
        }
        Text(
            if (draft.score > 0) "${draft.score} · ${malScoreLabel(draft.score)}" else "Not scored. Pick a number, or click it again to clear.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )

        if (draft.status == MalStatus.COMPLETED) {
            DialogTextField(
                value = rewatchText,
                onValueChange = { text ->
                    val digits = text.filter(Char::isDigit).take(5)
                    rewatchText = digits
                    digits.toIntOrNull()?.let { count ->
                        draft = draft.copy(numTimesRewatched = count.coerceAtMost(MAX_MAL_REWATCHES))
                        if (count > MAX_MAL_REWATCHES) rewatchText = draft.numTimesRewatched.toString()
                    }
                },
                label = stringResource(Res.string.mal_rewatch_count, draft.numTimesRewatched),
                modifier = Modifier.width(230.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                    .toggleable(value = draft.isRewatching, role = Role.Switch, onValueChange = { draft = draft.copy(isRewatching = it) })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.mal_rewatch_in_progress), style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
                    Text("Stays completed while you watch it again.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
                Switch(checked = draft.isRewatching, onCheckedChange = null, colors = nuvioSwitchColors())
            }
            if (draft.isRewatching && draft.numTimesRewatched < MAX_MAL_REWATCHES) {
                TextButton(
                    enabled = draft == entry.listStatus,
                    onClick = onFinishRewatch,
                ) {
                    Icon(Icons.Rounded.Replay, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.mal_rewatch_finish))
                }
            }
        }

        TextButton(onClick = onRemove, modifier = Modifier.offset(x = (-12).dp)) {
            Text("Remove from list", color = colors.danger)
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.action_save),
                onClick = { onSave(draft) },
                style = DialogButtonStyle.Primary,
                enabled = draft != entry.listStatus,
            )
        }
    }
}
