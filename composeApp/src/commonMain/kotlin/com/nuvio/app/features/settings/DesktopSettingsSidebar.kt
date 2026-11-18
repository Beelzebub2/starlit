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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.profiles.ActiveProfileMiniAvatar
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_nav_profile
import nuvio.composeapp.generated.resources.compose_search_clear
import nuvio.composeapp.generated.resources.compose_settings_page_account
import nuvio.composeapp.generated.resources.compose_settings_page_advanced
import nuvio.composeapp.generated.resources.compose_settings_page_notifications
import nuvio.composeapp.generated.resources.compose_settings_page_playback
import nuvio.composeapp.generated.resources.compose_settings_page_plugins
import nuvio.composeapp.generated.resources.compose_settings_page_tracking
import nuvio.composeapp.generated.resources.compose_settings_root_notifications_description
import nuvio.composeapp.generated.resources.compose_settings_root_tracking_description
import nuvio.composeapp.generated.resources.settings_desktop_about
import nuvio.composeapp.generated.resources.settings_desktop_about_summary
import nuvio.composeapp.generated.resources.settings_desktop_account_summary
import nuvio.composeapp.generated.resources.settings_desktop_addons
import nuvio.composeapp.generated.resources.settings_desktop_addons_summary
import nuvio.composeapp.generated.resources.settings_desktop_advanced_summary
import nuvio.composeapp.generated.resources.settings_desktop_appearance
import nuvio.composeapp.generated.resources.settings_desktop_appearance_summary
import nuvio.composeapp.generated.resources.settings_desktop_continue_watching
import nuvio.composeapp.generated.resources.settings_desktop_continue_watching_summary
import nuvio.composeapp.generated.resources.settings_desktop_debrid
import nuvio.composeapp.generated.resources.settings_desktop_debrid_summary
import nuvio.composeapp.generated.resources.settings_desktop_details_page
import nuvio.composeapp.generated.resources.settings_desktop_details_page_summary
import nuvio.composeapp.generated.resources.settings_desktop_home_screen
import nuvio.composeapp.generated.resources.settings_desktop_home_screen_summary
import nuvio.composeapp.generated.resources.settings_desktop_metadata_ratings
import nuvio.composeapp.generated.resources.settings_desktop_metadata_ratings_summary
import nuvio.composeapp.generated.resources.settings_desktop_overview
import nuvio.composeapp.generated.resources.settings_desktop_overview_summary
import nuvio.composeapp.generated.resources.settings_desktop_playback_summary
import nuvio.composeapp.generated.resources.settings_desktop_plugins_summary
import nuvio.composeapp.generated.resources.settings_desktop_posters_previews
import nuvio.composeapp.generated.resources.settings_desktop_posters_previews_summary
import nuvio.composeapp.generated.resources.settings_desktop_switch_profile
import nuvio.composeapp.generated.resources.settings_desktop_updates
import nuvio.composeapp.generated.resources.settings_desktop_updates_summary
import nuvio.composeapp.generated.resources.settings_group_look_and_feel
import nuvio.composeapp.generated.resources.settings_group_sources
import nuvio.composeapp.generated.resources.settings_group_system
import nuvio.composeapp.generated.resources.settings_group_watching
import nuvio.composeapp.generated.resources.settings_group_you
import nuvio.composeapp.generated.resources.settings_search_placeholder
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One item in the desktop settings sidebar. Most open a [SettingsPage]; Overview, Updates and About
 * are desktop-only screens with no page of their own.
 */
