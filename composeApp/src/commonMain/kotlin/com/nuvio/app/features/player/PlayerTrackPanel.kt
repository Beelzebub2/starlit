package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.LocalTelevisionUi
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.withDuplicateSafeLazyKeys
import com.nuvio.app.isDesktop
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.cd_decrease_value
import nuvio.composeapp.generated.resources.cd_increase_value
import nuvio.composeapp.generated.resources.addon_title
import nuvio.composeapp.generated.resources.compose_action_off
import nuvio.composeapp.generated.resources.compose_player_audio
import nuvio.composeapp.generated.resources.compose_player_built_in
import nuvio.composeapp.generated.resources.compose_player_fetch_subtitles
import nuvio.composeapp.generated.resources.compose_player_no_audio_tracks_available
import nuvio.composeapp.generated.resources.compose_player_style
import nuvio.composeapp.generated.resources.compose_player_subtitle_delay
import nuvio.composeapp.generated.resources.compose_player_subtitles
import nuvio.composeapp.generated.resources.compose_player_track_number
import nuvio.composeapp.generated.resources.player_local_subtitle_choose
import nuvio.composeapp.generated.resources.player_local_subtitle_label
import nuvio.composeapp.generated.resources.player_local_subtitle_remove
import nuvio.composeapp.generated.resources.player_track_badge_commentary
import nuvio.composeapp.generated.resources.player_track_badge_description
import nuvio.composeapp.generated.resources.player_track_badge_original
import nuvio.composeapp.generated.resources.player_track_badge_sdh
import nuvio.composeapp.generated.resources.player_tracks_title
import nuvio.composeapp.generated.resources.settings_playback_option_forced
import nuvio.composeapp.generated.resources.subtitle_language_unknown
import org.jetbrains.compose.resources.stringResource

/** Which column a narrow panel opens on; wide panels show both and only move focus. */
enum class TrackPanelTab { Audio, Subtitles }

private const val SubtitleOffOptionId = "__off__"
private const val LocalSubtitleOptionId = "__local_subtitle_file__"
private val PanelBackground = Color(0xF2111216)
private val PanelBorder = Color.White.copy(alpha = 0.08f)
private val QuietFill = Color.White.copy(alpha = 0.06f)

private data class TrackPanelMetrics(
    val margin: Dp,
    val panelPadding: Dp,
    val maxPanelWidth: Dp,
    val audioColumnWidth: Dp,
    val rowMinHeight: Dp,
    val rowPaddingH: Dp,
    val rowPaddingV: Dp,
    val radioSize: Dp,
    val radioDot: Dp,
    val titleSize: TextUnit,
    val detailSize: TextUnit,
    val badgeSize: TextUnit,
    val headerSize: TextUnit,
    val sectionSize: TextUnit,
    val controlSize: Dp,
    val focusScale: Float,
)

private val PhoneMetrics = TrackPanelMetrics(
    margin = 12.dp,
    panelPadding = 12.dp,
    maxPanelWidth = 420.dp,
    audioColumnWidth = 0.dp,
    rowMinHeight = 56.dp,
    rowPaddingH = 12.dp,
    rowPaddingV = 9.dp,
    radioSize = 18.dp,
    radioDot = 8.dp,
    titleSize = 15.sp,
    detailSize = 13.sp,
    badgeSize = 11.sp,
    headerSize = 18.sp,
    sectionSize = 12.sp,
    controlSize = 44.dp,
    focusScale = 1f,
)

private val WideMetrics = PhoneMetrics.copy(
    margin = 24.dp,
    panelPadding = 22.dp,
    maxPanelWidth = 820.dp,
    audioColumnWidth = 300.dp,
    rowMinHeight = 60.dp,
    rowPaddingH = 14.dp,
    rowPaddingV = 10.dp,
    headerSize = 20.sp,
)

// Android TV lays out at ~960 x 540 dp and is read from across the room.
private val TelevisionMetrics = WideMetrics.copy(
    margin = 24.dp,
    panelPadding = 24.dp,
    maxPanelWidth = 640.dp,
    audioColumnWidth = 250.dp,
    rowMinHeight = 60.dp,
    rowPaddingH = 16.dp,
    radioSize = 20.dp,
    radioDot = 9.dp,
    titleSize = 17.sp,
    detailSize = 14.sp,
    badgeSize = 12.sp,
    headerSize = 22.sp,
    sectionSize = 13.sp,
    controlSize = 48.dp,
    focusScale = 1.02f,
)

