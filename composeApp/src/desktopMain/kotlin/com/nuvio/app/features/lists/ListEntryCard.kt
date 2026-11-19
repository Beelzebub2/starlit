package com.nuvio.app.features.lists

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.myanimelist.malScoreLabel
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_edit
import nuvio.composeapp.generated.resources.action_saving
import nuvio.composeapp.generated.resources.airing_now_title
import nuvio.composeapp.generated.resources.details_actions_menu_label
import nuvio.composeapp.generated.resources.lists_card_edit
import nuvio.composeapp.generated.resources.lists_card_score
import nuvio.composeapp.generated.resources.lists_card_score_unset
import nuvio.composeapp.generated.resources.lists_card_status
import nuvio.composeapp.generated.resources.lists_card_status_unset
import nuvio.composeapp.generated.resources.lists_clear_rating
import nuvio.composeapp.generated.resources.lists_community_score
import nuvio.composeapp.generated.resources.lists_episode_less
import nuvio.composeapp.generated.resources.lists_episode_more
import nuvio.composeapp.generated.resources.lists_episodes_of
import nuvio.composeapp.generated.resources.lists_episodes_watched
import nuvio.composeapp.generated.resources.lists_open_on
import nuvio.composeapp.generated.resources.lists_rate
import nuvio.composeapp.generated.resources.lists_remove_entry
import nuvio.composeapp.generated.resources.lists_remove_from
import nuvio.composeapp.generated.resources.lists_remove_history
import nuvio.composeapp.generated.resources.lists_set_episodes
import nuvio.composeapp.generated.resources.lists_set_status
import nuvio.composeapp.generated.resources.mal_rewatch_count
import nuvio.composeapp.generated.resources.mal_rewatch_in_progress
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// Tall enough that every card in a row has the same height, whatever its title length.
internal val ListPosterWidth = 96.dp
// Leave enough vertical room for the rewatch count without uneven card heights.
internal val ListPosterHeight = 160.dp
internal val ListControlHeight = 34.dp
private val ScoreGold = Color(0xFFF5C518)

/** MAL's list colours, brightened slightly so they read on Starlit's dark surfaces; every service's cards use them. */
internal fun listStatusAccent(status: ListStatus?): Color = when (status) {
    ListStatus.WATCHING -> Color(0xFF3DBE5A)
    ListStatus.COMPLETED -> Color(0xFF5B8DEF)
    ListStatus.ON_HOLD -> Color(0xFFF2C14E)
    ListStatus.DROPPED -> Color(0xFFE5534B)
    ListStatus.PLAN_TO_WATCH, null -> Color(0xFF9AA0AA)
}

/** Prefix of the cards' test tags, e.g. "mal-entry-41" or "trakt-episode-stepper". */
internal val ListServiceId.tagPrefix: String get() = if (this == ListServiceId.MYANIMELIST) "mal" else storageId

/**
 * One title on a list service, generalised from the MyAnimeList card. Every control follows [capabilities]: the
 * status menu offers only pickable statuses (a derived status is shown but never offered), "Remove from <status>"
 * appears only for leavable statuses, and the score, episode stepper (never for movies) and rewatch line appear only
 * when the service supports them. [onEditDetails] and [onRemoveEntry] are MyAnimeList's full editor and list removal;
 * [onRemoveFromHistory] is shown only when the service can remove watch history.
 */
@Composable internal fun ListEntryCard(
    entry: ListEntry,
    capabilities: ListCapabilities,
    saving: Boolean,
    onChange: (ListChange) -> Unit,
    onOpen: () -> Unit,
    onRemoveFromHistory: (() -> Unit)?,
    onEditDetails: (() -> Unit)?,
    onRemoveEntry: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    val prefix = entry.key.service.tagPrefix
    val accent = listStatusAccent(entry.status)
    val shape = RoundedCornerShape(NuvioTokens.Radius.xl)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val series = entry.type == ListMediaType.SERIES
    Row(
        modifier = Modifier
            .testTag("$prefix-entry-${entry.key.id}")
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .hoverable(interaction)
            .clip(shape)
            .background(colors.surfaceCard)
            .border(NuvioTokens.Border.thin, if (hovered) colors.borderDefault else colors.borderSubtle, shape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ListPoster(entry, accent, onClick = onEditDetails)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = if (entry.subtitle != null) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (saving) {
                    Text(
                        stringResource(Res.string.action_saving),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                    )
                }
                ListEntryMenu(
                    service = entry.key.service,
                    onEditDetails = onEditDetails,
                    onOpen = onOpen,
                    onRemoveEntry = onRemoveEntry,
                    onRemoveFromHistory = onRemoveFromHistory?.takeIf { capabilities.removeFromHistory },
                )
            }
            Spacer(Modifier.height(6.dp))
            ListFacts(entry, showRewatch = capabilities.rewatch)
            Spacer(Modifier.weight(1f).heightIn(min = 6.dp))
            if (series) {
                ListEpisodeProgress(entry, accent)
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ListStatusPicker(entry.status, capabilities, onChange, Modifier.weight(1f, fill = false))
                    if (capabilities.score) ListScorePicker(entry.score, onSelect = { onChange(ListChange.SetScore(it)) })
                }
                if (series && capabilities.episodeProgress) {
                    ListEpisodeStepper(
                        tag = "$prefix-episode-stepper",
                        watched = entry.watchedEpisodes,
                        total = entry.totalEpisodes,
                        onStep = { onChange(ListChange.StepEpisodes(it)) },
                        onEdit = onEditDetails,
                    )
                }
            }
        }
    }
}