internal enum class DesktopSettingsEntry(
    val labelRes: StringResource,
    val icon: ImageVector,
    val page: SettingsPage? = null,
    val summaryRes: StringResource? = null,
) {
    Overview(Res.string.settings_desktop_overview, Icons.Rounded.Dashboard, summaryRes = Res.string.settings_desktop_overview_summary),
    Account(Res.string.compose_settings_page_account, Icons.Rounded.AccountCircle, SettingsPage.Account, Res.string.settings_desktop_account_summary),
    Tracking(Res.string.compose_settings_page_tracking, Icons.Rounded.Sync, SettingsPage.TraktAuthentication, Res.string.compose_settings_root_tracking_description),
    Playback(Res.string.compose_settings_page_playback, Icons.Rounded.PlayCircle, SettingsPage.Playback, Res.string.settings_desktop_playback_summary),
    ContinueWatching(Res.string.settings_desktop_continue_watching, Icons.Rounded.History, SettingsPage.ContinueWatching, Res.string.settings_desktop_continue_watching_summary),
    Notifications(Res.string.compose_settings_page_notifications, Icons.Rounded.Notifications, SettingsPage.Notifications, Res.string.compose_settings_root_notifications_description),
    Appearance(Res.string.settings_desktop_appearance, Icons.Rounded.Palette, SettingsPage.Appearance, Res.string.settings_desktop_appearance_summary),
    HomeScreen(Res.string.settings_desktop_home_screen, Icons.Rounded.SpaceDashboard, SettingsPage.Homescreen, Res.string.settings_desktop_home_screen_summary),
    PostersAndPreviews(Res.string.settings_desktop_posters_previews, Icons.Rounded.Style, SettingsPage.PosterCustomization, Res.string.settings_desktop_posters_previews_summary),
    DetailsPage(Res.string.settings_desktop_details_page, Icons.AutoMirrored.Rounded.Article, SettingsPage.MetaScreen, Res.string.settings_desktop_details_page_summary),
    Addons(Res.string.settings_desktop_addons, Icons.Rounded.Extension, SettingsPage.Addons, Res.string.settings_desktop_addons_summary),
    Debrid(Res.string.settings_desktop_debrid, Icons.Rounded.Cloud, SettingsPage.Debrid, Res.string.settings_desktop_debrid_summary),
    Plugins(Res.string.compose_settings_page_plugins, Icons.Rounded.Widgets, SettingsPage.Plugins, Res.string.settings_desktop_plugins_summary),
    MetadataAndRatings(Res.string.settings_desktop_metadata_ratings, Icons.Rounded.Movie, SettingsPage.TmdbEnrichment, Res.string.settings_desktop_metadata_ratings_summary),
    Advanced(Res.string.compose_settings_page_advanced, Icons.Rounded.Tune, SettingsPage.Advanced, Res.string.settings_desktop_advanced_summary),
    Updates(Res.string.settings_desktop_updates, Icons.Rounded.SystemUpdateAlt, summaryRes = Res.string.settings_desktop_updates_summary),
    About(Res.string.settings_desktop_about, Icons.Rounded.Info, summaryRes = Res.string.settings_desktop_about_summary),
}

/** Desktop-only screens shown while the settings page itself is [SettingsPage.Root]. */
internal val DesktopSettingsEntry.isRootScreen: Boolean
    get() = this == DesktopSettingsEntry.Overview ||
        this == DesktopSettingsEntry.Updates ||
        this == DesktopSettingsEntry.About

/** A sidebar group. The first group, holding Overview, has no label. */
internal data class DesktopSettingsGroup(
    val labelRes: StringResource?,
    val entries: List<DesktopSettingsEntry>,
)

internal fun desktopSettingsGroups(updatesAvailable: Boolean = true): List<DesktopSettingsGroup> = listOf(
    DesktopSettingsGroup(null, listOf(DesktopSettingsEntry.Overview)),
    DesktopSettingsGroup(
        Res.string.settings_group_you,
        listOf(DesktopSettingsEntry.Account, DesktopSettingsEntry.Tracking),
    ),
    DesktopSettingsGroup(
        Res.string.settings_group_watching,
        buildList {
            add(DesktopSettingsEntry.Playback)
            add(DesktopSettingsEntry.ContinueWatching)
            if (AppFeaturePolicy.notificationsEnabled) add(DesktopSettingsEntry.Notifications)
        },
    ),
    DesktopSettingsGroup(
        Res.string.settings_group_look_and_feel,
        listOf(
            DesktopSettingsEntry.Appearance,
            DesktopSettingsEntry.HomeScreen,
            DesktopSettingsEntry.PostersAndPreviews,
            DesktopSettingsEntry.DetailsPage,
        ),
    ),
    DesktopSettingsGroup(
        Res.string.settings_group_sources,
        buildList {
            add(DesktopSettingsEntry.Addons)
            add(DesktopSettingsEntry.Debrid)
            if (AppFeaturePolicy.pluginsEnabled) add(DesktopSettingsEntry.Plugins)
            add(DesktopSettingsEntry.MetadataAndRatings)
        },
    ),
    DesktopSettingsGroup(
        Res.string.settings_group_system,
        buildList {
            add(DesktopSettingsEntry.Advanced)
            if (updatesAvailable) add(DesktopSettingsEntry.Updates)
            add(DesktopSettingsEntry.About)
        },
    ),
)

