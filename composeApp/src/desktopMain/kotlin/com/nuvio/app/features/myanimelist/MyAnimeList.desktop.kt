package com.nuvio.app.features.myanimelist

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.nuvio.app.core.ui.NuvioActionSize
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable private fun rememberMalConnection(): MalConnectionState {
    val profile by ProfileRepository.state.collectAsState()
    LaunchedEffect(profile.activeProfile) { MyAnimeListRepository.ensureLoaded() }
    return MyAnimeListRepository.state.collectAsState().value
}
@Composable internal actual fun MyAnimeListTabVisible(): Boolean {
    val connection = rememberMalConnection()
    return connection.connected && connection.showTab
}

internal actual fun myAnimeListPlaybackUpdateThresholdPercent(): Int =
    MyAnimeListRepository.playbackUpdateThresholdPercent()

internal actual suspend fun markMyAnimeListRewatchOnPlayback(
    meta: MetaDetails,
    playbackProfileId: Int,
    positionMs: Long,
    durationMs: Long,
    seasonNumber: Int?,
    episodeNumber: Int?,
    completionReached: Boolean,
): Boolean {
    return MyAnimeListRepository.markRewatchingIfCompleted(
        meta, playbackProfileId, positionMs, durationMs, seasonNumber, episodeNumber, completionReached,
    )
}