/**
 * One panel for audio and subtitle tracks on phones, tablets, Android TV and iOS.
 * Wide screens show both columns side by side; phones switch between them with tabs.
 */
@Composable
fun PlayerTrackPanel(
    visible: Boolean,
    initialTab: TrackPanelTab,
    audioTracks: List<AudioTrack>,
    selectedAudioIndex: Int,
    onAudioTrackSelected: (Int) -> Unit,
    subtitleTracks: List<SubtitleTrack>,
    selectedSubtitleIndex: Int,
    isSubtitleOffSelected: Boolean = false,
    addonSubtitles: List<AddonSubtitle>,
    selectedAddonSubtitleId: String?,
    isLoadingAddonSubtitles: Boolean,
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    subtitleStyle: SubtitleStyleState,
    subtitleDelayMs: Int,
    selectedAddonSubtitle: AddonSubtitle?,
    subtitleAutoSyncState: SubtitleAutoSyncUiState,
    localSubtitleName: String? = null,
    canChooseLocalSubtitle: Boolean = false,
    onBuiltInTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (AddonSubtitle) -> Unit,
    onChooseLocalSubtitle: () -> Unit = {},
    onRemoveLocalSubtitle: () -> Unit = {},
    onFetchAddonSubtitles: () -> Unit,
    onStyleChanged: (SubtitleStyleState) -> Unit,
    onSubtitleDelayChanged: (Int) -> Unit,
    onSubtitleDelayReset: () -> Unit,
    onAutoSyncCapture: () -> Unit,
    onAutoSyncCueSelected: (SubtitleSyncCue) -> Unit,
    onAutoSyncReload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember(visible, initialTab) { mutableStateOf(initialTab) }
    var showStyle by remember(visible) { mutableStateOf(false) }
    PlatformBackHandler(enabled = visible) {
        if (showStyle) showStyle = false else onDismiss()
    }

    val subtitles = rememberSubtitleColumnState(
        visible = visible,
        subtitleTracks = subtitleTracks,
        selectedSubtitleIndex = selectedSubtitleIndex,
        isSubtitleOffSelected = isSubtitleOffSelected,
        addonSubtitles = addonSubtitles,
        selectedAddonSubtitleId = selectedAddonSubtitleId,
        selectedAddonSubtitle = selectedAddonSubtitle,
        preferredSubtitleLanguage = preferredSubtitleLanguage,
        secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
        showOnlyPreferredLanguages = subtitleStyle.showOnlyPreferredLanguages,
        localSubtitleName = localSubtitleName,
    )

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val television = LocalTelevisionUi.current
            val wide = television || (maxWidth >= 900.dp && maxHeight >= 480.dp)
            val metrics = when {
                television -> TelevisionMetrics
                wide -> WideMetrics
                else -> PhoneMetrics
            }

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 5 },
                exit = fadeOut(tween(180)) + slideOutHorizontally(tween(200)) { it / 5 },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(metrics.margin),
            ) {
                val shape = RoundedCornerShape(20.dp)
                Column(
                    modifier = Modifier
                        .widthIn(max = metrics.maxPanelWidth)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .clip(shape)
                        .background(PanelBackground)
                        .border(1.dp, PanelBorder, shape)
                        // Taps inside the panel must not reach the dismissing scrim.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .padding(metrics.panelPadding),
                    verticalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 8.dp),
                ) {
                    PanelHeader(
                        title = stringResource(if (showStyle) Res.string.compose_player_style else Res.string.player_tracks_title),
                        showBack = showStyle,
                        metrics = metrics,
                        tabs = if (!wide && !showStyle) tab else null,
                        onTabSelected = { tab = it },
                        onBack = { showStyle = false },
                        onClose = onDismiss,
                    )

                    when {
                        showStyle -> Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            SubtitleStylePanel(
                                style = subtitleStyle,
                                subtitleDelayMs = subtitleDelayMs,
                                selectedAddonSubtitle = subtitles.selectedAddonSubtitle,
                                subtitleAutoSyncState = subtitleAutoSyncState,
                                isCompact = !wide,
                                showHeader = false,
                                onStyleChanged = onStyleChanged,
                                onSubtitleDelayChanged = onSubtitleDelayChanged,
                                onSubtitleDelayReset = onSubtitleDelayReset,
                                onAutoSyncCapture = onAutoSyncCapture,
                                onAutoSyncCueSelected = onAutoSyncCueSelected,
                                onAutoSyncReload = onAutoSyncReload,
                            )
                        }

                        wide -> Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(metrics.panelPadding),
                        ) {
                            AudioColumn(
                                visible = visible,
                                requestFocus = initialTab == TrackPanelTab.Audio,
                                audioTracks = audioTracks,
                                selectedAudioIndex = selectedAudioIndex,
                                metrics = metrics,
                                onAudioTrackSelected = onAudioTrackSelected,
                                modifier = Modifier.width(metrics.audioColumnWidth).fillMaxHeight(),
                            )
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(PanelBorder),
                            )
                            SubtitleColumn(
                                visible = visible,
                                requestFocus = initialTab == TrackPanelTab.Subtitles,
                                state = subtitles,
                                subtitleTracks = subtitleTracks,
                                addonSubtitles = addonSubtitles,
                                isLoadingAddonSubtitles = isLoadingAddonSubtitles,
                                subtitleDelayMs = subtitleDelayMs,
                                localSubtitleName = localSubtitleName,
                                canChooseLocalSubtitle = canChooseLocalSubtitle,
                                metrics = metrics,
                                showHeading = true,
                                onBuiltInTrackSelected = onBuiltInTrackSelected,
                                onAddonSubtitleSelected = onAddonSubtitleSelected,
                                onChooseLocalSubtitle = onChooseLocalSubtitle,
                                onRemoveLocalSubtitle = onRemoveLocalSubtitle,
                                onFetchAddonSubtitles = onFetchAddonSubtitles,
                                onSubtitleDelayChanged = onSubtitleDelayChanged,
                                onShowStyle = { showStyle = true },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }

                        tab == TrackPanelTab.Audio -> AudioColumn(
                            visible = visible,
                            requestFocus = true,
                            audioTracks = audioTracks,
                            selectedAudioIndex = selectedAudioIndex,
                            metrics = metrics,
                            showHeading = false,
                            onAudioTrackSelected = onAudioTrackSelected,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )

                        else -> SubtitleColumn(
                            visible = visible,
                            requestFocus = true,
                            state = subtitles,
                            subtitleTracks = subtitleTracks,
                            addonSubtitles = addonSubtitles,
                            isLoadingAddonSubtitles = isLoadingAddonSubtitles,
                            subtitleDelayMs = subtitleDelayMs,
                            localSubtitleName = localSubtitleName,
                            canChooseLocalSubtitle = canChooseLocalSubtitle,
                            metrics = metrics,
                            showHeading = false,
                            onBuiltInTrackSelected = onBuiltInTrackSelected,
                            onAddonSubtitleSelected = onAddonSubtitleSelected,
                            onChooseLocalSubtitle = onChooseLocalSubtitle,
                            onRemoveLocalSubtitle = onRemoveLocalSubtitle,
                            onFetchAddonSubtitles = onFetchAddonSubtitles,
                            onSubtitleDelayChanged = onSubtitleDelayChanged,
                            onShowStyle = { showStyle = true },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** Subtitle choices shared by both layouts so switching tabs keeps the pending selection. */
private class SubtitleColumnState(
    val chips: List<SubtitleLanguageItem>,
    val activeLanguageKey: String,
    val options: List<SubtitleSelectionOption>,
    val selectedOptionId: String,
    val selectedAddonSubtitle: AddonSubtitle?,
    val selectLanguage: (String) -> Unit,
    val selectOption: (String) -> Unit,
)

@Composable
private fun rememberSubtitleColumnState(
    visible: Boolean,
    subtitleTracks: List<SubtitleTrack>,
    selectedSubtitleIndex: Int,
    isSubtitleOffSelected: Boolean,
    addonSubtitles: List<AddonSubtitle>,
    selectedAddonSubtitleId: String?,
    selectedAddonSubtitle: AddonSubtitle?,
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    showOnlyPreferredLanguages: Boolean,
    localSubtitleName: String?,
): SubtitleColumnState {
    val effectiveAddon = selectedAddonSubtitle ?: addonSubtitles.findSelectedAddon(selectedAddonSubtitleId)
    val playbackLanguageKey = selectedSubtitleLanguageKey(
        subtitleTracks = subtitleTracks,
        selectedSubtitleIndex = selectedSubtitleIndex,
        selectedAddonSubtitle = effectiveAddon,
        isExplicitlyDisabled = isSubtitleOffSelected,
    )
    val playbackOptionId = when {
        localSubtitleName != null -> LocalSubtitleOptionId
        else -> selectedSubtitleOptionId(
            subtitleTracks = subtitleTracks,
            selectedSubtitleIndex = selectedSubtitleIndex,
            selectedAddonSubtitle = effectiveAddon,
            isExplicitlyDisabled = isSubtitleOffSelected,
        ) ?: SubtitleOffOptionId
    }
    val subtitleKey = subtitleTracksStructureKey(subtitleTracks)
    val addonKey = addonSubtitlesStructureKey(addonSubtitles)
    val chips = remember(
        subtitleKey,
        addonKey,
        preferredSubtitleLanguage,
        secondaryPreferredSubtitleLanguage,
        showOnlyPreferredLanguages,
        playbackLanguageKey,
    ) {
        buildSubtitleLanguageItems(
            subtitleTracks = subtitleTracks,
            addonSubtitles = addonSubtitles,
            preferredLanguage = preferredSubtitleLanguage,
            secondaryPreferredLanguage = secondaryPreferredSubtitleLanguage,
            showOnlyPreferredLanguages = showOnlyPreferredLanguages,
            selectedLanguageKey = playbackLanguageKey,
        ).filter { it.key != SubtitleOffLanguageKey }
    }
    fun defaultLanguage(): String =
        playbackLanguageKey.takeIf { key -> chips.any { it.key == key } }
            ?: chips.firstOrNull()?.key
            ?: SubtitleOffLanguageKey

    var activeLanguageKey by remember(visible) { mutableStateOf(defaultLanguage()) }
    var pendingOptionId by remember(visible) { mutableStateOf<String?>(null) }
    LaunchedEffect(chips) {
        if (chips.none { it.key == activeLanguageKey }) activeLanguageKey = defaultLanguage()
    }
    // Once the player reports a new track, show what is actually playing.
    LaunchedEffect(playbackOptionId, playbackLanguageKey) {
        pendingOptionId = null
        if (chips.any { it.key == playbackLanguageKey }) activeLanguageKey = playbackLanguageKey
    }
    val options = remember(activeLanguageKey, subtitleKey, addonKey) {
        buildSubtitleSelectionOptions(activeLanguageKey, subtitleTracks, addonSubtitles)
    }
    return SubtitleColumnState(
        chips = chips,
        activeLanguageKey = activeLanguageKey,
        options = options,
        selectedOptionId = pendingOptionId ?: playbackOptionId,
        selectedAddonSubtitle = effectiveAddon,
        selectLanguage = { activeLanguageKey = it },
        selectOption = { pendingOptionId = it },
    )
}

@Composable
private fun PanelHeader(
    title: String,
    showBack: Boolean,
    metrics: TrackPanelMetrics,
    tabs: TrackPanelTab?,
    onTabSelected: (TrackPanelTab) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val closeLabel = stringResource(Res.string.action_close)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.action_back), metrics.controlSize, onClick = onBack)
        }
        if (tabs != null) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(QuietFill)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TrackPanelTab.entries.forEach { entry ->
                    val selected = entry == tabs
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(metrics.controlSize - 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color(0xFFF5F7F8) else Color.Transparent)
                            .semantics {
                                role = Role.Tab
                                this.selected = selected
                            }
                            .clickable { onTabSelected(entry) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(
                                if (entry == TrackPanelTab.Audio) Res.string.compose_player_audio
                                else Res.string.compose_player_subtitles,
                            ),
                            color = if (selected) Color(0xFF111111) else MaterialTheme.nuvio.colors.textSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.nuvio.colors.textPrimary,
                fontSize = metrics.headerSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoundIconButton(Icons.Rounded.Close, closeLabel, metrics.controlSize, onClick = onClose)
    }
}