@Composable private fun ListPoster(entry: ListEntry, accent: Color, onClick: (() -> Unit)?) {
    val colors = MaterialTheme.nuvio.colors
    val editLabel = stringResource(Res.string.lists_card_edit, entry.title)
    Box(
        Modifier
            .size(ListPosterWidth, ListPosterHeight)
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(colors.skeleton)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = editLabel, role = Role.Button, onClick = onClick) else Modifier),
    ) {
        NuvioAsyncImage(model = entry.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (entry.airing) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(NuvioTokens.Radius.xs))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(listStatusAccent(ListStatus.WATCHING), CircleShape))
                Text(stringResource(Res.string.airing_now_title), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(accent))
    }
}

@Composable private fun ListFacts(entry: ListEntry, showRewatch: Boolean) {
    val colors = MaterialTheme.nuvio.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (entry.facts.isNotEmpty()) {
                Text(
                    entry.facts.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            entry.communityScore?.let { mean ->
                val value = "%.2f".format(mean)
                val description = stringResource(Res.string.lists_community_score, entry.key.service.displayName, value)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
                ) {
                    Icon(Icons.Rounded.Star, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(13.dp))
                    Text(value, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                }
            }
        }
        if (showRewatch) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (entry.rewatching) {
                    Row(
                        Modifier
                            .background(colors.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(NuvioTokens.Radius.xs))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Replay, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(12.dp))
                        Text(stringResource(Res.string.mal_rewatch_in_progress), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                    }
                }
                Text(
                    stringResource(Res.string.mal_rewatch_count, entry.rewatchCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.testTag("${entry.key.service.tagPrefix}-rewatch-count-${entry.key.id}"),
                )
            }
        }
    }
}

@Composable private fun ListEpisodeProgress(entry: ListEntry, accent: Color) {
    val colors = MaterialTheme.nuvio.colors
    val watched = entry.watchedEpisodes
    val total = entry.totalEpisodes
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (total > 0) stringResource(Res.string.lists_episodes_of, watched, total)
                else pluralStringResource(Res.plurals.lists_episodes_watched, watched, watched),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (total > 0) {
                Text("${(watched * 100 / total).coerceIn(0, 100)}%", style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
            }
        }
        if (total > 0) {
            NuvioProgressBar(
                progress = watched.toFloat() / total,
                height = 4.dp,
                trackColor = colors.textPrimary.copy(alpha = 0.08f),
                fillColor = accent,
            )
        } else {
            // Without a known length an empty bar would read as "not started"; keep the row height.
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** The rounded control used by the card's pickers and by MyAnimeList's entry editor. */
@Composable internal fun ListPill(
    onClick: () -> Unit,
    container: Color,
    border: Color,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(ListControlHeight).semantics { contentDescription = accessibilityLabel },
        shape = RoundedCornerShape(NuvioTokens.Radius.chip),
        color = container,
        border = BorderStroke(NuvioTokens.Border.thin, border),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable private fun ListStatusPicker(
    status: ListStatus?,
    capabilities: ListCapabilities,
    onChange: (ListChange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val accent = listStatusAccent(status)
    var open by remember { mutableStateOf(false) }
    val label = status?.let { listStatusLabel(it) }
    Box(modifier) {
        ListPill(
            onClick = { open = true },
            container = accent.copy(alpha = 0.14f),
            border = accent.copy(alpha = 0.38f),
            accessibilityLabel = stringResource(Res.string.lists_card_status, label ?: stringResource(Res.string.lists_card_status_unset)),
        ) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Text(
                label ?: stringResource(Res.string.lists_set_status),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
        }
        ListMenu(expanded = open, onDismiss = { open = false }) {
            pickerStatuses(capabilities).forEach { option ->
                ListMenuItem(
                    text = listStatusLabel(option),
                    selected = option == status,
                    leading = { ListStatusDot(option) },
                    onClick = {
                        open = false
                        if (option != status) onChange(ListChange.SetStatus(option))
                    },
                )
            }
            if (status != null && status in capabilities.leavableStatuses) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.borderSubtle)
                ListMenuItem(
                    text = stringResource(Res.string.lists_remove_from, listStatusLabel(status)),
                    selected = false,
                    leading = { Icon(Icons.Rounded.RemoveCircleOutline, null, Modifier.size(18.dp), tint = colors.textSecondary) },
                    onClick = { open = false; onChange(ListChange.LeaveStatus(status)) },
                )
            }
        }
    }
}

@Composable private fun ListScorePicker(score: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    var open by remember { mutableStateOf(false) }
    Box {
        ListPill(
            onClick = { open = true },
            container = colors.textPrimary.copy(alpha = 0.06f),
            border = colors.borderSubtle,
            accessibilityLabel = if (score > 0) stringResource(Res.string.lists_card_score, score) else stringResource(Res.string.lists_card_score_unset),
        ) {
            Icon(Icons.Rounded.Star, contentDescription = null, tint = if (score > 0) ScoreGold else colors.textMuted, modifier = Modifier.size(16.dp))
            Text(
                if (score > 0) "$score" else stringResource(Res.string.lists_rate),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (score > 0) FontWeight.SemiBold else null,
                color = if (score > 0) colors.textPrimary else colors.textSecondary,
            )
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
        }
        ListMenu(expanded = open, onDismiss = { open = false }) {
            (10 downTo 1).forEach { option ->
                ListMenuItem(
                    text = "$option  ·  ${malScoreLabel(option)}",
                    selected = option == score,
                    leading = { Icon(Icons.Rounded.Star, null, Modifier.size(18.dp), tint = ScoreGold) },
                    onClick = { open = false; onSelect(option) },
                )
            }
            if (score > 0) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.borderSubtle)
                ListMenuItem(
                    text = stringResource(Res.string.lists_clear_rating),
                    selected = false,
                    leading = { Icon(Icons.Rounded.StarBorder, null, Modifier.size(18.dp), tint = colors.textSecondary) },
                    onClick = { open = false; onSelect(0) },
                )
            }
        }
    }
}

