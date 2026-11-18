package com.nuvio.app.features.profiles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.LocalTelevisionUi
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private val PinGround = Color(0xFF09090D)
private val PinCard = Color(0xFF111118)
private val PinSlotFill = Color(0xFF0B0B11)
private val PinKeyFill = Color(0xFF15151D)
private val PinKeyBorder = Color(0xFF23232D)
private val PinLine = Color(0xFF26262F)
private val PinFilled = Color(0xFF3A3A48)
private val PinAccent = Color(0xFF45D6EB)
private val PinTextPrimary = Color(0xFFF5F7F8)
private val PinTextSecondary = Color(0xFFA4A9B2)
private val PinTextMuted = Color(0xFF80858F)

private const val PinLength = 4

/** Sizes for one PIN layout; phone, desktop and TV only differ in scale and arrangement. */
private data class PinMetrics(
    val slotWidth: Dp,
    val slotHeight: Dp,
    val slotGap: Dp,
    val dotSize: Dp,
    val keyHeight: Dp,
    val keyGap: Dp,
    val keypadWidth: Dp,
    val keyTextSize: Float,
    val avatarSize: Dp,
)

private val PhonePinMetrics = PinMetrics(60.dp, 68.dp, 12.dp, 14.dp, 64.dp, 12.dp, 312.dp, 28f, 96.dp)
private val DesktopPinMetrics = PinMetrics(56.dp, 64.dp, 12.dp, 13.dp, 56.dp, 10.dp, 368.dp, 22f, 72.dp)
private val TelevisionPinMetrics = PinMetrics(56.dp, 66.dp, 12.dp, 13.dp, 54.dp, 12.dp, 264.dp, 22f, 112.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinEntryDialog(
    profileName: String,
    onVerify: suspend (String) -> PinVerifyResult,
    onDismiss: () -> Unit,
    onVerified: ((String) -> Unit)? = null,
    onForgotPin: (() -> Unit)? = null,
    profile: NuvioProfile? = null,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var errorCount by remember { mutableIntStateOf(0) }
    var isVerifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val television = LocalTelevisionUi.current
    val containerFocus = remember { FocusRequester() }
    val firstKeyFocus = remember { FocusRequester() }

    fun submitDigit(digit: String) {
        if (pin.length < PinLength && !isVerifying) {
            error = null
            pin += digit
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (pin.length == PinLength) {
                isVerifying = true
                scope.launch {
                    val result = onVerify(pin)
                    if (result.unlocked) {
                        onVerified?.invoke(pin)
                    } else {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        error = result.message ?: if (result.retryAfterSeconds > 0) {
                            getString(
                                Res.string.pin_locked_try_again,
                                result.retryAfterSeconds,
                            )
                        } else {
                            getString(Res.string.pin_incorrect)
                        }
                        errorCount += 1
                        pin = ""
                    }
                    isVerifying = false
                }
            }
        }
    }

    fun deleteDigit() {
        if (pin.isNotEmpty() && !isVerifying) {
            pin = pin.dropLast(1)
            error = null
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val heading = if (profile != null) stringResource(Res.string.pin_enter_for, profile.name) else profileName
    val keyEvents = Modifier
        .focusRequester(containerFocus)
        .focusable()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val digit = event.utf16CodePoint.takeIf { it in '0'.code..'9'.code }?.toChar()?.toString()
            when {
                digit != null -> {
                    submitDigit(digit)
                    true
                }
                event.key == Key.Backspace || event.key == Key.Delete -> {
                    deleteDigit()
                    true
                }
                event.key == Key.Escape -> {
                    onDismiss()
                    true
                }
                else -> false
            }
        }
    val state = PinViewState(
        pinLength = pin.length,
        error = error,
        errorCount = errorCount,
        isVerifying = isVerifying,
    )
    val keypad: @Composable (PinMetrics) -> Unit = { metrics ->
        PinKeypad(
            metrics = metrics,
            television = television,
            enabled = !isVerifying,
            firstKeyFocus = firstKeyFocus,
            onDigit = ::submitDigit,
            onBackspace = ::deleteDigit,
        )
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(keyEvents)
                .testTag("pin-entry-dialog"),
        ) {
            LaunchedEffect(television) {
                // TV remotes need a focused key to start from; keyboards and touch type into the container.
                val target = if (television) firstKeyFocus else containerFocus
                if (runCatching { target.requestFocus() }.isFailure) {
                    withFrameNanos { }
                    runCatching { target.requestFocus() }
                }
            }
            when {
                television -> PinTelevisionLayout(
                    heading = heading,
                    profile = profile,
                    state = state,
                    keypad = keypad,
                    onForgotPin = onForgotPin,
                )
                maxWidth < 600.dp -> PinPhoneLayout(
                    heading = heading,
                    profile = profile,
                    state = state,
                    keypad = keypad,
                    onDismiss = onDismiss,
                    onForgotPin = onForgotPin,
                )
                else -> PinDesktopLayout(
                    heading = heading,
                    profile = profile,
                    state = state,
                    keypad = keypad,
                    onDismiss = onDismiss,
                    onForgotPin = onForgotPin,
                )
            }
        }
    }
}

private data class PinViewState(
    val pinLength: Int,
    val error: String?,
    val errorCount: Int,
    val isVerifying: Boolean,
)

@Composable
private fun PinPhoneLayout(
    heading: String,
    profile: NuvioProfile?,
    state: PinViewState,
    keypad: @Composable (PinMetrics) -> Unit,
    onDismiss: () -> Unit,
    onForgotPin: (() -> Unit)?,
) {
    val metrics = PhonePinMetrics
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PinGround)
            .verticalScroll(rememberScrollState())
            .padding(
                top = insets.calculateTopPadding() + 12.dp,
                bottom = insets.calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            PinCloseButton(onDismiss = onDismiss)
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (profile != null) {
            PinProfileAvatar(profile = profile, size = metrics.avatarSize)
            Spacer(modifier = Modifier.height(18.dp))
        }
        PinHeading(text = heading, size = 24f)
        if (profile != null) {
            Spacer(modifier = Modifier.height(6.dp))
            PinSupportText(stringResource(Res.string.pin_profile_locked), size = 15f)
        }
        Spacer(modifier = Modifier.height(28.dp))
        PinSlots(metrics = metrics, state = state)
        PinStatus(error = state.error, size = 14f, modifier = Modifier.padding(top = 14.dp))
        Spacer(modifier = Modifier.height(18.dp))
        keypad(metrics)
        if (onForgotPin != null) {
            Spacer(modifier = Modifier.height(14.dp))
            PinForgotButton(onClick = onForgotPin)
        }
    }
}