@Composable
private fun ColumnHeading(text: String, count: Int, metrics: TrackPanelMetrics) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text.uppercase(),
            color = MaterialTheme.nuvio.colors.textMuted,
            fontSize = metrics.sectionSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            maxLines = 1,
        )
        if (count > 1) {
            Text(
                text = count.toString(),
                color = MaterialTheme.nuvio.colors.textMuted,
                fontSize = metrics.sectionSize,
            )
        }
    }
}

@Composable
private fun AudioColumn(
    visible: Boolean,
    requestFocus: Boolean,
    audioTracks: List<AudioTrack>,
    selectedAudioIndex: Int,
    metrics: TrackPanelMetrics,
    onAudioTrackSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showHeading: Boolean = true,
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val selectedPosition = audioTracks.indexOfFirst { it.index == selectedAudioIndex }
    FocusSelectedRowOnOpen(visible, requestFocus, listState, selectedPosition, focusRequester)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showHeading) ColumnHeading(stringResource(Res.string.compose_player_audio), audioTracks.size, metrics)
        if (audioTracks.isEmpty()) {
            Text(
                text = stringResource(Res.string.compose_player_no_audio_tracks_available),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                color = MaterialTheme.nuvio.colors.textMuted,
                fontSize = metrics.titleSize,
            )
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
        ) {
            items(audioTracks, key = { "${it.index}:${it.id}" }) { track ->
                val selected = track.index == selectedAudioIndex
                val parts = describeTrackLabel(
                    label = track.label,
                    languageCode = track.language,
                    languageName = knownLanguageLabelForCode(track.language),
                    kind = TrackKind.Audio,
                )
                TrackRow(
                    title = parts.title ?: stringResource(Res.string.compose_player_track_number, track.index + 1),
                    badges = parts.badges.map { badgeLabel(it) },
                    detail = parts.detail,
                    selected = selected,
                    metrics = metrics,
                    onClick = { onAudioTrackSelected(track.index) },
                    modifier = if (selected) Modifier.focusRequester(focusRequester) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun SubtitleColumn(
    visible: Boolean,
    requestFocus: Boolean,
    state: SubtitleColumnState,
    subtitleTracks: List<SubtitleTrack>,
    addonSubtitles: List<AddonSubtitle>,
    isLoadingAddonSubtitles: Boolean,
    subtitleDelayMs: Int,
    localSubtitleName: String?,
    canChooseLocalSubtitle: Boolean,
    metrics: TrackPanelMetrics,
    showHeading: Boolean,
    onBuiltInTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (AddonSubtitle) -> Unit,
    onChooseLocalSubtitle: () -> Unit,
    onRemoveLocalSubtitle: () -> Unit,
    onFetchAddonSubtitles: () -> Unit,
    onSubtitleDelayChanged: (Int) -> Unit,
    onShowStyle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyedOptions = remember(state.options) { state.options.withDuplicateSafeLazyKeys { it.id } }
    val leadingItems = 1 + (if (localSubtitleName != null) 1 else 0) + (if (state.chips.size > 1) 1 else 0)
    val selectedPosition = when (state.selectedOptionId) {
        SubtitleOffOptionId -> 0
        LocalSubtitleOptionId -> 1
        else -> state.options.indexOfFirst { it.id == state.selectedOptionId }.takeIf { it >= 0 }?.plus(leadingItems) ?: 0
    }
    FocusSelectedRowOnOpen(visible, requestFocus, listState, selectedPosition, focusRequester)

    val builtInLabel = stringResource(Res.string.compose_player_built_in)
    val addonLabel = stringResource(Res.string.addon_title)
    val hasAddonOptions = addonSubtitles.isNotEmpty()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showHeading) {
            ColumnHeading(stringResource(Res.string.compose_player_subtitles), subtitleTracks.size + addonSubtitles.size, metrics)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
        ) {
            item(key = "off") {
                val selected = state.selectedOptionId == SubtitleOffOptionId
                TrackRow(
                    title = stringResource(Res.string.compose_action_off),
                    badges = emptyList(),
                    detail = null,
                    selected = selected,
                    metrics = metrics,
                    onClick = {
                        state.selectOption(SubtitleOffOptionId)
                        onBuiltInTrackSelected(-1)
                    },
                    modifier = if (selected) Modifier.focusRequester(focusRequester) else Modifier,
                )
            }
            if (localSubtitleName != null) {
                item(key = "local") {
                    TrackRow(
                        title = localSubtitleName,
                        badges = emptyList(),
                        detail = stringResource(Res.string.player_local_subtitle_label),
                        selected = state.selectedOptionId == LocalSubtitleOptionId,
                        metrics = metrics,
                        onClick = {},
                        modifier = Modifier.focusRequester(focusRequester),
                        trailing = {
                            PillButton(
                                label = stringResource(Res.string.player_local_subtitle_remove),
                                metrics = metrics,
                                onClick = onRemoveLocalSubtitle,
                            )
                        },
                    )
                }
            }
            if (state.chips.size > 1) {
                item(key = "chips") {
                    LanguageChips(
                        chips = state.chips,
                        activeKey = state.activeLanguageKey,
                        metrics = metrics,
                        onSelect = state.selectLanguage,
                    )
                }
            }
            items(keyedOptions, key = { it.lazyKey }) { entry ->
                val option = entry.value
                val selected = option.id == state.selectedOptionId
                val rowModifier = if (selected) Modifier.focusRequester(focusRequester) else Modifier
                when (option) {
                    is SubtitleSelectionOption.BuiltIn -> {
                        val parts = describeTrackLabel(
                            label = option.track.label,
                            languageCode = option.track.language,
                            languageName = knownLanguageLabelForCode(option.track.language),
                            kind = TrackKind.Subtitle,
                            isForced = option.track.isForced,
                        )
                        TrackRow(
                            title = parts.title ?: chipLabel(state.activeLanguageKey),
                            badges = parts.badges.map { badgeLabel(it) },
                            detail = listOf(builtInLabel, parts.detail).filter { it.isNotBlank() }.joinToString(" · "),
                            selected = selected,
                            metrics = metrics,
                            onClick = {
                                state.selectOption(option.id)
                                onBuiltInTrackSelected(option.track.index)
                            },
                            modifier = rowModifier,
                        )
                    }

                    is SubtitleSelectionOption.Addon -> {
                        val title = languageLabelForCode(option.subtitle.language)
                        TrackRow(
                            title = title,
                            badges = emptyList(),
                            detail = listOfNotNull(
                                option.subtitle.addonName ?: addonLabel,
                                option.subtitle.display.takeIf { it.isNotBlank() && it != title },
                            ).joinToString(" · "),
                            detailMaxLines = 2,
                            selected = selected,
                            metrics = metrics,
                            onClick = {
                                state.selectOption(option.id)
                                onAddonSubtitleSelected(option.subtitle)
                            },
                            modifier = rowModifier,
                        )
                    }
                }
            }
            when {
                isLoadingAddonSubtitles -> item(key = "loading") {
                    PlayerModalLoading(modifier = Modifier.padding(vertical = 16.dp))
                }
                !hasAddonOptions -> item(key = "fetch") {
                    ActionRow(
                        icon = Icons.Rounded.CloudDownload,
                        label = stringResource(Res.string.compose_player_fetch_subtitles),
                        metrics = metrics,
                        onClick = onFetchAddonSubtitles,
                    )
                }
            }
            if (isDesktop) {
                item(key = "open-file") {
                    ActionRow(
                        icon = Icons.Rounded.FolderOpen,
                        label = stringResource(Res.string.player_local_subtitle_choose),
                        metrics = metrics,
                        enabled = canChooseLocalSubtitle,
                        onClick = onChooseLocalSubtitle,
                    )
                }
            }
        }
        SubtitleFooter(
            subtitleDelayMs = subtitleDelayMs,
            metrics = metrics,
            onSubtitleDelayChanged = onSubtitleDelayChanged,
            onShowStyle = onShowStyle,
        )
    }
}

@Composable
private fun LanguageChips(
    chips: List<SubtitleLanguageItem>,
    activeKey: String,
    metrics: TrackPanelMetrics,
    onSelect: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(activeKey) {
        val index = chips.indexOfFirst { it.key == activeKey }
        if (index >= 0) listState.scrollItemIntoViewIfNeeded(index)
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(chips, key = { it.key }) { chip ->
            val active = chip.key == activeKey
            var focused by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .height(if (metrics.focusScale > 1f) 44.dp else 36.dp)
                    .clip(CircleShape)
                    .background(if (active) Color(0xFFF5F7F8) else Color.White.copy(alpha = 0.08f))
                    .border(2.dp, if (focused) Color(0xFFF5F7F8) else Color.Transparent, CircleShape)
                    .onFocusChanged { focused = it.hasFocus }
                    .semantics { selected = active }
                    .clickable { onSelect(chip.key) }
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = chipLabel(chip.key),
                    color = if (active) Color(0xFF111111) else MaterialTheme.nuvio.colors.textSecondary,
                    fontSize = metrics.detailSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (chip.count > 0) {
                    Text(
                        text = chip.count.toString(),
                        color = (if (active) Color(0xFF111111) else MaterialTheme.nuvio.colors.textSecondary).copy(alpha = 0.7f),
                        fontSize = metrics.detailSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleFooter(
    subtitleDelayMs: Int,
    metrics: TrackPanelMetrics,
    onSubtitleDelayChanged: (Int) -> Unit,
    onShowStyle: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(PanelBorder))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.compose_player_subtitle_delay),
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                color = tokens.colors.textSecondary,
                fontSize = metrics.detailSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.clip(CircleShape).background(QuietFill),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(
                    icon = Icons.Rounded.Remove,
                    contentDescription = stringResource(
                        Res.string.cd_decrease_value,
                        stringResource(Res.string.compose_player_subtitle_delay),
                    ),
                    size = metrics.controlSize,
                    enabled = subtitleDelayMs > SUBTITLE_DELAY_MIN_MS,
                    background = Color.Transparent,
                ) {
                    onSubtitleDelayChanged((subtitleDelayMs - SUBTITLE_DELAY_STEP_MS).coerceAtLeast(SUBTITLE_DELAY_MIN_MS))
                }
                Text(
                    text = formatTrackPanelDelay(subtitleDelayMs),
                    modifier = Modifier.widthIn(min = 60.dp),
                    color = if (subtitleDelayMs != 0) tokens.colors.accentStrong else tokens.colors.textPrimary,
                    fontSize = metrics.detailSize,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                RoundIconButton(
                    icon = Icons.Rounded.Add,
                    contentDescription = stringResource(
                        Res.string.cd_increase_value,
                        stringResource(Res.string.compose_player_subtitle_delay),
                    ),
                    size = metrics.controlSize,
                    enabled = subtitleDelayMs < SUBTITLE_DELAY_MAX_MS,
                    background = Color.Transparent,
                ) {
                    onSubtitleDelayChanged((subtitleDelayMs + SUBTITLE_DELAY_STEP_MS).coerceAtMost(SUBTITLE_DELAY_MAX_MS))
                }
            }
            RoundIconButton(
                icon = Icons.Rounded.TextFields,
                contentDescription = stringResource(Res.string.compose_player_style),
                size = metrics.controlSize,
                onClick = onShowStyle,
            )
        }
    }
}

/** One audio or subtitle choice: radio, line 1 with badges, line 2 with the facts. */
@Composable
private fun TrackRow(
    title: String,
    badges: List<String>,
    detail: String?,
    selected: Boolean,
    metrics: TrackPanelMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detailMaxLines: Int = 1,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val background = when {
        selected && focused -> tokens.colors.accent.copy(alpha = 0.28f)
        selected -> tokens.colors.accent.copy(alpha = 0.16f)
        focused -> Color.White.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = metrics.rowMinHeight)
            .scale(if (focused) metrics.focusScale else 1f)
            .clip(shape)
            .background(background)
            .border(2.dp, if (focused) Color(0xFFF5F7F8) else Color.Transparent, shape)
            .onFocusChanged { focused = it.hasFocus }
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
            }
            .clickableIncludingFlingStop(onClick)
            .padding(horizontal = metrics.rowPaddingH, vertical = metrics.rowPaddingV),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(metrics.radioSize)
                .border(2.dp, if (selected) tokens.colors.accent else Color.White.copy(alpha = 0.34f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(metrics.radioDot).clip(CircleShape).background(tokens.colors.accent))
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false),
                    color = tokens.colors.textPrimary,
                    fontSize = metrics.titleSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                badges.forEach { badge ->
                    Text(
                        text = badge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        color = Color.White.copy(alpha = 0.74f),
                        fontSize = metrics.badgeSize,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    color = tokens.colors.textMuted,
                    fontSize = metrics.detailSize,
                    maxLines = detailMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    metrics: TrackPanelMetrics,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = metrics.controlSize)
            .clip(shape)
            .border(2.dp, if (focused) Color(0xFFF5F7F8) else Color.Transparent, shape)
            .onFocusChanged { focused = it.hasFocus }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = metrics.rowPaddingH, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = MaterialTheme.nuvio.colors.textSecondary.copy(alpha = if (enabled) 1f else 0.4f)
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text = label, color = color, fontSize = metrics.detailSize, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PillButton(label: String, metrics: TrackPanelMetrics, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(QuietFill)
            .border(2.dp, if (focused) Color(0xFFF5F7F8) else Color.Transparent, CircleShape)
            .onFocusChanged { focused = it.hasFocus }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.nuvio.colors.textPrimary, fontSize = metrics.detailSize, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    enabled: Boolean = true,
    background: Color = QuietFill,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (focused) Color.White.copy(alpha = 0.16f) else background)
            .border(2.dp, if (focused) Color(0xFFF5F7F8) else Color.Transparent, CircleShape)
            .onFocusChanged { focused = it.hasFocus }
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.nuvio.colors.textPrimary.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

/** Scrolls the playing track into view and, for remotes and keyboards, focuses it. */
@Composable
private fun FocusSelectedRowOnOpen(
    visible: Boolean,
    requestFocus: Boolean,
    listState: LazyListState,
    selectedPosition: Int,
    focusRequester: FocusRequester,
) {
    // Touch screens get no focus ring; only a remote needs focus placed in the panel.
    val television = LocalTelevisionUi.current
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        // Wait for the first layout so rows already on screen (like "Off") are not scrolled away.
        withFrameNanos { }
        if (selectedPosition >= 0) listState.scrollItemIntoViewIfNeeded(selectedPosition)
        if (!requestFocus || !television) return@LaunchedEffect
        delay(60)
        runCatching { focusRequester.requestFocus() }
    }
}

@Composable
private fun badgeLabel(badge: TrackBadge): String = stringResource(
    when (badge) {
        TrackBadge.Forced -> Res.string.settings_playback_option_forced
        TrackBadge.Sdh -> Res.string.player_track_badge_sdh
        TrackBadge.Commentary -> Res.string.player_track_badge_commentary
        TrackBadge.Original -> Res.string.player_track_badge_original
        TrackBadge.Description -> Res.string.player_track_badge_description
    },
)

@Composable
private fun chipLabel(key: String): String = when (key) {
    SubtitleUnknownLanguageKey -> stringResource(Res.string.subtitle_language_unknown)
    else -> languageLabelForCode(key)
}

internal fun formatTrackPanelDelay(delayMs: Int): String {
    if (delayMs == 0) return "0.0 s"
    val absolute = kotlin.math.abs(delayMs)
    val whole = absolute / 1000
    val fraction = absolute % 1000
    val decimals = if (fraction % 100 == 0) (fraction / 100).toString() else (fraction / 10).toString().padStart(2, '0')
    return "${if (delayMs > 0) "+" else "−"}$whole.$decimals s"
}

private suspend fun LazyListState.scrollItemIntoViewIfNeeded(targetIndex: Int) {
    if (targetIndex < 0) return
    if (layoutInfo.visibleItemsInfo.any { it.index == targetIndex }) return
    scrollToItem(targetIndex)
}
