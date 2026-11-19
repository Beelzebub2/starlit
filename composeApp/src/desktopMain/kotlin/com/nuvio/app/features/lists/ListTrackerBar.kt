package com.nuvio.app.features.lists

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.simkl.SimklBrandAsset
import com.nuvio.app.features.simkl.simklBrandPainter
import com.nuvio.app.features.trakt.TraktBrandAsset
import com.nuvio.app.features.trakt.traktBrandPainter
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.lists_clear_rating
import nuvio.composeapp.generated.resources.lists_connect
import nuvio.composeapp.generated.resources.lists_rate
import nuvio.composeapp.generated.resources.lists_rate_on
import nuvio.composeapp.generated.resources.lists_rating_description
import nuvio.composeapp.generated.resources.lists_rating_value
import nuvio.composeapp.generated.resources.lists_remove_from
import nuvio.composeapp.generated.resources.lists_set_status
import nuvio.composeapp.generated.resources.lists_status_completed
import nuvio.composeapp.generated.resources.lists_status_description
import nuvio.composeapp.generated.resources.lists_status_dropped
import nuvio.composeapp.generated.resources.lists_status_on_hold
import nuvio.composeapp.generated.resources.lists_status_plan_to_watch
import nuvio.composeapp.generated.resources.lists_status_watching
import nuvio.composeapp.generated.resources.lists_switch_service
import nuvio.composeapp.generated.resources.mal_rewatch_count
import nuvio.composeapp.generated.resources.mal_rewatch_finish
import nuvio.composeapp.generated.resources.mal_rewatch_in_progress
import nuvio.composeapp.generated.resources.mdblist_logo
import nuvio.composeapp.generated.resources.rating_mal
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Stateless title tracker shown under the desktop detail hero actions for one list service: the service logo
 * (a switcher when [otherServices] is non-empty), list status, score and rewatch count, grouped in one bar.
 * Controls follow [capabilities]; [entry] is null when the title is not on the list. [linked] is false only for
 * MyAnimeList when no MAL id could be resolved, which shows the link-entry state instead of the controls.
 */
@Composable internal fun ListTrackerBar(
    service: ListServiceId,
    otherServices: List<ListServiceId>,
    capabilities: ListCapabilities,
    entry: ListEntry?,
    working: Boolean,
    error: String?,
    onSwitch: (ListServiceId) -> Unit,
    onChange: (ListChange) -> Unit,
    onChooseEntry: (() -> Unit)?,
    onRetry: () -> Unit,
    linked: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        ListBarPill(working = working) {
            ListServiceSwitcher(service, otherServices, enabled = !working, onSwitch = onSwitch)
            ListBarDivider()
            if (!linked && !working && onChooseEntry != null) {
                ListBarSegment(onClick = onChooseEntry, enabled = true, accessibilityLabel = "Link MAL entry") {
                    ListBarIcon(Icons.Rounded.Link)
                    ListBarLabel("Link MAL entry", emphasized = true)
                }
            } else {
                val canEdit = !working && linked
                ListStatusSegment(service, capabilities, entry?.status, canEdit, onChange)
                if (capabilities.score) {
                    ListBarDivider()
                    ListScoreSegment(service, entry?.score?.takeIf { it > 0 }, canEdit, onChange)
                }
                if (capabilities.rewatch && entry != null && entry.status != null) {
                    ListBarDivider()
                    ListRewatchSegment(entry, canEdit, onChange)
                }
                if (onChooseEntry != null) {
                    ListBarDivider()
                    val editLabel = if (linked) "Change MAL entry" else "Link MAL entry"
                    ListTooltip(editLabel) {
                        ListBarSegment(onClick = onChooseEntry, enabled = !working, accessibilityLabel = editLabel, horizontalPadding = 11.dp) {
                            ListBarIcon(Icons.Rounded.Edit)
                        }
                    }
                }
            }
        }
        error?.let { ListInlineError(it, working, onRetry) }
    }
}

/** The bar shown when no service can track the title but connecting [service] would (today: anime + MyAnimeList). */
@Composable internal fun ListConnectPrompt(service: ListServiceId, onConnect: () -> Unit, enabled: Boolean = true) {
    val label = stringResource(Res.string.lists_connect, service.displayName)
    ListBarPill(working = false) {
        ListServiceSwitcher(service, emptyList(), enabled = false, onSwitch = {})
        ListBarDivider()
        ListBarSegment(onClick = onConnect, enabled = enabled, accessibilityLabel = label) {
            ListBarIcon(Icons.Rounded.Link)
            ListBarLabel(label, emphasized = true)
        }
    }
}

