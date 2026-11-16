package com.nuvio.app.features.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_tab_next_episode
import nuvio.composeapp.generated.resources.settings_playback_tab_player
import nuvio.composeapp.generated.resources.settings_playback_tab_streams
import nuvio.composeapp.generated.resources.settings_playback_tab_subtitles_audio
import nuvio.composeapp.generated.resources.settings_playback_tab_video
import nuvio.composeapp.generated.resources.settings_playback_tab_video_p2p
import org.jetbrains.compose.resources.StringResource

/** The desktop Playback page shows one of these at a time instead of one very long page. */
internal enum class PlaybackSettingsTab(val labelRes: StringResource) {
    Player(Res.string.settings_playback_tab_player),
    SubtitlesAudio(Res.string.settings_playback_tab_subtitles_audio),
    Streams(Res.string.settings_playback_tab_streams),
    NextEpisode(Res.string.settings_playback_tab_next_episode),
    Video(Res.string.settings_playback_tab_video),
    ;

    /** Video also holds the P2P settings when that feature is available. */
    fun labelRes(p2pVisible: Boolean): StringResource =
        if (this == Video && p2pVisible) Res.string.settings_playback_tab_video_p2p else labelRes

    companion object {
        val Default = SubtitlesAudio

        fun fromName(name: String?): PlaybackSettingsTab =
            entries.firstOrNull { it.name == name } ?: Default
    }
}

/**
 * Tab holding a Playback search result, from its stable search key (see SettingsSearch).
 * Unknown keys return null, so the open tab is kept.
 */
internal fun playbackTabForSearchKey(key: String?): PlaybackSettingsTab? {
    val row = key?.removePrefix("playback-") ?: return null
    return when (row) {
        "loading-overlay", "pause-overlay", "external-player", "external-player-app",
        "legacy-player-layout", "hold-to-speed", "touch-gestures", "hold-speed",
        -> PlaybackSettingsTab.Player
        "preferred-audio", "secondary-audio", "preferred-subtitles", "secondary-subtitles",
        "libass", "libass-render",
        -> PlaybackSettingsTab.SubtitlesAudio
        "reuse-last-link", "last-link-cache", "stream-mode", "regex-pattern", "stream-timeout",
        "source-scope", "allowed-addons", "allowed-plugins",
        -> PlaybackSettingsTab.Streams
        "skip-intro", "anime-skip", "anime-skip-client", "intro-submit", "introdb-key",
        "auto-play-next", "prefer-binge", "threshold-mode", "threshold-percent", "threshold-minutes",
        -> PlaybackSettingsTab.NextEpisode
        "decoder-priority", "dv7-hevc", "tunneled-playback", "rtx-vsr", "rtx-setup",
        -> PlaybackSettingsTab.Video
        else -> null
    }
}

internal data class DesktopSettingsTab(
    val id: String,
    val label: String,
)

/** A row of tabs under a desktop settings page header, with an underline on the open one. */
@Composable
internal fun DesktopSettingsTabRow(
    tabs: List<DesktopSettingsTab>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEach { tab ->
                DesktopSettingsTabItem(
                    label = tab.label,
                    selected = tab.id == selectedId,
                    onClick = { onSelect(tab.id) },
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = tokens.colors.borderSubtle)
    }
}

@Composable
private fun DesktopSettingsTabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val background by animateColorAsState(
        when {
            focused -> tokens.colors.textPrimary.copy(alpha = 0.10f)
            hovered && !selected -> tokens.colors.overlayHover
            else -> Color.Transparent
        },
        tween(NuvioTokens.Motion.fastMillis),
        label = "settings_tab_background",
    )
    val indicator by animateColorAsState(
        if (selected) tokens.colors.accent else Color.Transparent,
        tween(NuvioTokens.Motion.fastMillis),
        label = "settings_tab_indicator",
    )
    Column(
        modifier = Modifier.width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                .background(background)
                .clickable(interactionSource = interactionSource, indication = null, role = Role.Tab, onClick = onClick)
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected || hovered) tokens.colors.textPrimary else tokens.colors.textSecondary,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(indicator),
        )
    }
}