@Composable private fun ListEpisodeStepper(tag: String, watched: Int, total: Int, onStep: (Int) -> Unit, onEdit: (() -> Unit)?) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(NuvioTokens.Radius.chip)
    val setLabel = stringResource(Res.string.lists_set_episodes)
    Row(
        Modifier
            .testTag(tag)
            .height(ListControlHeight)
            .clip(shape)
            .background(colors.textPrimary.copy(alpha = 0.06f))
            .border(NuvioTokens.Border.thin, colors.borderSubtle, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListStepButton(Icons.Rounded.Remove, stringResource(Res.string.lists_episode_less), enabled = watched > 0, size = ListControlHeight) { onStep(-1) }
        Text(
            "$watched / ${if (total > 0) total else "?"}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
                .then(if (onEdit != null) Modifier.clickable(onClickLabel = setLabel, onClick = onEdit) else Modifier)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
        ListStepButton(Icons.Rounded.Add, stringResource(Res.string.lists_episode_more), enabled = total <= 0 || watched < total, size = ListControlHeight) {
            onStep(1)
        }
    }
}

@Composable internal fun ListStepButton(icon: ImageVector, label: String, enabled: Boolean, size: Dp = 40.dp, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) colors.textPrimary else colors.textDisabled, modifier = Modifier.size(18.dp))
    }
}

@Composable private fun ListEntryMenu(
    service: ListServiceId,
    onEditDetails: (() -> Unit)?,
    onOpen: () -> Unit,
    onRemoveEntry: (() -> Unit)?,
    onRemoveFromHistory: (() -> Unit)?,
) {
    val colors = MaterialTheme.nuvio.colors
    var open by remember { mutableStateOf(false) }
    @Composable fun icon(vector: ImageVector, tint: Color = colors.textSecondary) = Icon(vector, null, Modifier.size(18.dp), tint = tint)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(Res.string.details_actions_menu_label), tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
        ListMenu(expanded = open, onDismiss = { open = false }) {
            if (onEditDetails != null) {
                ListMenuItem(stringResource(Res.string.action_edit), selected = false, leading = { icon(Icons.Rounded.Edit) }) { open = false; onEditDetails() }
            }
            ListMenuItem(
                stringResource(Res.string.lists_open_on, service.displayName),
                selected = false,
                leading = { icon(Icons.AutoMirrored.Rounded.OpenInNew) },
            ) { open = false; onOpen() }
            if (onRemoveEntry != null || onRemoveFromHistory != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.borderSubtle)
            }
            if (onRemoveEntry != null) {
                ListMenuItem(stringResource(Res.string.lists_remove_entry), selected = false, leading = { icon(Icons.Rounded.DeleteOutline, colors.danger) }) {
                    open = false; onRemoveEntry()
                }
            }
            if (onRemoveFromHistory != null) {
                ListMenuItem(stringResource(Res.string.lists_remove_history), selected = false, leading = { icon(Icons.Rounded.History, colors.danger) }) {
                    open = false; onRemoveFromHistory()
                }
            }
        }
    }
}