@Composable internal fun listStatusLabel(status: ListStatus): String = stringResource(
    when (status) {
        ListStatus.WATCHING -> Res.string.lists_status_watching
        ListStatus.COMPLETED -> Res.string.lists_status_completed
        ListStatus.ON_HOLD -> Res.string.lists_status_on_hold
        ListStatus.DROPPED -> Res.string.lists_status_dropped
        ListStatus.PLAN_TO_WATCH -> Res.string.lists_status_plan_to_watch
    },
)

@Composable internal fun listServiceLogo(service: ListServiceId): Painter = when (service) {
    ListServiceId.MYANIMELIST -> painterResource(Res.drawable.rating_mal)
    ListServiceId.SIMKL -> simklBrandPainter(SimklBrandAsset.Glyph)
    ListServiceId.TRAKT -> traktBrandPainter(TraktBrandAsset.Glyph)
    ListServiceId.MDBLIST -> painterResource(Res.drawable.mdblist_logo)
}

private val ListStarColor = Color(0xFFF5C451)

/** MyAnimeList's list colours, lifted slightly so they read on the dark hero; shared by every service. */
private fun ListStatus.dotColor(): Color = when (this) {
    ListStatus.WATCHING -> Color(0xFF2DB039)
    ListStatus.COMPLETED -> Color(0xFF5B83E6)
    ListStatus.ON_HOLD -> Color(0xFFF1C83E)
    ListStatus.DROPPED -> Color(0xFFE5534B)
    ListStatus.PLAN_TO_WATCH -> Color(0xFFB8BEC5)
}

/** Pickable statuses in the service's own order; derived-only statuses (Trakt/MDBList Watching) are never offered. */
internal fun pickerStatuses(capabilities: ListCapabilities): List<ListStatus> =
    (capabilities.statuses + ListStatus.entries).distinct().filter { it in capabilities.pickableStatuses }

@Composable private fun ListServiceSwitcher(
    service: ListServiceId,
    otherServices: List<ListServiceId>,
    enabled: Boolean,
    onSwitch: (ListServiceId) -> Unit,
) {
    if (otherServices.isEmpty()) {
        ListTooltip(service.displayName) {
            Box(Modifier.fillMaxHeight().padding(start = 6.dp, end = 4.dp), contentAlignment = Alignment.Center) {
                ListLogo(service, contentDescription = service.displayName)
            }
        }
        return
    }
    var menu by remember { mutableStateOf(false) }
    Box {
        ListBarSegment(
            onClick = { menu = true },
            enabled = enabled,
            accessibilityLabel = stringResource(Res.string.lists_switch_service, service.displayName),
            horizontalPadding = 6.dp,
        ) {
            ListLogo(service, contentDescription = null)
            ListBarChevron()
        }
        ListMenu(expanded = menu, onDismiss = { menu = false }) {
            (listOf(service) + otherServices).forEach { option ->
                ListMenuItem(
                    text = option.displayName,
                    selected = option == service,
                    leading = { ListLogo(option, contentDescription = null, size = 20.dp) },
                    onClick = {
                        menu = false
                        if (option != service) onSwitch(option)
                    },
                )
            }
        }
    }
}

@Composable internal fun ListLogo(service: ListServiceId, contentDescription: String?, size: Dp = 26.dp) {
    Image(
        painter = listServiceLogo(service),
        contentDescription = contentDescription,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.27f)),
    )
}

@Composable private fun ListStatusSegment(
    service: ListServiceId,
    capabilities: ListCapabilities,
    current: ListStatus?,
    enabled: Boolean,
    onChange: (ListChange) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val label = current?.let { listStatusLabel(it) } ?: stringResource(Res.string.lists_set_status)
    val colors = MaterialTheme.nuvio.colors
    Box {
        ListBarSegment(
            onClick = { menu = true },
            enabled = enabled,
            accessibilityLabel = stringResource(Res.string.lists_status_description, service.displayName, label),
        ) {
            ListStatusDot(current)
            ListBarLabel(label, emphasized = current != null)
            ListBarChevron()
        }
        ListMenu(expanded = menu, onDismiss = { menu = false }) {
            pickerStatuses(capabilities).forEach { option ->
                ListMenuItem(
                    text = listStatusLabel(option),
                    selected = option == current,
                    leading = { ListStatusDot(option) },
                    onClick = {
                        menu = false
                        if (option != current) onChange(ListChange.SetStatus(option))
                    },
                )
            }
            if (current != null && current in capabilities.leavableStatuses) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.borderSubtle)
                ListMenuItem(
                    text = stringResource(Res.string.lists_remove_from, listStatusLabel(current)),
                    selected = false,
                    leading = { Icon(Icons.Rounded.RemoveCircleOutline, null, Modifier.size(18.dp), tint = colors.textSecondary) },
                    onClick = { menu = false; onChange(ListChange.LeaveStatus(current)) },
                )
            }
        }
    }
}

