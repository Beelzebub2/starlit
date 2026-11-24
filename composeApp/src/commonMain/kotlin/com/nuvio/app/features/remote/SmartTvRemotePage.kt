package com.nuvio.app.features.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogHint
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.NuvioEmptyBlock
import com.nuvio.app.core.ui.NuvioGroup
import com.nuvio.app.core.ui.NuvioGroupDivider
import com.nuvio.app.core.ui.NuvioGroupRow
import com.nuvio.app.core.ui.NuvioIconChip
import com.nuvio.app.core.ui.NuvioIconChipTone
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLabeledField
import com.nuvio.app.core.ui.NuvioPillButton
import com.nuvio.app.core.ui.NuvioPillButtonStyle
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSquareIconButton
import com.nuvio.app.core.ui.NuvioSquareIconButtonStyle
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.tabular
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** The TV tab sends the TV's own remote keys, so it also navigates Starlit on Tizen/webOS. */
@Composable
internal fun SmartTvRemotePage(
    transport: SmartTvRemoteTransport = remember { SmartTvRemote.transport },
) {
    val active by SmartTvRemote.active.collectAsState()
    val discovered by SmartTvRemote.discovered.collectAsState()
    val saved by SmartTvRemote.saved.collectAsState()
    val scanning by SmartTvRemote.scanning.collectAsState()
    val connecting by SmartTvRemote.connecting.collectAsState()
    val status by SmartTvRemote.status.collectAsState()

    if (!transport.supported) {
        NuvioSurfaceCard(outlined = true) {
            NuvioEmptyBlock(
                icon = RemoteIcons.Tv,
                title = stringResource(Res.string.remote_tv_title),
                message = stringResource(Res.string.remote_tv_android_only),
                tone = NuvioIconChipTone.Neutral,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        return
    }

    LaunchedEffect(Unit) {
        if (active == null && discovered.isEmpty() && !scanning) transport.discover()
    }

    val device = active
    if (device != null) {
        SmartTvConnectedStrip(device, onDisconnect = transport::disconnect)
        SmartTvRemotePad(transport::send)
        StarlitTvPairingSection(folded = true)
        return
    }

    SmartTvFindHero(
        scanning = scanning,
        connecting = connecting,
        status = status,
        onSearch = transport::discover,
        onCancel = transport::disconnect,
    )
    val devices = (discovered + saved).distinctBy { it.address to it.platform }
    if (devices.isNotEmpty() || scanning) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NuvioSectionLabel(stringResource(Res.string.remote_tv_available).uppercase())
            NuvioGroup {
                devices.forEachIndexed { index, tv ->
                    key(tv.platform, tv.address) {
                        if (index > 0) NuvioGroupDivider()
                        SmartTvDeviceRow(
                            device = tv,
                            saved = saved.any { it.address == tv.address && it.platform == tv.platform },
                            connecting = connecting,
                            index = index,
                            onConnect = { transport.connect(tv) },
                            onForget = { transport.forget(tv) },
                        )
                    }
                }
                if (scanning) {
                    if (devices.isNotEmpty()) NuvioGroupDivider()
                    SearchingPlaceholderRow()
                }
            }
        }
    }
    SmartTvManualConnection(
        connecting = connecting,
        // A search that came back empty opens the typed address by itself.
        openAfterEmptySearch = devices.isEmpty(),
        scanning = scanning,
        onConnect = transport::connect,
    )
    StarlitTvPairingSection()
}

// --- Finding a TV --------------------------------------------------------------------------------