/** The sidebar entry that shows [this] page, if any. */
internal fun SettingsPage.desktopEntry(): DesktopSettingsEntry? = when (this) {
    // Opened from About, so About stays highlighted.
    SettingsPage.SupportersContributors, SettingsPage.LicensesAttributions -> DesktopSettingsEntry.About
    // Pages shown as a tab of another sidebar entry.
    SettingsPage.Streams -> DesktopSettingsEntry.Playback
    SettingsPage.HoverPreview -> DesktopSettingsEntry.PostersAndPreviews
    SettingsPage.MdbListRatings -> DesktopSettingsEntry.MetadataAndRatings
    else -> DesktopSettingsEntry.entries.firstOrNull { it.page == this }
}

/** Name used for [this] page on desktop: the sidebar label when the page has one. */
internal fun SettingsPage.desktopTitleRes(): StringResource =
    desktopEntry()?.takeUnless { it == DesktopSettingsEntry.About }?.labelRes ?: titleRes

/** Sidebar group of [entry], used as the page breadcrumb. */
internal fun desktopGroupLabelFor(entry: DesktopSettingsEntry): StringResource? =
    desktopSettingsGroups().firstOrNull { entry in it.entries }?.labelRes

/** Group for pages reached from a hub rather than listed in the sidebar themselves. */
internal fun SettingsPage.desktopHubGroupLabel(): StringResource? = when (this) {
    SettingsPage.Integrations, SettingsPage.ContentDiscovery -> Res.string.settings_group_sources
    else -> null
}