@Composable
private fun PinDesktopLayout(
    heading: String,
    profile: NuvioProfile?,
    state: PinViewState,
    keypad: @Composable (PinMetrics) -> Unit,
    onDismiss: () -> Unit,
    onForgotPin: (() -> Unit)?,
) {
    val metrics = DesktopPinMetrics
    val cardShape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            // The dialog fills the window, so outside clicks are handled here.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .clip(cardShape)
                .background(PinCard)
                .border(1.dp, PinLine, cardShape)
                // Swallows clicks so taps inside the card never dismiss it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .verticalScroll(rememberScrollState())
                .padding(start = 36.dp, end = 36.dp, top = 28.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                PinCloseButton(onDismiss = onDismiss, modifier = Modifier.align(Alignment.TopEnd))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (profile != null) {
                        PinProfileAvatar(profile = profile, size = metrics.avatarSize)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    PinHeading(text = heading, size = 22f, modifier = Modifier.padding(horizontal = 40.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                    PinSupportText(stringResource(Res.string.pin_keyboard_hint), size = 14f)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            PinSlots(metrics = metrics, state = state)
            PinStatus(error = state.error, size = 14f, modifier = Modifier.padding(top = 14.dp))
            Spacer(modifier = Modifier.height(16.dp))
            keypad(metrics)
            if (onForgotPin != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(PinLine),
                )
                Spacer(modifier = Modifier.height(10.dp))
                PinForgotButton(onClick = onForgotPin)
            }
        }
    }
}

@Composable
private fun PinTelevisionLayout(
    heading: String,
    profile: NuvioProfile?,
    state: PinViewState,
    keypad: @Composable (PinMetrics) -> Unit,
    onForgotPin: (() -> Unit)?,
) {
    val metrics = TelevisionPinMetrics
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(PinGround)
            .padding(horizontal = 72.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
        ) {
            if (profile != null) {
                PinProfileAvatar(profile = profile, size = metrics.avatarSize)
                Spacer(modifier = Modifier.height(24.dp))
            }
            PinHeading(text = heading, size = 36f, textAlign = TextAlign.Start)
            Spacer(modifier = Modifier.height(10.dp))
            PinSupportText(
                text = stringResource(Res.string.pin_remote_hint),
                size = 16f,
                textAlign = TextAlign.Start,
            )
            if (onForgotPin != null) {
                Spacer(modifier = Modifier.height(20.dp))
                PinForgotButton(onClick = onForgotPin)
            }
        }
        Column(
            modifier = Modifier.width(metrics.keypadWidth + 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PinSlots(metrics = metrics, state = state)
            PinStatus(error = state.error, size = 15f, modifier = Modifier.padding(top = 14.dp))
            Spacer(modifier = Modifier.height(18.dp))
            keypad(metrics)
        }
    }
}

@Composable
private fun PinHeading(
    text: String,
    size: Float,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall.copy(
            fontSize = size.sp,
            lineHeight = (size * 1.25f).sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2f).sp,
        ),
        color = PinTextPrimary,
        textAlign = textAlign,
    )
}

