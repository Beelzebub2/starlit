package com.nuvio.app.features.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.NuvioPillButton
import com.nuvio.app.core.ui.NuvioPillButtonStyle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.DebridSettings
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.features.player.languageLabelForCode
import com.nuvio.app.features.profiles.ActiveProfileMiniAvatar
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.addons_desktop_stat_installed
import nuvio.composeapp.generated.resources.compose_action_off
import nuvio.composeapp.generated.resources.compose_nav_profile
import nuvio.composeapp.generated.resources.compose_settings_page_tracking
import nuvio.composeapp.generated.resources.settings_debrid_connected
import nuvio.composeapp.generated.resources.settings_desktop_about
import nuvio.composeapp.generated.resources.settings_desktop_addons
import nuvio.composeapp.generated.resources.settings_desktop_current_version
import nuvio.composeapp.generated.resources.settings_desktop_debrid
import nuvio.composeapp.generated.resources.settings_desktop_manage_account
import nuvio.composeapp.generated.resources.settings_desktop_quick_settings
import nuvio.composeapp.generated.resources.settings_desktop_subtitle_language
import nuvio.composeapp.generated.resources.settings_desktop_switch_profile
import nuvio.composeapp.generated.resources.settings_desktop_updates
import nuvio.composeapp.generated.resources.settings_playback_auto_play_next_episode
import nuvio.composeapp.generated.resources.settings_playback_auto_play_next_episode_description
import nuvio.composeapp.generated.resources.settings_playback_option_device_language
import nuvio.composeapp.generated.resources.settings_playback_option_forced
import nuvio.composeapp.generated.resources.settings_playback_option_none
import nuvio.composeapp.generated.resources.settings_playback_preferred_subtitle_language
import nuvio.composeapp.generated.resources.settings_playback_reuse_last_link
import nuvio.composeapp.generated.resources.settings_playback_reuse_last_link_description
import nuvio.composeapp.generated.resources.settings_playback_skip_intro_outro_recap
import nuvio.composeapp.generated.resources.settings_playback_skip_intro_outro_recap_description
import nuvio.composeapp.generated.resources.settings_tracking_not_connected
import org.jetbrains.compose.resources.stringResource

/**
 * The desktop settings home: who is signed in, how connected services stand, and the
 * playback settings people change most, each one click away.
 */
internal fun LazyListScope.desktopOverviewContent(
    debridSettings: DebridSettings,
    traktConnected: Boolean,
    simklConnected: Boolean,
    preferredSubtitleLanguage: String,
    streamReuseLastLinkEnabled: Boolean,
    updatesAvailable: Boolean,
    onSwitchProfile: (() -> Unit)?,
    onOpenEntry: (DesktopSettingsEntry) -> Unit,
    onOpenPreferredSubtitles: (title: String) -> Unit,
) {
    item(key = "desktop-overview-account") {
        OverviewAccountCard(
            onSwitchProfile = onSwitchProfile,
            onManageAccount = { onOpenEntry(DesktopSettingsEntry.Account) },
        )
    }
    item(key = "desktop-overview-status") {
        OverviewStatusGrid(
            debridSettings = debridSettings,
            traktConnected = traktConnected,
            simklConnected = simklConnected,
            updatesAvailable = updatesAvailable,
            onOpenEntry = onOpenEntry,
        )
    }
    item(key = "desktop-overview-quick-settings") {
        OverviewQuickSettings(
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
            onOpenPreferredSubtitles = onOpenPreferredSubtitles,
        )
    }
}

@Composable
private fun OverviewAccountCard(
    onSwitchProfile: (() -> Unit)?,
    onManageAccount: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val profile = profileState.activeProfile
    val email = (authState as? AuthState.Authenticated)?.takeUnless { it.isAnonymous }?.email
    val shape = RoundedCornerShape(NuvioTokens.Radius.lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.colors.surface)
            .border(NuvioTokens.Border.thin, tokens.colors.borderSubtle, shape)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActiveProfileMiniAvatar(profile = profile, avatars = avatars, selected = false, size = 52)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = profile?.name?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.compose_nav_profile),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (email != null) {
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onSwitchProfile != null) {
            NuvioPillButton(
                text = stringResource(Res.string.settings_desktop_switch_profile),
                onClick = onSwitchProfile,
                style = NuvioPillButtonStyle.Primary,
            )
        }
        NuvioPillButton(
            text = stringResource(Res.string.settings_desktop_manage_account),
            onClick = onManageAccount,
        )
    }
}

private data class OverviewStatus(
    val entry: DesktopSettingsEntry,
    val icon: ImageVector,
    val label: String,
    val value: String,
    val caption: String?,
    val active: Boolean,
)

