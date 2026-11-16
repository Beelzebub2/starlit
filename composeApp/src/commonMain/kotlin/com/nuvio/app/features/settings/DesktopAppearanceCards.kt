package com.nuvio.app.features.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.CustomThemeColors
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.labelRes
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cd_selected
import nuvio.composeapp.generated.resources.custom_theme_edit
import org.jetbrains.compose.resources.stringResource

/**
 * Desktop theme picker: each theme is a small preview of the app in its colours, so the choice
 * shows what the app will look like rather than only an accent swatch.
 */
@Composable
internal fun DesktopThemeCardGrid(
    themes: List<AppTheme>,
    selectedTheme: AppTheme,
    customColors: CustomThemeColors,
    onThemeClick: (AppTheme) -> Unit,
) {
    val spacing = 12.dp
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        val columns = ((maxWidth + spacing) / (150.dp + spacing)).toInt().coerceIn(2, 4)
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            themes.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.forEach { theme ->
                        DesktopThemeCard(
                            theme = theme,
                            customColors = customColors,
                            selected = theme == selectedTheme,
                            onClick = { onThemeClick(theme) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun DesktopThemeCard(
    theme: AppTheme,
    customColors: CustomThemeColors,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val palette = remember(theme, customColors) { ThemeColors.getColorPalette(theme, customColors) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(NuvioTokens.Radius.lg)
    val border by animateColorAsState(
        when {
            selected -> palette.focusRing
            focused -> tokens.colors.focusRing
            hovered -> tokens.colors.borderStrong
            else -> tokens.colors.borderSubtle
        },
        tween(NuvioTokens.Motion.fastMillis),
        label = "desktop_theme_card_border",
    )
    Column(
        modifier = modifier
            .clip(shape)
            .border(if (selected) 2.dp else NuvioTokens.Border.thin, border, shape)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            ),
    ) {
        // A tiny Home screen: top bar, a row of posters and an accent button.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp)
                .background(palette.background)
                .padding(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    PreviewBlock(palette.backgroundElevated, width = 22.dp, height = 6.dp)
                    PreviewBlock(palette.backgroundElevated, width = 14.dp, height = 6.dp)
                    PreviewBlock(palette.backgroundElevated, width = 14.dp, height = 6.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(4) { PreviewBlock(palette.backgroundCard, width = 18.dp, height = 26.dp) }
                }
                Box(
                    modifier = Modifier
                        .width(34.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(palette.accentBrush()),
                )
            }
            if (theme == AppTheme.CUSTOM) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(Res.string.custom_theme_edit),
                    tint = tokens.colors.textPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).size(16.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (hovered || focused) tokens.colors.overlayHover else tokens.colors.surface)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(palette.accentBrush()),
            )
            Text(
                text = stringResource(theme.labelRes),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) tokens.colors.textPrimary else tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(Res.string.cd_selected),
                    tint = palette.focusRing,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewBlock(color: Color, width: Dp, height: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

/** Desktop navigation layout as two picture cards, chosen in place instead of in a sheet. */
@Composable
internal fun DesktopNavigationLayoutPicker(
    title: String,
    selected: DesktopNavigationLayout,
    onSelect: (DesktopNavigationLayout) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = tokens.colors.textPrimary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(DesktopNavigationLayout.TopBar, DesktopNavigationLayout.Sidebar).forEach { layout ->
                DesktopNavigationLayoutCard(
                    layout = layout,
                    selected = layout == selected,
                    onClick = { onSelect(layout) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DesktopNavigationLayoutCard(
    layout: DesktopNavigationLayout,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(NuvioTokens.Radius.lg)
    val border by animateColorAsState(
        when {
            selected -> tokens.colors.accent
            focused -> tokens.colors.focusRing
            hovered -> tokens.colors.borderStrong
            else -> tokens.colors.borderSubtle
        },
        tween(NuvioTokens.Motion.fastMillis),
        label = "desktop_nav_layout_border",
    )
    val chrome = tokens.colors.textPrimary.copy(alpha = 0.16f)
    val content = tokens.colors.textPrimary.copy(alpha = 0.08f)
    Column(
        modifier = modifier
            .clip(shape)
            .border(if (selected) 2.dp else NuvioTokens.Border.thin, border, shape)
            .background(if (hovered || focused) tokens.colors.overlayHover else Color.Transparent)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                .background(tokens.colors.background)
                .border(NuvioTokens.Border.thin, tokens.colors.borderSubtle, RoundedCornerShape(NuvioTokens.Radius.md)),
        ) {
            when (layout) {
                DesktopNavigationLayout.TopBar -> Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(16.dp).background(chrome).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PreviewBlock(tokens.colors.accent, width = 12.dp, height = 4.dp)
                        repeat(3) { PreviewBlock(content, width = 12.dp, height = 4.dp) }
                    }
                    PreviewContentGrid(content)
                }
                DesktopNavigationLayout.Sidebar -> Row(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.width(30.dp).fillMaxHeight().background(chrome).padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        PreviewBlock(tokens.colors.accent, width = 16.dp, height = 4.dp)
                        repeat(3) { PreviewBlock(content, width = 16.dp, height = 4.dp) }
                    }
                    PreviewContentGrid(content)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (selected) tokens.colors.accent else tokens.colors.borderStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(tokens.colors.accent))
                }
            }
            Text(
                text = stringResource(layout.labelRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) tokens.colors.textPrimary else tokens.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun PreviewContentGrid(color: Color) {
    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(5) { PreviewBlock(color, width = 14.dp, height = 22.dp) }
            }
        }
    }
}