@Composable internal actual fun MyAnimeListSettingsCard() {
    val connection = rememberMalConnection()
    var setup by remember { mutableStateOf(false) }
    val syncScope = rememberCoroutineScope()
    val tvSyncSuccessText = stringResource(Res.string.mal_tv_sync_success)
    val tvSyncFailureText = stringResource(Res.string.mal_tv_sync_failure)
    var tvSyncMessage by remember(connection.profileId) { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MyAnimeList", style = MaterialTheme.typography.titleMedium)
            Text(if (!connection.connected) "Connect your anime list to set statuses and ratings."
                else if (connection.username.isBlank() || connection.username == "Connected") "Connected to MyAnimeList."
                else "Connected as ${connection.username}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !connection.busy, onClick = { setup = true }) { Text(if (connection.connected) "Reconnect" else "Set up / Connect") }
                if (connection.connected) TextButton(onClick = MyAnimeListRepository::disconnect) { Text(stringResource(Res.string.remote_disconnect)) }
                if (connection.connecting) TextButton(onClick = MyAnimeListRepository::cancelConnect) { Text(stringResource(Res.string.action_cancel)) }
            }
            if (connection.connected) {
                TextButton(enabled = !connection.busy, onClick = {
                    syncScope.launch {
                        tvSyncMessage = try { MyAnimeListRepository.syncConnectionToAccount(); tvSyncSuccessText }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { tvSyncFailureText }
                    }
                }) { Text(stringResource(Res.string.mal_tv_sync_button)) }
                Text(stringResource(Res.string.mal_tv_sync_description), style = MaterialTheme.typography.bodySmall)
                tvSyncMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Show MyAnimeList tab", style = MaterialTheme.typography.bodyMedium)
                    Text("Browse your MAL anime list from the Starlit navigation bar.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = connection.showTab && connection.connected,
                    onCheckedChange = MyAnimeListRepository::setTabVisible,
                    enabled = connection.connected && !connection.busy,
                )
            }
            Text("Automatic MAL update: ${connection.playbackUpdateThresholdPercent}%", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = connection.playbackUpdateThresholdPercent.toFloat(),
                onValueChange = { MyAnimeListRepository.setPlaybackUpdateThresholdPercent(it.roundToInt()) },
                valueRange = MinimumMalPlaybackUpdateThresholdPercent.toFloat()..MaximumMalPlaybackUpdateThresholdPercent.toFloat(),
                steps = 7,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "A completed title is marked Rewatching only when playback reaches this percentage of an episode. " +
                    "Short playback sessions do not update MAL. Episode counts are still edited manually.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (connection.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            connection.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (setup) MalSetupDialog(connection, onDismiss = { setup = false })
}

@Composable internal actual fun MyAnimeListControls(meta: MetaDetails) {
    val connection = rememberMalConnection()
    key(meta.type, meta.id, connection.profileId, connection.revision) { MalControls(meta, connection) }
}

@Composable private fun MalControls(meta: MetaDetails, connection: MalConnectionState) {
    val scope = rememberCoroutineScope()
    var malId by remember(meta.id, connection.profileId) { mutableStateOf<Int?>(null) }
    var status by remember(meta.id, connection.profileId) { mutableStateOf<MalListStatus?>(null) }
    var busy by remember(meta.id, connection.profileId) { mutableStateOf(false) }
    var error by remember(meta.id, connection.profileId) { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    LaunchedEffect(meta.id, connection.profileId, connection.connected, connection.busy) {
        if (connection.busy) return@LaunchedEffect
        busy = true; error = null
        try {
            malId = withTimeoutOrNull(20_000L) { MyAnimeListRepository.resolveId(meta) }
            if (connection.connected) malId?.let { status = MyAnimeListRepository.listStatus(it) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
        finally { busy = false }
    }
    val anime = malId != null || isLikelyMyAnimeListAnime(meta)
    if (!anime) return
    fun update(newStatus: MalStatus? = null, score: Int? = null, isRewatching: Boolean? = null) {
        val id = malId ?: return
        if (busy || connection.busy) return
        scope.launch {
            busy = true; error = null
            try { status = MyAnimeListRepository.update(id, newStatus, score, isRewatching); NuvioToastController.show("MyAnimeList updated") }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    fun finishRewatch() {
        val id = malId ?: return
        if (busy || connection.busy) return
        scope.launch {
            busy = true; error = null
            try {
                status = MyAnimeListRepository.finishRewatch(id)
                NuvioToastController.show("MyAnimeList updated")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    MalTrackerControls(
        connected = connection.connected,
        connectionBusy = connection.busy,
        working = busy || connection.busy,
        linked = malId != null,
        entry = status,
        error = error,
        onConnect = { setup = true },
        onChooseEntry = { search = true },
        onStatus = { update(newStatus = it) },
        onScore = { update(score = it) },
        onToggleRewatching = { update(isRewatching = status?.isRewatching != true) },
        onFinishRewatch = ::finishRewatch,
        onRetry = {
            scope.launch { busy = true; try { malId?.let { status = MyAnimeListRepository.listStatus(it); error = null } }
                catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false } }
        },
    )
    if (setup) MalSetupDialog(connection, onDismiss = { setup = false })
    if (search) MalSearchDialog(meta, onDismiss = { search = false }, onSelect = { selected ->
        MyAnimeListRepository.link(meta, selected.id); malId = selected.id; search = false
        scope.launch { try { status = MyAnimeListRepository.listStatus(selected.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } }
    })
}

/**
 * Stateless MyAnimeList tracker shown under the desktop detail hero actions: list status, score,
 * rewatch count and entry editing, grouped in one bar. [entry] is null when the title is not on the list.
 */
@Composable internal fun MalTrackerControls(
    connected: Boolean,
    connectionBusy: Boolean,
    working: Boolean,
    linked: Boolean,
    entry: MalListStatus?,
    error: String?,
    onConnect: () -> Unit,
    onChooseEntry: () -> Unit,
    onStatus: (MalStatus) -> Unit,
    onScore: (Int) -> Unit,
    onToggleRewatching: () -> Unit,
    onFinishRewatch: () -> Unit,
    onRetry: () -> Unit,
) {
    var statusMenu by remember { mutableStateOf(false) }
    var ratingMenu by remember { mutableStateOf(false) }
    val entryStatus = entry?.status
    val colors = MaterialTheme.nuvio.colors
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        MalTrackerBar(working = working) {
            MalBrandMark()
            MalBarDivider()
            when {
                !connected -> MalBarSegment(
                    onClick = onConnect, enabled = !connectionBusy,
                    accessibilityLabel = "Connect MyAnimeList",
                ) {
                    MalBarIcon(Icons.Rounded.Link)
                    MalBarLabel("Connect MyAnimeList", emphasized = true)
                }
                !linked && !working -> MalBarSegment(
                    onClick = onChooseEntry, enabled = true,
                    accessibilityLabel = "Link MAL entry",
                ) {
                    MalBarIcon(Icons.Rounded.Link)
                    MalBarLabel("Link MAL entry", emphasized = true)
                }
                else -> {
                    val canEdit = !working && linked
                    Box {
                        MalBarSegment(
                            onClick = { statusMenu = true }, enabled = canEdit,
                            accessibilityLabel = "MyAnimeList status: ${entryStatus?.label ?: "Set status"}",
                        ) {
                            MalStatusDot(entryStatus)
                            MalBarLabel(entryStatus?.label ?: "Set status", emphasized = entryStatus != null)
                            MalBarChevron()
                        }
                        MalMenu(expanded = statusMenu, onDismiss = { statusMenu = false }) {
                            MalStatus.entries.forEach { option ->
                                MalMenuItem(
                                    text = option.label,
                                    selected = option == entryStatus,
                                    leading = { MalStatusDot(option) },
                                    onClick = { statusMenu = false; onStatus(option) },
                                )
                            }
                        }
                    }
                    MalBarDivider()
                    val score = entry?.score?.takeIf { it > 0 }
                    Box {
                        MalBarSegment(
                            onClick = { ratingMenu = true }, enabled = canEdit,
                            accessibilityLabel = score?.let { "MyAnimeList rating: $it out of 10" } ?: "Rate on MyAnimeList",
                        ) {
                            if (score != null) {
                                MalBarIcon(Icons.Rounded.Star, tint = MalStarColor)
                                Text(malScoreText(score), style = malBarTextStyle(), maxLines = 1)
                            } else {
                                MalBarIcon(Icons.Rounded.StarBorder)
                                MalBarLabel("Rate", emphasized = false)
                            }
                            MalBarChevron()
                        }
                        MalMenu(expanded = ratingMenu, onDismiss = { ratingMenu = false }) {
                            (10 downTo 1).forEach { value ->
                                MalMenuItem(
                                    text = "$value / 10",
                                    selected = value == score,
                                    leading = { Icon(Icons.Rounded.Star, null, Modifier.size(18.dp), tint = MalStarColor) },
                                    onClick = { ratingMenu = false; onScore(value) },
                                )
                            }
                            if (score != null) {
                                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.borderSubtle)
                                MalMenuItem(
                                    text = "Clear rating",
                                    selected = false,
                                    leading = { Icon(Icons.Rounded.StarBorder, null, Modifier.size(18.dp), tint = colors.textSecondary) },
                                    onClick = { ratingMenu = false; onScore(0) },
                                )
                            }
                        }
                    }
                    if (entry != null && entryStatus != null) {
                        MalBarDivider()
                        MalRewatchSegment(
                            entry = entry,
                            enabled = canEdit,
                            onToggleRewatching = onToggleRewatching,
                            onFinishRewatch = onFinishRewatch,
                        )
                    }
                    MalBarDivider()
                    val editLabel = if (linked) "Change MAL entry" else "Link MAL entry"
                    MalTooltip(editLabel) {
                        MalBarSegment(
                            onClick = onChooseEntry, enabled = !working,
                            accessibilityLabel = editLabel, horizontalPadding = 11.dp,
                        ) {
                            MalBarIcon(Icons.Rounded.Edit)
                        }
                    }
                }
            }
        }
        error?.let { message ->
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
    }
}

private val MalStarColor = Color(0xFFF5C451)

/** MyAnimeList's own list colours, lifted slightly so they read on the dark hero. */
private fun MalStatus.dotColor(): Color = when (this) {
    MalStatus.WATCHING -> Color(0xFF2DB039)
    MalStatus.COMPLETED -> Color(0xFF5B83E6)
    MalStatus.ON_HOLD -> Color(0xFFF1C83E)
    MalStatus.DROPPED -> Color(0xFFE5534B)
    MalStatus.PLAN_TO_WATCH -> Color(0xFFB8BEC5)
}

@Composable private fun malBarTextStyle(): TextStyle = MaterialTheme.typography.labelLarge.copy(
    fontSize = 14.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.sp,
    fontFeatureSettings = "tnum",
)

/** One glass pill that groups every MyAnimeList control, so it reads as a single tracker. */
@Composable private fun MalTrackerBar(working: Boolean, content: @Composable RowScope.() -> Unit) {
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

@Composable private fun MalBrandMark() {
    MalTooltip("MyAnimeList") {
        Box(Modifier.fillMaxHeight().padding(start = 6.dp, end = 4.dp), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(Res.drawable.rating_mal),
                contentDescription = "MyAnimeList",
                modifier = Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)),
            )
        }
    }
}

@Composable private fun MalBarDivider() {
    Box(
        Modifier
            .width(NuvioTokens.Border.thin)
            .height(18.dp)
            .background(MaterialTheme.nuvio.colors.textPrimary.copy(alpha = 0.14f)),
    )
}

@Composable private fun MalBarSegment(
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
        label = "mal_bar_segment_background",
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

@Composable private fun MalBarIcon(icon: ImageVector, tint: Color? = null) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = tint ?: MaterialTheme.nuvio.colors.textSecondary)
}

@Composable private fun MalBarLabel(text: String, emphasized: Boolean) {
    val colors = MaterialTheme.nuvio.colors
    Text(
        text,
        style = malBarTextStyle(),
        color = if (emphasized) colors.textPrimary else colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable private fun MalBarChevron() {
    Icon(
        Icons.Rounded.KeyboardArrowDown,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.nuvio.colors.textSecondary.copy(alpha = 0.8f),
    )
}

@Composable private fun MalStatusDot(status: MalStatus?) {
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

@Composable private fun malScoreText(score: Int): AnnotatedString {
    val colors = MaterialTheme.nuvio.colors
    return buildAnnotatedString {
        withStyle(SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.SemiBold)) { append(score.toString()) }
        withStyle(SpanStyle(color = colors.textSecondary)) { append("/10") }
    }
}

/** Keeps the translated "Rewatches: %d" wording but lifts the number so the count is what you see first. */
@Composable private fun malRewatchCountText(label: String, count: Int): AnnotatedString {
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

@Composable private fun MalRewatchSegment(
    entry: MalListStatus,
    enabled: Boolean,
    onToggleRewatching: () -> Unit,
    onFinishRewatch: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val completed = entry.status == MalStatus.COMPLETED
    val rewatching = completed && entry.isRewatching
    var menu by remember { mutableStateOf(false) }
    val countLabel = stringResource(Res.string.mal_rewatch_count, entry.numTimesRewatched)
    val inProgressLabel = stringResource(Res.string.mal_rewatch_in_progress)
    Box {
        MalBarSegment(
            onClick = if (completed) ({ menu = true }) else null,
            enabled = enabled,
            accessibilityLabel = if (rewatching) "$countLabel, $inProgressLabel" else countLabel,
        ) {
            MalBarIcon(Icons.Rounded.Replay, tint = if (rewatching) colors.accent else null)
            Text(malRewatchCountText(countLabel, entry.numTimesRewatched), style = malBarTextStyle(), maxLines = 1)
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
            if (completed) MalBarChevron()
        }
        if (completed) MalMenu(expanded = menu, onDismiss = { menu = false }) {
            DropdownMenuItem(
                text = { Text(inProgressLabel, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary) },
                leadingIcon = { Icon(Icons.Rounded.Replay, null, Modifier.size(18.dp), tint = colors.textSecondary) },
                trailingIcon = { Switch(checked = rewatching, onCheckedChange = null) },
                onClick = { menu = false; onToggleRewatching() },
                modifier = Modifier.widthIn(min = 220.dp),
            )
            if (rewatching) DropdownMenuItem(
                text = { Text(stringResource(Res.string.mal_rewatch_finish), style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary) },
                leadingIcon = { Icon(Icons.Rounded.TaskAlt, null, Modifier.size(18.dp), tint = colors.accent) },
                onClick = { menu = false; onFinishRewatch() },
            )
        }
    }
}

@Composable private fun MalMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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

@Composable private fun MalMenuItem(text: String, selected: Boolean, leading: @Composable () -> Unit, onClick: () -> Unit) {
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
@Composable private fun MalTooltip(label: String, content: @Composable () -> Unit) {
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

@Composable internal fun MalSetupDialog(connection: MalConnectionState, onDismiss: () -> Unit) {
    var clientId by remember { mutableStateOf(connection.clientId) }
    var clientSecret by remember { mutableStateOf("") }
    val uri = LocalUriHandler.current
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Connect MyAnimeList") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("In MyAnimeList API setup, choose App Type Other (recommended, no secret). Existing Web apps require their client secret. Your MAL password stays on MyAnimeList.")
            TextButton(onClick = { uri.openUri("https://myanimelist.net/apiconfig") }) { Text("Open MyAnimeList API setup") }
            Text("Set the App Redirect URL to exactly:")
            SelectionContainer { Text(MAL_REDIRECT, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(value = clientId, onValueChange = { clientId = it.trim() }, label = { Text("Public client ID") }, singleLine = true)
            OutlinedTextField(value = clientSecret, onValueChange = { clientSecret = it.trim() },
                label = { Text("Client secret (Web apps only)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Text("The site shown on MAL's approval screen (such as example.com) is your registered app's homepage. You can correct it in MAL API setup; it does not determine the sign-in callback.")
            Text("Connect opens your browser. Approve access there, then return here. Tokens are protected by your Windows account.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = clientId.isNotBlank() && !connection.busy, onClick = {
        runCatching { MyAnimeListRepository.connect(clientId, clientSecret); onDismiss() }.onFailure { error = it.message }
    }) { Text(stringResource(Res.string.remote_pc_connect)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } })
}

@Composable private fun MalSearchDialog(meta: MetaDetails, onDismiss: () -> Unit, onSelect: (MalAnime) -> Unit) {
    var query by remember { mutableStateOf(meta.name) }
    var results by remember { mutableStateOf(emptyList<MalAnime>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun search() { if (busy) return; scope.launch { busy = true; error = null
        try { results = MyAnimeListRepository.search(query) } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message } finally { busy = false } } }
    LaunchedEffect(meta.id) { search() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose this title on MyAnimeList") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true)
            TextButton(enabled = !busy && query.length >= 2, onClick = ::search) { Text(stringResource(Res.string.compose_nav_search)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            results.forEach { anime -> TextButton(onClick = { onSelect(anime) }) { Text(anime.title, maxLines = 2) } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } })
}