@Composable
private fun PinSupportText(
    text: String,
    size: Float,
    textAlign: TextAlign = TextAlign.Center,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = size.sp,
            lineHeight = (size * 1.4f).sp,
        ),
        color = PinTextSecondary,
        textAlign = textAlign,
    )
}

/** One line reserved for errors so the keypad never jumps when a message appears. */
@Composable
private fun PinStatus(error: String?, size: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = (size * 1.45f).dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = size.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PinSlots(metrics: PinMetrics, state: PinViewState) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state.errorCount) {
        if (state.errorCount > 0) {
            shake.snapTo(0f)
            shake.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 420
                    -10f at 80
                    9f at 160
                    -6f at 250
                    4f at 330
                },
            )
        }
    }
    val hasError = state.error != null
    Row(
        modifier = Modifier
            .offset { IntOffset((shake.value * density).roundToInt(), 0) }
            .semantics { contentDescription = "${state.pinLength}/$PinLength" },
        horizontalArrangement = Arrangement.spacedBy(metrics.slotGap),
    ) {
        repeat(PinLength) { index ->
            PinSlot(
                metrics = metrics,
                filled = index < state.pinLength,
                active = !hasError && !state.isVerifying && index == state.pinLength,
                hasError = hasError,
            )
        }
    }
}

@Composable
private fun PinSlot(
    metrics: PinMetrics,
    filled: Boolean,
    active: Boolean,
    hasError: Boolean,
) {
    val shape = RoundedCornerShape(16.dp)
    val borderColor = when {
        hasError -> MaterialTheme.colorScheme.error
        active -> PinAccent
        filled -> PinFilled
        else -> PinLine
    }
    val dotScale = remember { Animatable(if (filled) 1f else 0f) }
    LaunchedEffect(filled) {
        if (filled) {
            dotScale.snapTo(1.5f)
            dotScale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessHigh,
                ),
            )
        } else {
            dotScale.animateTo(0f, tween(durationMillis = 120))
        }
    }
    Box(
        modifier = Modifier
            .size(width = metrics.slotWidth, height = metrics.slotHeight)
            .then(
                if (active) {
                    Modifier.border(4.dp, PinAccent.copy(alpha = 0.15f), RoundedCornerShape(19.dp))
                } else {
                    Modifier
                },
            )
            .padding(if (active) 3.dp else 0.dp)
            .clip(shape)
            .background(PinSlotFill)
            .border(2.dp, borderColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(metrics.dotSize)
                .graphicsLayer {
                    scaleX = dotScale.value
                    scaleY = dotScale.value
                }
                .background(PinTextPrimary, CircleShape),
        )
    }
}