@Composable
internal fun DesktopSettingsSidebar(
    groups: List<DesktopSettingsGroup>,
    selectedEntry: DesktopSettingsEntry?,
    query: String,
    onQueryChange: (String) -> Unit,
    onEntryClick: (DesktopSettingsEntry) -> Unit,
    topPadding: Dp,
    startPadding: Dp,
    onSwitchProfile: (() -> Unit)?,
    modifier: Modifier = Modifier,
    searchFocusRequester: FocusRequester? = null,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = startPadding - 10.dp, end = 14.dp, top = topPadding),
    ) {
        if (onSwitchProfile != null) {
            SidebarProfileCard(onSwitchProfile = onSwitchProfile)
            Spacer(Modifier.height(12.dp))
        }
        SettingsSearchField(query = query, onQueryChange = onQueryChange, focusRequester = searchFocusRequester)
        Spacer(Modifier.height(10.dp))
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(top = 6.dp, bottom = 28.dp, end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val addonCount by AddonRepository.uiState.collectAsStateWithLifecycle()
                groups.forEach { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        group.labelRes?.let { label ->
                            Text(
                                text = stringResource(label),
                                modifier = Modifier.padding(start = 10.dp, bottom = 5.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.nuvio.colors.textMuted,
                            )
                        }
                        group.entries.forEach { entry ->
                            SidebarLink(
                                entry = entry,
                                selected = entry == selectedEntry,
                                badge = if (entry == DesktopSettingsEntry.Addons) {
                                    addonCount.addons.size.takeIf { it > 0 }?.toString()
                                } else {
                                    null
                                },
                                onClick = { onEntryClick(entry) },
                            )
                        }
                    }
                }
            }
            NuvioDesktopVerticalScrollbar(
                state = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SidebarProfileCard(onSwitchProfile: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val profile = profileState.activeProfile
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(NuvioTokens.Radius.lg)
    // Opaque on both ends so the hover animation cannot flash the page behind the card.
    val background by animateColorAsState(
        if (hovered || focused) tokens.colors.overlayHover.compositeOver(tokens.colors.surface) else tokens.colors.surface,
        tween(NuvioTokens.Motion.fastMillis),
        label = "settings_profile_card",
    )
    val email = (authState as? AuthState.Authenticated)?.takeUnless { it.isAnonymous }?.email
    val switchLabel = stringResource(Res.string.settings_desktop_switch_profile)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(NuvioTokens.Border.thin, if (focused) tokens.colors.focusRing else tokens.colors.borderSubtle, shape)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onSwitchProfile)
            .semantics { contentDescription = switchLabel }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActiveProfileMiniAvatar(profile = profile, avatars = avatars, selected = false, size = 34)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = profile?.name?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.compose_nav_profile),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = email ?: switchLabel,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Rounded.SwapHoriz,
            contentDescription = null,
            tint = if (hovered) tokens.colors.textPrimary else tokens.colors.textMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester?,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.lg)
    val placeholder = stringResource(Res.string.settings_search_placeholder)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val border by animateColorAsState(
        if (focused) tokens.colors.borderFocus else tokens.colors.borderSubtle,
        tween(NuvioTokens.Motion.fastMillis),
        label = "settings_search_border",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(shape)
            .background(tokens.colors.surface)
            .border(NuvioTokens.Border.thin, border, shape)
            .padding(start = 12.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(16.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .semantics { contentDescription = placeholder }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && query.isNotEmpty()) {
                            onQueryChange("")
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                interactionSource = interactionSource,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.colors.textPrimary),
                cursorBrush = SolidColor(tokens.colors.textPrimary),
            )
        }
        if (query.isEmpty() && !focused && focusRequester != null) {
            SettingsShortcutHint(text = settingsSearchShortcutLabel())
        }
        if (query.isNotEmpty()) {
            val clearLabel = stringResource(Res.string.compose_search_clear)
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                    .clickable(role = Role.Button) { onQueryChange("") }
                    .semantics { contentDescription = clearLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Keyboard shortcut chip shown in the search field while it is empty and unfocused. */
@Composable
private fun SettingsShortcutHint(text: String) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.sm)
    Text(
        text = text,
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(shape)
            .border(NuvioTokens.Border.thin, tokens.colors.borderSubtle, shape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = tokens.colors.textMuted,
        maxLines = 1,
    )
}

@Composable
private fun SidebarLink(
    entry: DesktopSettingsEntry,
    selected: Boolean,
    badge: String?,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(NuvioTokens.Radius.md)
    // Keyboard focus deepens the fill rather than drawing a ring, which also showed after mouse clicks.
    val background by animateColorAsState(
        when {
            selected && focused -> tokens.colors.textPrimary.copy(alpha = 0.15f)
            selected -> tokens.colors.textPrimary.copy(alpha = 0.09f)
            focused -> tokens.colors.textPrimary.copy(alpha = 0.10f)
            hovered -> tokens.colors.overlayHover
            else -> Color.Transparent
        },
        tween(NuvioTokens.Motion.fastMillis),
        label = "settings_sidebar_link",
    )
    val contentColor = if (selected || hovered) tokens.colors.textPrimary else tokens.colors.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(shape)
            .background(background)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = null,
            tint = when {
                selected -> tokens.colors.accent
                hovered -> tokens.colors.textSecondary
                else -> tokens.colors.textMuted
            },
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(entry.labelRes),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (badge != null) {
            Text(
                text = badge,
                modifier = Modifier.widthIn(min = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

/** Breadcrumb, title and one-line summary at the top of a desktop settings page. */
@Composable
internal fun DesktopSettingsPageHeader(
    title: String,
    eyebrow: String?,
    summary: String?,
    onEyebrowClick: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (eyebrow != null) {
            val interactionSource = remember { MutableInteractionSource() }
            val hovered by interactionSource.collectIsHoveredAsState()
            Text(
                text = if (onEyebrowClick != null) "‹  $eyebrow" else eyebrow,
                modifier = if (onEyebrowClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                        .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onEyebrowClick)
                } else {
                    Modifier
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (hovered) tokens.colors.textPrimary else tokens.colors.textMuted,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        if (!summary.isNullOrBlank()) {
            Text(
                text = summary,
                modifier = Modifier.widthIn(max = 680.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
        }
    }
}