@Composable private fun ListScoreSegment(service: ListServiceId, score: Int?, enabled: Boolean, onChange: (ListChange) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.nuvio.colors
    Box {
        ListBarSegment(
            onClick = { menu = true },
            enabled = enabled,
            accessibilityLabel = score?.let { stringResource(Res.string.lists_rating_description, service.displayName, it) }
                ?: stringResource(Res.string.lists_rate_on, service.displayName),
        ) {
            if (score != null) {
                ListBarIcon(Icons.Rounded.Star, tint = ListStarColor)
                Text(listScoreText(score), style = listBarTextStyle(), maxLines = 1)
            } else {
                ListBarIcon(Icons.Rounded.StarBorder)
                ListBarLabel(stringResource(Res.string.lists_rate), emphasized = false)
            }
            ListBarChevron()
        }
        ListMenu(expanded = menu, onDismiss = { menu = false }) {
            (10 downTo 1).forEach { value ->
                ListMenuItem(
                    text = stringResource(Res.string.lists_rating_value, value),
                    selected = value == score,
                    leading = { Icon(Icons.Rounded.Star, null, Modifier.size(18.dp), tint = ListStarColor) },
                    onClick = { menu = false; onChange(ListChange.SetScore(value)) },
                )
            }
            if (score != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.borderSubtle)
                ListMenuItem(
                    text = stringResource(Res.string.lists_clear_rating),
                    selected = false,
                    leading = { Icon(Icons.Rounded.StarBorder, null, Modifier.size(18.dp), tint = colors.textSecondary) },
                    onClick = { menu = false; onChange(ListChange.SetScore(0)) },
                )
            }
        }
    }
}

@Composable private fun ListRewatchSegment(entry: ListEntry, enabled: Boolean, onChange: (ListChange) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val completed = entry.status == ListStatus.COMPLETED
    val rewatching = completed && entry.rewatching
    var menu by remember { mutableStateOf(false) }
    val countLabel = stringResource(Res.string.mal_rewatch_count, entry.rewatchCount)
    val inProgressLabel = stringResource(Res.string.mal_rewatch_in_progress)
    Box {
        ListBarSegment(
            onClick = if (completed) ({ menu = true }) else null,
            enabled = enabled,
            accessibilityLabel = if (rewatching) "$countLabel, $inProgressLabel" else countLabel,
        ) {
            ListBarIcon(Icons.Rounded.Replay, tint = if (rewatching) colors.accent else null)
            Text(listRewatchCountText(countLabel, entry.rewatchCount), style = listBarTextStyle(), maxLines = 1)
            if (rewatching) {
                val tagShape = RoundedCornerShape(NuvioTokens.Radius.full)
                Text(
                    inProgressLabel,
                    modifier = Modifier
                        .background(colors.accent.copy(alpha = 0.20f), tagShape)
                        .border(NuvioTokens.Border.thin, colors.accent.copy(alpha = 0.45f), tagShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
                    color = colors.textPrimary,
                    maxLines = 1,
                )
            }
            if (completed) ListBarChevron()
        }
        if (completed) ListMenu(expanded = menu, onDismiss = { menu = false }) {
            DropdownMenuItem(
                text = { Text(inProgressLabel, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary) },
                leadingIcon = { Icon(Icons.Rounded.Replay, null, Modifier.size(18.dp), tint = colors.textSecondary) },
                trailingIcon = { Switch(checked = rewatching, onCheckedChange = null) },
                onClick = { menu = false; onChange(ListChange.SetRewatching(!rewatching)) },
                modifier = Modifier.widthIn(min = 220.dp),
            )
            if (rewatching) DropdownMenuItem(
                text = { Text(stringResource(Res.string.mal_rewatch_finish), style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary) },
                leadingIcon = { Icon(Icons.Rounded.TaskAlt, null, Modifier.size(18.dp), tint = colors.accent) },
                onClick = { menu = false; onChange(ListChange.FinishRewatch) },
            )
        }
    }
}

@Composable private fun ListInlineError(message: String, working: Boolean, onRetry: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Row(
        modifier = Modifier.widthIn(max = 620.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp), tint = colors.danger)
        Text(
            message,
            modifier = Modifier.weight(1f, fill = false),
            color = colors.danger,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        NuvioCompactActionButton(
            label = stringResource(Res.string.action_retry), icon = Icons.Rounded.Refresh,
            tone = NuvioCompactActionTone.Ghost, size = NuvioActionSize.Small,
            enabled = !working,
            onClick = onRetry,
        )
    }
}

@Composable private fun listBarTextStyle(): TextStyle = MaterialTheme.typography.labelLarge.copy(
    fontSize = 14.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.sp,
    fontFeatureSettings = "tnum",
)

/** One glass pill that groups every control of a service, so it reads as a single tracker. */
@Composable private fun ListBarPill(working: Boolean, content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(NuvioTokens.Radius.full)
    Box(
        Modifier
            .height(46.dp)
            .clip(shape)
            .background(colors.textPrimary.copy(alpha = 0.07f))
            .border(NuvioTokens.Border.thin, colors.textPrimary.copy(alpha = 0.12f), shape),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
        if (working) LinearProgressIndicator(
            modifier = Modifier.matchParentSize().wrapContentHeight(Alignment.Bottom).height(2.dp),
            color = colors.accent,
            trackColor = Color.Transparent,
        )
    }
}

@Composable private fun ListBarDivider() {
    Box(
        Modifier
            .width(NuvioTokens.Border.thin)
            .height(18.dp)
            .background(MaterialTheme.nuvio.colors.textPrimary.copy(alpha = 0.14f)),
    )
}

@Composable private fun ListBarSegment(
    onClick: (() -> Unit)?,
    enabled: Boolean,
    accessibilityLabel: String,
    horizontalPadding: Dp = 12.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(NuvioTokens.Radius.full)
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val interactive = onClick != null && enabled
    val background by animateColorAsState(
        targetValue = when {
            !interactive -> Color.Transparent
            pressed -> colors.textPrimary.copy(alpha = 0.16f)
            hovered || focused -> colors.textPrimary.copy(alpha = 0.10f)
            else -> Color.Transparent
        },
        animationSpec = tween(NuvioTokens.Motion.fastMillis),
        label = "list_bar_segment_background",
    )
    val click = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    } else Modifier
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .graphicsLayer { alpha = if (onClick != null && !enabled) 0.45f else 1f }
            .clip(shape)
            .background(background)
            .then(if (interactive && focused) Modifier.border(NuvioTokens.Border.medium, colors.focusRing, shape) else Modifier)
            .then(click)
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel }
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        content = content,
    )
}