@Composable
private fun OverviewStatusGrid(
    debridSettings: DebridSettings,
    traktConnected: Boolean,
    simklConnected: Boolean,
    updatesAvailable: Boolean,
    onOpenEntry: (DesktopSettingsEntry) -> Unit,
) {
    val addonState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val connected = stringResource(Res.string.settings_debrid_connected)
    val notConnected = stringResource(Res.string.settings_tracking_not_connected)
    val debridNames = DebridProviders.configuredServices(debridSettings).map { it.provider.displayName }.distinct()
    val trackingNames = buildList {
        if (traktConnected) add("Trakt")
        if (simklConnected) add("Simkl")
    }
    val version = stringResource(Res.string.settings_desktop_current_version, AppVersionPolicy.displayVersionName)
    val statuses = listOf(
        OverviewStatus(
            entry = DesktopSettingsEntry.Addons,
            icon = Icons.Rounded.Extension,
            label = stringResource(Res.string.settings_desktop_addons),
            value = addonState.addons.size.toString(),
            caption = stringResource(Res.string.addons_desktop_stat_installed),
            active = addonState.addons.isNotEmpty(),
        ),
        OverviewStatus(
            entry = DesktopSettingsEntry.Debrid,
            icon = Icons.Rounded.Cloud,
            label = stringResource(Res.string.settings_desktop_debrid),
            value = debridNames.joinToString().ifEmpty { "—" },
            caption = when {
                debridNames.isEmpty() -> notConnected
                debridSettings.enabled -> connected
                else -> stringResource(Res.string.compose_action_off)
            },
            active = debridNames.isNotEmpty() && debridSettings.enabled,
        ),
        OverviewStatus(
            entry = DesktopSettingsEntry.Tracking,
            icon = Icons.Rounded.Sync,
            label = stringResource(Res.string.compose_settings_page_tracking),
            value = trackingNames.joinToString().ifEmpty { "—" },
            caption = if (trackingNames.isEmpty()) notConnected else connected,
            active = trackingNames.isNotEmpty(),
        ),
        if (updatesAvailable) {
            OverviewStatus(
                entry = DesktopSettingsEntry.Updates,
                icon = Icons.Rounded.SystemUpdateAlt,
                label = stringResource(Res.string.settings_desktop_updates),
                value = version,
                caption = null,
                active = true,
            )
        } else {
            OverviewStatus(
                entry = DesktopSettingsEntry.About,
                icon = Icons.Rounded.Info,
                label = stringResource(Res.string.settings_desktop_about),
                value = version,
                caption = null,
                active = true,
            )
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        statuses.chunked(2).forEach { row ->
            // Tiles in a row share a height even when only one of them has a caption.
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { status ->
                    OverviewStatusTile(
                        status = status,
                        onClick = { onOpenEntry(status.entry) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewStatusTile(
    status: OverviewStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(NuvioTokens.Radius.lg)
    val background by animateColorAsState(
        if (hovered || focused) tokens.colors.overlayHover else tokens.colors.surface,
        tween(NuvioTokens.Motion.fastMillis),
        label = "overview_tile_background",
    )
    Row(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(NuvioTokens.Border.thin, if (focused) tokens.colors.focusRing else tokens.colors.borderSubtle, shape)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(tokens.colors.textPrimary.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(status.icon, contentDescription = null, tint = tokens.colors.textSecondary, modifier = Modifier.size(19.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = status.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = tokens.colors.textMuted,
                maxLines = 1,
            )
            Text(
                text = status.value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status.caption != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (status.active) tokens.colors.success else tokens.colors.textDisabled),
                    )
                    Text(
                        text = status.caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewQuickSettings(
    preferredSubtitleLanguage: String,
    streamReuseLastLinkEnabled: Boolean,
    onOpenPreferredSubtitles: (title: String) -> Unit,
) {
    val playerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    val externalPlayerActive = AppFeaturePolicy.externalPlayerSupported && playerSettings.externalPlayerEnabled
    Column {
        SettingsSection(
            title = stringResource(Res.string.settings_desktop_quick_settings),
            isTablet = true,
        ) {
            SettingsGroup(isTablet = true) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_auto_play_next_episode),
                    description = stringResource(Res.string.settings_playback_auto_play_next_episode_description),
                    checked = playerSettings.streamAutoPlayNextEpisodeEnabled,
                    isTablet = true,
                    onCheckedChange = PlayerSettingsRepository::setStreamAutoPlayNextEpisodeEnabled,
                )
                SettingsGroupDivider(isTablet = true)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_skip_intro_outro_recap),
                    description = stringResource(Res.string.settings_playback_skip_intro_outro_recap_description),
                    checked = playerSettings.skipIntroEnabled,
                    enabled = !externalPlayerActive,
                    isTablet = true,
                    onCheckedChange = PlayerSettingsRepository::setSkipIntroEnabled,
                )
                SettingsGroupDivider(isTablet = true)
                // The Playback row is titled "Preferred Language"; here it needs to say which.
                val playbackRowTitle = stringResource(Res.string.settings_playback_preferred_subtitle_language)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_desktop_subtitle_language),
                    description = when (preferredSubtitleLanguage) {
                        SubtitleLanguageOption.NONE -> stringResource(Res.string.settings_playback_option_none)
                        SubtitleLanguageOption.DEVICE -> stringResource(Res.string.settings_playback_option_device_language)
                        SubtitleLanguageOption.FORCED -> stringResource(Res.string.settings_playback_option_forced)
                        else -> languageLabelForCode(preferredSubtitleLanguage)
                    },
                    isTablet = true,
                    onClick = { onOpenPreferredSubtitles(playbackRowTitle) },
                )
                SettingsGroupDivider(isTablet = true)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_reuse_last_link),
                    description = stringResource(Res.string.settings_playback_reuse_last_link_description),
                    checked = streamReuseLastLinkEnabled,
                    isTablet = true,
                    onCheckedChange = PlayerSettingsRepository::setStreamReuseLastLinkEnabled,
                )
            }
        }
    }
}