@Composable
private fun PinKeypad(
    metrics: PinMetrics,
    television: Boolean,
    enabled: Boolean,
    firstKeyFocus: FocusRequester,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫"),
    )
    val backspace = stringResource(Res.string.pin_backspace)
    Column(
        modifier = Modifier
            .width(metrics.keypadWidth)
            .testTag("pin-keypad"),
        verticalArrangement = Arrangement.spacedBy(metrics.keyGap),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(metrics.keyGap),
            ) {
                row.forEach { key ->
                    when (key) {
                        "" -> Spacer(modifier = Modifier.weight(1f))
                        "⌫" -> PinKey(
                            metrics = metrics,
                            television = television,
                            enabled = enabled,
                            description = backspace,
                            icon = Icons.AutoMirrored.Rounded.Backspace,
                            subtle = true,
                            onClick = onBackspace,
                            modifier = Modifier.testTag("pin-key-backspace"),
                        )
                        else -> PinKey(
                            metrics = metrics,
                            television = television,
                            enabled = enabled,
                            description = key,
                            label = key,
                            onClick = { onDigit(key) },
                            modifier = Modifier
                                .testTag("pin-key-$key")
                                .then(if (key == "5") Modifier.focusRequester(firstKeyFocus) else Modifier),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.PinKey(
    metrics: PinMetrics,
    television: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    subtle: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape: Shape = RoundedCornerShape(if (television) 16.dp else 20.dp)
    // TV focus is the only cursor, so the focused key inverts and lifts; elsewhere a ring is enough.
    val tvFocused = television && focused
    val lift by animateFloatAsState(if (tvFocused) 1.08f else 1f)
    val background = when {
        tvFocused -> PinTextPrimary
        subtle -> Color.Transparent
        else -> PinKeyFill
    }
    val content = if (tvFocused) PinGround else if (subtle) Color(0xFFC9CCD2) else PinTextPrimary
    val borderColor = when {
        focused -> PinAccent
        subtle -> Color.Transparent
        else -> PinKeyBorder
    }
    Box(
        modifier = modifier
            .weight(1f)
            .height(metrics.keyHeight)
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
            }
            .clip(shape)
            .background(background)
            .border(if (focused) (if (television) 3.dp else 2.dp) else 1.dp, borderColor, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when {
            label != null -> Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = metrics.keyTextSize.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = content,
            )
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size((metrics.keyTextSize * 0.95f).dp),
            )
        }
    }
}

@Composable
private fun PinCloseButton(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(shape)
            .border(2.dp, if (focused) PinAccent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onDismiss)
            .testTag("pin-cancel"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(Res.string.pin_cancel),
            tint = PinTextSecondary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun PinForgotButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    var focused by remember { mutableStateOf(false) }
    Text(
        text = stringResource(Res.string.pin_forgot),
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = PinAccent,
        modifier = Modifier
            .clip(shape)
            .border(2.dp, if (focused) PinAccent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** The profile's avatar with a lock badge, matching the profile picker's rendering. */
@Composable
private fun PinProfileAvatar(profile: NuvioProfile, size: Dp) {
    val avatarColor = remember(profile.avatarColorHex) { parseHexColor(profile.avatarColorHex) }
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val avatarItem = remember(profile.avatarId, avatars) {
        profile.avatarId?.let { id -> avatars.find { it.id == id } }
    }
    val avatarImageUrl = remember(profile.avatarUrl, avatarItem) { profileAvatarImageUrl(profile, avatarItem) }
    val background = avatarItem?.bgColor?.let { parseHexColor(it) }
        ?: if (avatarImageUrl != null) avatarColor else avatarColor.copy(alpha = 0.18f)
    val badge = size * 0.36f
    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(background)
                .then(
                    if (avatarImageUrl == null && avatarItem == null) {
                        Modifier.border(2.dp, avatarColor.copy(alpha = 0.45f), CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                avatarImageUrl != null -> AsyncImage(
                    model = avatarImageUrl,
                    contentDescription = avatarItem?.displayName ?: profile.name,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
                avatarItem != null && isBuiltInAvatar(avatarItem.id) -> BuiltInAvatarIcon(
                    avatarId = avatarItem.id,
                    modifier = Modifier.fillMaxSize(0.62f),
                    contentDescription = avatarItem.displayName,
                )
                profile.name.isNotBlank() -> Text(
                    text = profile.name.take(1).uppercase(),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = (size.value * 0.4f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = avatarColor,
                )
                else -> Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = avatarColor,
                    modifier = Modifier.size(size * 0.46f),
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 4.dp, y = 4.dp)
                .size(badge)
                .background(PinGround, CircleShape)
                .padding(3.dp)
                .background(Color(0xFF1C1C26), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = PinTextPrimary,
                modifier = Modifier.size(badge * 0.46f),
            )
        }
    }
}