@Composable private fun ListBarIcon(icon: ImageVector, tint: Color? = null) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = tint ?: MaterialTheme.nuvio.colors.textSecondary)
}

@Composable private fun ListBarLabel(text: String, emphasized: Boolean) {
    val colors = MaterialTheme.nuvio.colors
    Text(
        text,
        style = listBarTextStyle(),
        color = if (emphasized) colors.textPrimary else colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable private fun ListBarChevron() {
    Icon(
        Icons.Rounded.KeyboardArrowDown,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.nuvio.colors.textSecondary.copy(alpha = 0.8f),
    )
}

@Composable internal fun ListStatusDot(status: ListStatus?) {
    val colors = MaterialTheme.nuvio.colors
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        val color = status?.dotColor()
        if (color == null) {
            Box(Modifier.size(9.dp).border(1.5.dp, colors.textSecondary, CircleShape))
        } else {
            Box(Modifier.size(12.dp).background(color.copy(alpha = 0.25f), CircleShape))
            Box(Modifier.size(7.dp).background(color, CircleShape))
        }
    }
}

@Composable private fun listScoreText(score: Int): AnnotatedString {
    val colors = MaterialTheme.nuvio.colors
    return buildAnnotatedString {
        withStyle(SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.SemiBold)) { append(score.toString()) }
        withStyle(SpanStyle(color = colors.textSecondary)) { append("/10") }
    }
}

/** Keeps the translated "Rewatches: %d" wording but lifts the number so the count is what you see first. */
@Composable private fun listRewatchCountText(label: String, count: Int): AnnotatedString {
    val colors = MaterialTheme.nuvio.colors
    val number = count.toString()
    val index = label.lastIndexOf(number)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = colors.textSecondary)) { append(label) }
        if (index >= 0) {
            addStyle(SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.SemiBold), index, index + number.length)
        }
    }
}

@Composable internal fun ListMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(0.dp, 6.dp),
        shape = RoundedCornerShape(NuvioTokens.Radius.lg),
        containerColor = colors.surfacePopover,
        border = BorderStroke(NuvioTokens.Border.thin, colors.borderSubtle),
        content = content,
    )
}

@Composable internal fun ListMenuItem(text: String, selected: Boolean, leading: @Composable () -> Unit, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    DropdownMenuItem(
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = colors.textPrimary,
            )
        },
        leadingIcon = leading,
        trailingIcon = if (selected) {
            { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = colors.accent) }
        } else null,
        onClick = onClick,
        modifier = Modifier.widthIn(min = 200.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ListTooltip(label: String, content: @Composable () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(NuvioTokens.Radius.md),
                color = colors.surfacePopover,
                contentColor = colors.textPrimary,
                border = BorderStroke(NuvioTokens.Border.thin, colors.borderSubtle),
                shadowElevation = 6.dp,
            ) {
                Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
        },
        delayMillis = 450,
        tooltipPlacement = TooltipPlacement.ComponentRect(
            anchor = Alignment.TopCenter,
            alignment = Alignment.TopCenter,
            offset = DpOffset(0.dp, (-6).dp),
        ),
        content = content,
    )
}