/** What is happening right now, and the one main action: search, or cancel a pending approval. */
@Composable
private fun SmartTvFindHero(
    scanning: Boolean,
    connecting: Boolean,
    status: String,
    onSearch: () -> Unit,
    onCancel: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val busy = scanning || connecting
    NuvioSurfaceCard(outlined = true, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RemoteSearchRadar(
                searching = busy,
                connected = false,
                icon = RemoteIcons.Tv,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Text(
                stringResource(Res.string.remote_tv_find_title),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            // While a search or approval runs, its progress replaces the generic hint.
            val showStatus = busy && status.isNotBlank()
            Text(
                if (showStatus) status else stringResource(Res.string.remote_tv_pairing_hint),
                modifier = if (showStatus) Modifier.testTag("smart-tv-remote-status") else Modifier,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        if (connecting) {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().testTag("smart-tv-cancel-pairing"),
            )
        } else {
            NuvioPrimaryButton(
                text = stringResource(Res.string.remote_tv_search),
                icon = RemoteIcons.Search,
                onClick = onSearch,
                enabled = !scanning,
                loading = scanning,
                modifier = Modifier.testTag("smart-tv-search"),
            )
        }
        if (!busy && status.isNotBlank()) {
            Text(
                status,
                modifier = Modifier.fillMaxWidth().testTag("smart-tv-remote-status"),
                style = MaterialTheme.nuvioTypeScale.bodySm,
                color = tokens.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A discovered name is "Platform · address"; show it as a platform title over the address. */
private fun SmartTvDevice.rowTitle(): String =
    if (name == SmartTvDevice(address, platform).name) platform.displayName else name

/** The brand alone ("Samsung", "LG") keeps the address readable beside a row's buttons. */
private fun SmartTvDevice.rowDetail(): String =
    if (name == SmartTvDevice(address, platform).name) address
    else "${platform.displayName.substringBefore(' ')} · $address"

@Composable
private fun SmartTvDeviceRow(
    device: SmartTvDevice,
    saved: Boolean,
    connecting: Boolean,
    index: Int,
    onConnect: () -> Unit,
    onForget: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    NuvioGroupRow(
        title = device.rowTitle(),
        description = device.rowDetail(),
        // Each TV rises in as the search finds it, a beat after the one before.
        modifier = Modifier
            .remoteRiseIn(delayMillis = (index * 120).coerceAtMost(480))
            .testTag("smart-tv-${device.platform.name}-${device.address}"),
        onClick = onConnect,
        enabled = !connecting,
        titleMaxLines = 1,
        descriptionMaxLines = 1,
        verticalPadding = 12.dp,
        endPadding = if (saved) 8.dp else 16.dp,
        spacing = 12.dp,
        leading = { NuvioIconChip(RemoteIcons.Tv, tone = NuvioIconChipTone.Neutral) },
        trailing = {
            NuvioPillButton(
                text = stringResource(Res.string.remote_pc_connect),
                onClick = onConnect,
                style = NuvioPillButtonStyle.Primary,
                enabled = !connecting,
            )
            if (saved) {
                Box {
                    NuvioSquareIconButton(
                        icon = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(Res.string.remote_pc_more_options, device.rowTitle()),
                        onClick = { menuOpen = true },
                        size = 36.dp,
                        style = NuvioSquareIconButtonStyle.Ghost,
                        tint = MaterialTheme.nuvio.colors.textMuted,
                        modifier = Modifier.testTag("smart-tv-more-${device.address}"),
                    )
                    Menu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        MenuItem(
                            text = stringResource(Res.string.remote_pc_forget),
                            enabled = !connecting,
                            onClick = { menuOpen = false; onForget() },
                        )
                    }
                }
            }
        },
    )
}

/** Discovery can be blocked by the router; a typed private address is the fallback. */
@Composable
private fun SmartTvManualConnection(
    connecting: Boolean,
    openAfterEmptySearch: Boolean,
    scanning: Boolean,
    onConnect: (SmartTvDevice) -> Unit,
) {
    var address by rememberSaveable { mutableStateOf("") }
    var platform by rememberSaveable { mutableStateOf(SmartTvPlatform.SamsungTizen) }
    var open by rememberSaveable { mutableStateOf(false) }
    var searched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(scanning) {
        if (scanning) searched = true
        else if (searched && openAfterEmptySearch) open = true
    }
    val valid = isPrivateSmartTvAddress(address.trim())
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NuvioSectionLabel(stringResource(Res.string.remote_window_other_ways).uppercase())
        NuvioGroup {
            NuvioGroupRow(
                title = stringResource(Res.string.remote_tv_manual_title),
                description = stringResource(Res.string.remote_tv_manual_hint),
                modifier = Modifier.testTag("smart-tv-enter-address"),
                onClick = { open = !open },
                descriptionMaxLines = 3,
                leading = { NuvioIconChip(RemoteIcons.Address) },
                trailing = {
                    Icon(
                        if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = tokens.colors.textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
            AnimatedVisibility(visible = open) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SmartTvPlatformPicker(platform, onSelect = { platform = it })
                    NuvioLabeledField(
                        label = stringResource(Res.string.remote_tv_manual_ip_label),
                        helper = stringResource(Res.string.remote_tv_private_ip),
                    ) {
                        NuvioInputField(
                            value = address,
                            onValueChange = { address = it.take(45) },
                            placeholder = "192.168.1.20",
                            minHeight = 48.dp,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            modifier = Modifier.testTag("smart-tv-manual-ip"),
                        )
                    }
                    DialogButton(
                        text = stringResource(Res.string.remote_pc_connect),
                        onClick = { onConnect(SmartTvDevice(address.trim(), platform)) },
                        style = DialogButtonStyle.Primary,
                        enabled = valid && !connecting,
                        modifier = Modifier.fillMaxWidth().testTag("smart-tv-manual-connect"),
                    )
                }
            }
        }
    }
}

/** Samsung or LG: a two-way switch whose highlight slides to the chosen platform. */
@Composable
private fun SmartTvPlatformPicker(selected: SmartTvPlatform, onSelect: (SmartTvPlatform) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val outer = RoundedCornerShape(14.dp)
    val inner = RoundedCornerShape(10.dp)
    val options = SmartTvPlatform.entries
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(outer)
            .background(tokens.colors.surfaceCard)
            .border(tokens.borders.thin, tokens.colors.borderSubtle, outer)
            .padding(4.dp),
    ) {
        val optionWidth = maxWidth / options.size
        val offset by animateDpAsState(
            targetValue = optionWidth * options.indexOf(selected),
            animationSpec = tween(NuvioTokens.Motion.normalMillis, easing = NuvioTokens.Motion.standard),
            label = "smart_tv_platform_indicator",
        )
        Box(
            Modifier
                .offset(x = offset)
                .width(optionWidth)
                .fillMaxHeight()
                .clip(inner)
                .background(lerp(tokens.colors.surfaceCard, tokens.colors.textPrimary, 0.12f)),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            options.forEach { option ->
                val isSelected = option == selected
                val content by animateColorAsState(
                    targetValue = if (isSelected) tokens.colors.textPrimary else tokens.colors.textMuted,
                    animationSpec = tween(NuvioTokens.Motion.normalMillis),
                    label = "smart_tv_platform_content",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(inner)
                        .selectable(
                            selected = isSelected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.RadioButton,
                            onClick = { onSelect(option) },
                        )
                        .testTag("smart-tv-platform-${option.name}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// --- Starlit on the TV -------------------------------------------------------------------------

/** Starlit-native pairing for metadata-only source selection and TV playback. */
@Composable
private fun StarlitTvPairingSection(folded: Boolean = false) {
    val remote = remember { StarlitTvPlayback.transport }
    val connected by StarlitTvPlayback.connected.collectAsState()
    val pairing by StarlitTvPlayback.pairing.collectAsState()
    val name by StarlitTvPlayback.name.collectAsState()
    val verification by StarlitTvPlayback.verification.collectAsState()
    val error by StarlitTvPlayback.error.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NuvioSectionLabel(stringResource(Res.string.remote_tv_starlit_title).uppercase())
        NuvioGroup {
            if (connected) {
                NuvioGroupRow(
                    title = name.ifBlank { stringResource(Res.string.remote_tv_connected_title) },
                    description = stringResource(Res.string.remote_tv_connected_title).takeIf { name.isNotBlank() },
                    titleMaxLines = 1,
                    leading = { ConnectedTvBadge(RemoteIcons.StarlitTv, size = 40.dp) },
                    trailing = { DisconnectButton(remote::disconnect, Modifier.testTag("starlit-tv-unpair")) },
                )
            } else {
                StarlitTvPairingForm(
                    pairing = pairing,
                    verification = verification,
                    error = error,
                    folded = folded,
                    onPair = remote::pair,
                    onCancel = remote::disconnect,
                )
            }
        }
    }
}

/** The code entry, then the matching code to approve on the TV. */
@Composable
private fun StarlitTvPairingForm(
    pairing: Boolean,
    verification: String,
    error: Boolean,
    folded: Boolean,
    onPair: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var code by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf(false) }
    // A pairing that is under way or failed always stays in view.
    val expanded = !folded || open || pairing || error
    NuvioGroupRow(
        title = stringResource(Res.string.remote_tv_starlit_code),
        description = stringResource(Res.string.remote_tv_starlit_hint),
        modifier = Modifier.testTag("starlit-tv-pair-row"),
        onClick = if (folded) ({ open = !open }) else null,
        descriptionMaxLines = 3,
        leading = { NuvioIconChip(RemoteIcons.StarlitTv) },
        trailing = if (folded) {
            {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else null,
    )
    AnimatedVisibility(visible = expanded) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pairing && verification.isNotBlank()) {
                // The TV shows the same code; the person approves it there.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(tokens.colors.accent.copy(alpha = 0.08f))
                        .border(tokens.borders.thin, tokens.colors.accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(vertical = 16.dp, horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(Res.string.remote_tv_starlit_approve),
                        style = MaterialTheme.nuvioTypeScale.bodySm,
                        color = tokens.colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        verification,
                        modifier = Modifier.testTag("starlit-tv-verification"),
                        style = MaterialTheme.typography.headlineMedium.tabular,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = NuvioTokens.LetterSpacing.label,
                        color = tokens.colors.textPrimary,
                    )
                }
            } else if (!pairing) {
                NuvioInputField(
                    value = code,
                    onValueChange = {
                        code = it.uppercase().filter { char -> char in 'A'..'Z' || char in '2'..'9' }.take(8)
                    },
                    placeholder = "ABCDEFGH",
                    minHeight = 48.dp,
                    textStyle = MaterialTheme.typography.titleMedium.tabular.copy(
                        letterSpacing = NuvioTokens.LetterSpacing.label,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    modifier = Modifier.testTag("starlit-tv-pair-code"),
                )
            }
            if (error && !(pairing && verification.isNotBlank())) {
                DialogHint(stringResource(Res.string.remote_tv_starlit_failed), isError = true)
            }
            DialogButton(
                text = stringResource(if (pairing) Res.string.action_cancel else Res.string.remote_pc_connect),
                onClick = if (pairing) onCancel else ({ onPair(code) }),
                style = if (pairing) DialogButtonStyle.Secondary else DialogButtonStyle.Primary,
                enabled = pairing || code.length == 8,
                modifier = Modifier.fillMaxWidth().testTag(if (pairing) "starlit-tv-pair-cancel" else "starlit-tv-pair"),
            )
        }
    }
}

// --- Connected ---------------------------------------------------------------------------------

/** A TV glyph in a soft success square, with a live dot on its corner. */
@Composable
private fun ConnectedTvBadge(icon: ImageVector, size: Dp = 44.dp) {
    val tokens = MaterialTheme.nuvio
    Box(Modifier.size(size)) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(size * 0.32f))
                .background(tokens.colors.success.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tokens.colors.success, modifier = Modifier.size(size * 0.52f))
        }
        RemoteLiveDot(
            tokens.colors.success,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp),
        )
    }
}

@Composable
private fun SmartTvConnectedStrip(device: SmartTvDevice, onDisconnect: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    NuvioSurfaceCard(outlined = true, contentPadding = 14.dp) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectedTvBadge(RemoteIcons.Tv)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    stringResource(Res.string.remote_tv_connected_title).uppercase(),
                    style = MaterialTheme.nuvioTypeScale.labelXs,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = NuvioTokens.LetterSpacing.label,
                    color = tokens.colors.success,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    device.rowTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    device.rowDetail(),
                    style = MaterialTheme.nuvioTypeScale.bodySm,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DisconnectButton(onDisconnect, Modifier.testTag("smart-tv-disconnect"))
        }
    }
}

/** Disconnect as an icon, so a long TV name keeps the room; its name is read out. */
@Composable
private fun DisconnectButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    NuvioSquareIconButton(
        icon = Icons.Rounded.LinkOff,
        contentDescription = stringResource(Res.string.remote_disconnect),
        onClick = onClick,
        modifier = modifier,
        size = 40.dp,
        style = NuvioSquareIconButtonStyle.Surface,
        iconSize = 20.dp,
        tint = MaterialTheme.nuvio.colors.textSecondary,
    )
}

// --- The remote --------------------------------------------------------------------------------

/** Every key gives a light tick, so the phone feels like a remote while eyes stay on the TV. */
@Composable
private fun rememberKeySender(send: (SmartTvKey) -> Unit): (SmartTvKey) -> Unit {
    val haptics = LocalHapticFeedback.current
    val currentSend by rememberUpdatedState(send)
    return remember(haptics) {
        { key ->
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentSend(key)
        }
    }
}

@Composable
private fun SmartTvRemotePad(send: (SmartTvKey) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val press = rememberKeySender(send)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NuvioSectionLabel(stringResource(Res.string.remote_tv_navigation).uppercase())
        NuvioSurfaceCard(outlined = true, contentPadding = 20.dp, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SmartTvDirectionPad(press, diameter = (maxWidth * 0.86f).coerceIn(216.dp, 288.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmartTvWideKey(
                    key = SmartTvKey.Back,
                    label = stringResource(Res.string.action_back),
                    icon = RemoteIcons.Return,
                    onPress = press,
                    modifier = Modifier.weight(1f),
                )
                SmartTvWideKey(
                    key = SmartTvKey.Home,
                    label = stringResource(Res.string.remote_tv_home),
                    icon = RemoteIcons.Home,
                    onPress = press,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NuvioSectionLabel(stringResource(Res.string.remote_tv_playback).uppercase())
        NuvioSurfaceCard(outlined = true, contentPadding = 20.dp, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SmartTvTransportRow(press)
            SmartTvVolumeRow(press)
        }
        Text(
            stringResource(Res.string.remote_tv_controls_hint),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.nuvioTypeScale.bodySm,
            color = tokens.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

private val DirectionKeys = listOf(SmartTvKey.Up, SmartTvKey.Right, SmartTvKey.Down, SmartTvKey.Left)

/** Where each direction's wedge starts, clockwise from three o'clock as Canvas arcs count. */
private fun SmartTvKey.wedgeStart(): Float = when (this) {
    SmartTvKey.Right -> -45f
    SmartTvKey.Down -> 45f
    SmartTvKey.Left -> 135f
    else -> 225f
}

/**
 * A round pad like a real remote's: the whole quarter of the ring around an arrow is its key,
 * the pressed quarter lights up, and OK sits raised in the middle. Each arrow is also its own
 * accessible button.
 */
@Composable
private fun SmartTvDirectionPad(onPress: (SmartTvKey) -> Unit, diameter: Dp) {
    val tokens = MaterialTheme.nuvio
    val currentPress by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf<SmartTvKey?>(null) }
    var lit by remember { mutableStateOf(SmartTvKey.Up) }
    val glow by animateFloatAsState(
        targetValue = if (pressed != null) 1f else 0f,
        animationSpec = tween(if (pressed != null) NuvioTokens.Motion.fastMillis else NuvioTokens.Motion.slowMillis),
        label = "smart_tv_pad_glow",
    )
    val okFraction = 0.40f
    val ringTop = lerp(tokens.colors.surfaceCard, tokens.colors.textPrimary, 0.07f)
    val ringBottom = lerp(tokens.colors.surfaceCard, tokens.colors.textPrimary, 0.02f)
    val wedge = tokens.colors.accent
    val rim = tokens.colors.borderSubtle
    val divider = tokens.colors.textPrimary.copy(alpha = 0.06f)
    Box(
        Modifier
            .size(diameter)
            .drawBehind {
                val radius = this.size.minDimension / 2f
                drawCircle(Brush.verticalGradient(listOf(ringTop, ringBottom)), radius)
                drawCircle(rim, radius - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                // Faint seams between the four keys, like the gaps on a physical pad.
                val inner = radius * okFraction
                listOf(45f, 135f, 225f, 315f).forEach { degrees ->
                    val radians = degrees * PI.toFloat() / 180f
                    val dx = cos(radians)
                    val dy = sin(radians)
                    drawLine(
                        divider,
                        start = center + Offset(dx, dy) * (inner + 6.dp.toPx()),
                        end = center + Offset(dx, dy) * (radius - 10.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                if (glow > 0f) {
                    drawArc(
                        Brush.radialGradient(
                            0f to wedge.copy(alpha = 0f),
                            okFraction to wedge.copy(alpha = 0.10f * glow),
                            1f to wedge.copy(alpha = 0.26f * glow),
                            center = center,
                            radius = radius,
                        ),
                        startAngle = lit.wedgeStart(),
                        sweepAngle = 90f,
                        useCenter = true,
                        topLeft = Offset.Zero,
                        size = Size(radius * 2f, radius * 2f),
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        val radius = size.width / 2f
                        val dx = offset.x - radius
                        val dy = offset.y - radius
                        val distance = hypot(dx, dy)
                        val key = when {
                            distance < radius * okFraction || distance > radius * 1.05f -> null
                            abs(dx) > abs(dy) -> if (dx > 0) SmartTvKey.Right else SmartTvKey.Left
                            else -> if (dy > 0) SmartTvKey.Down else SmartTvKey.Up
                        }
                        if (key != null) {
                            pressed = key
                            lit = key
                            // A scroll that starts on the pad cancels the press instead of sending it.
                            val released = tryAwaitRelease()
                            pressed = null
                            if (released) currentPress(key)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val labels = mapOf(
            SmartTvKey.Up to stringResource(Res.string.remote_tv_up),
            SmartTvKey.Right to stringResource(Res.string.remote_tv_right),
            SmartTvKey.Down to stringResource(Res.string.remote_tv_down),
            SmartTvKey.Left to stringResource(Res.string.remote_tv_left),
        )
        val arrowBox = diameter * 0.28f
        DirectionKeys.forEach { key ->
            val alignment = when (key) {
                SmartTvKey.Up -> Alignment.TopCenter
                SmartTvKey.Right -> Alignment.CenterEnd
                SmartTvKey.Down -> Alignment.BottomCenter
                else -> Alignment.CenterStart
            }
            val active = pressed == key
            val tint by animateColorAsState(
                targetValue = if (active) tokens.colors.accent else tokens.colors.textSecondary,
                animationSpec = tween(NuvioTokens.Motion.fastMillis),
                label = "smart_tv_arrow_tint",
            )
            val label = labels.getValue(key)
            // Touches are handled by the ring above; this box carries the key's accessibility.
            Box(
                Modifier
                    .align(alignment)
                    .padding(diameter * 0.03f)
                    .size(arrowBox)
                    .semantics {
                        role = Role.Button
                        contentDescription = label
                        onClick(label) { currentPress(key); true }
                    }
                    .testTag("smart-tv-key-${key.name}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    RemoteIcons.Chevron,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .size(30.dp)
                        .graphicsLayer {
                            rotationZ = when (key) {
                                SmartTvKey.Up -> -90f
                                SmartTvKey.Down -> 90f
                                SmartTvKey.Left -> 180f
                                else -> 0f
                            }
                            val scale = if (active) 1.12f else 1f
                            scaleX = scale
                            scaleY = scale
                        },
                )
            }
        }
        // OK: raised in the middle, set apart from the ring by a gap of card colour.
        val okSize = diameter * okFraction
        Box(
            Modifier
                .size(okSize)
                .clip(CircleShape)
                .background(tokens.colors.surface)
                .padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            RemoteRoundButton(
                contentDescription = stringResource(Res.string.action_ok),
                onClick = { currentPress(SmartTvKey.Ok) },
                size = okSize - 10.dp,
                container = tokens.colors.accent,
                modifier = Modifier.testTag("smart-tv-key-${SmartTvKey.Ok.name}"),
            ) {
                Text(
                    stringResource(Res.string.action_ok),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.onAccent,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A wide rounded key with its icon and name, for Back and Home under the pad. */
@Composable
private fun SmartTvWideKey(
    key: SmartTvKey,
    label: String,
    icon: ImageVector,
    onPress: (SmartTvKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val container by animateColorAsState(
        targetValue = tokens.colors.textPrimary.copy(alpha = if (pressed) 0.12f else 0.06f),
        animationSpec = tween(NuvioTokens.Motion.fastMillis),
        label = "smart_tv_wide_key",
    )
    Row(
        modifier
            .height(56.dp)
            .remotePressScale(interaction, pressedScale = 0.96f)
            .clip(shape)
            .background(container)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onPress(key) }
            .testTag("smart-tv-key-${key.name}")
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tokens.colors.textPrimary, modifier = Modifier.size(22.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Rewind, play | pause and fast forward; the TV has separate play and pause keys. */
@Composable
private fun SmartTvTransportRow(onPress: (SmartTvKey) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val soft = tokens.colors.textPrimary.copy(alpha = 0.07f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // About 300 dp at full size; narrow phones get proportionally smaller keys.
        val scale = (maxWidth / 300.dp).coerceIn(0.82f, 1f)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteRoundButton(
                contentDescription = stringResource(Res.string.remote_tv_rewind),
                onClick = { onPress(SmartTvKey.Rewind) },
                size = 58.dp * scale,
                container = soft,
                modifier = Modifier.testTag("smart-tv-key-${SmartTvKey.Rewind.name}"),
            ) {
                Icon(RemoteIcons.FastRewind, contentDescription = null, tint = tokens.colors.textPrimary, modifier = Modifier.size(26.dp * scale))
            }
            Row(
                Modifier
                    .height(68.dp * scale)
                    .clip(CircleShape)
                    .background(tokens.colors.accent),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmartTvAccentHalf(
                    key = SmartTvKey.Play,
                    label = stringResource(Res.string.action_play),
                    icon = RemoteIcons.Play,
                    onPress = onPress,
                    width = 74.dp * scale,
                    iconOffset = 2.dp,
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(28.dp * scale)
                        .background(tokens.colors.onAccent.copy(alpha = 0.22f)),
                )
                SmartTvAccentHalf(
                    key = SmartTvKey.Pause,
                    label = stringResource(Res.string.remote_tv_pause),
                    icon = RemoteIcons.Pause,
                    onPress = onPress,
                    width = 74.dp * scale,
                )
            }
            RemoteRoundButton(
                contentDescription = stringResource(Res.string.remote_tv_forward),
                onClick = { onPress(SmartTvKey.FastForward) },
                size = 58.dp * scale,
                container = soft,
                modifier = Modifier.testTag("smart-tv-key-${SmartTvKey.FastForward.name}"),
            ) {
                Icon(RemoteIcons.FastForward, contentDescription = null, tint = tokens.colors.textPrimary, modifier = Modifier.size(26.dp * scale))
            }
        }
    }
}

/** One half of the play | pause pill; it darkens slightly while held. */
@Composable
private fun SmartTvAccentHalf(
    key: SmartTvKey,
    label: String,
    icon: ImageVector,
    onPress: (SmartTvKey) -> Unit,
    width: Dp,
    iconOffset: Dp = 0.dp,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shade by animateFloatAsState(if (pressed) 0.16f else 0f, tween(NuvioTokens.Motion.fastMillis), label = "smart_tv_accent_half")
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = shade))
            .semantics { contentDescription = label }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onPress(key) }
            .testTag("smart-tv-key-${key.name}"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tokens.colors.onAccent,
            modifier = Modifier
                .size(26.dp)
                .offset(x = iconOffset)
                .remotePressScale(interaction, pressedScale = 0.88f),
        )
    }
}

/** Mute beside a rocker: minus and plus at the ends of one pill, like a remote's volume key. */
@Composable
private fun SmartTvVolumeRow(onPress: (SmartTvKey) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteRoundButton(
            contentDescription = stringResource(Res.string.remote_tv_mute),
            onClick = { onPress(SmartTvKey.Mute) },
            size = 52.dp,
            container = tokens.colors.textPrimary.copy(alpha = 0.06f),
            shape = shape,
            modifier = Modifier.testTag("smart-tv-key-${SmartTvKey.Mute.name}"),
        ) {
            RemoteSpeakerIcon(level = 0f, tint = tokens.colors.textPrimary)
        }
        Row(
            Modifier
                .weight(1f)
                .height(52.dp)
                .clip(shape)
                .background(tokens.colors.textPrimary.copy(alpha = 0.06f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmartTvRockerEnd(SmartTvKey.VolumeDown, stringResource(Res.string.remote_tv_volume_down), RemoteIcons.Minus, onPress)
            Text(
                stringResource(Res.string.remote_playback_volume),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SmartTvRockerEnd(SmartTvKey.VolumeUp, stringResource(Res.string.remote_tv_volume_up), RemoteIcons.Plus, onPress)
        }
    }
}

@Composable
private fun SmartTvRockerEnd(key: SmartTvKey, label: String, icon: ImageVector, onPress: (SmartTvKey) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val container by animateColorAsState(
        targetValue = tokens.colors.textPrimary.copy(alpha = if (pressed) 0.10f else 0f),
        animationSpec = tween(NuvioTokens.Motion.fastMillis),
        label = "smart_tv_rocker_end",
    )
    Box(
        Modifier
            .size(width = 64.dp, height = 52.dp)
            .background(container)
            .semantics { contentDescription = label }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onPress(key) }
            .testTag("smart-tv-key-${key.name}"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tokens.colors.textPrimary,
            modifier = Modifier.size(22.dp).remotePressScale(interaction, pressedScale = 0.85f),
        )
    }
}
