package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.player.DEFAULT_PLAYER_CONTROLS_SCALE_PERCENT
import com.nuvio.app.features.player.MAX_PLAYER_CONTROLS_SCALE_PERCENT
import com.nuvio.app.features.player.MIN_PLAYER_CONTROLS_SCALE_PERCENT
import com.nuvio.app.features.player.PLAYER_CONTROLS_SCALE_STEP_PERCENT
import com.nuvio.app.features.player.PlayerControlIcons
import com.nuvio.app.features.player.normalizedPlayerControlsScalePercent
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_controls_size
import nuvio.composeapp.generated.resources.settings_playback_controls_size_description
import nuvio.composeapp.generated.resources.settings_playback_controls_size_preview
import nuvio.composeapp.generated.resources.settings_playback_controls_size_reset
import nuvio.composeapp.generated.resources.settings_playback_controls_size_value
import org.jetbrains.compose.resources.stringResource

/**
 * Control size slider with a live preview of the player bar. The preview follows the slider while
 * dragging; the value is saved when the drag ends so the player is not re-rendered on every step.
 */
@Composable
internal fun PlayerControlsSizeSetting(
    scalePercent: Int,
    isTablet: Boolean,
    onScalePercentChange: (Int) -> Unit,
) {
    val title = stringResource(Res.string.settings_playback_controls_size)
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    var sliderValue by remember(scalePercent) { mutableFloatStateOf(scalePercent.toFloat()) }
    val previewPercent = normalizedPlayerControlsScalePercent(sliderValue.roundToInt())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .settingsSearchTarget(title)
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(Res.string.settings_playback_controls_size_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (previewPercent != DEFAULT_PLAYER_CONTROLS_SCALE_PERCENT) {
                TextButton(
                    onClick = {
                        sliderValue = DEFAULT_PLAYER_CONTROLS_SCALE_PERCENT.toFloat()
                        onScalePercentChange(DEFAULT_PLAYER_CONTROLS_SCALE_PERCENT)
                    },
                ) {
                    Text(stringResource(Res.string.settings_playback_controls_size_reset))
                }
            }
            ValueBox(
                text = stringResource(Res.string.settings_playback_controls_size_value, previewPercent),
                modifier = Modifier.wrapContentWidth(),
            )
        }
        PlayerControlsPreview(scale = previewPercent / 100f)
        Slider(
            value = sliderValue.coerceIn(
                MIN_PLAYER_CONTROLS_SCALE_PERCENT.toFloat(),
                MAX_PLAYER_CONTROLS_SCALE_PERCENT.toFloat(),
            ),
            onValueChange = { sliderValue = snapToStep(it, PLAYER_CONTROLS_SCALE_STEP_PERCENT.toFloat()) },
            onValueChangeFinished = { onScalePercentChange(previewPercent) },
            valueRange = MIN_PLAYER_CONTROLS_SCALE_PERCENT.toFloat()..MAX_PLAYER_CONTROLS_SCALE_PERCENT.toFloat(),
            steps = calculateSteps(
                MIN_PLAYER_CONTROLS_SCALE_PERCENT.toFloat(),
                MAX_PLAYER_CONTROLS_SCALE_PERCENT.toFloat(),
                PLAYER_CONTROLS_SCALE_STEP_PERCENT.toFloat(),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A stand-in for the bottom of the player at real size: the same button, icon and play sizes the
 * desktop overlay uses (44, 22 and 56 at 100%), so what you see here is what the player shows.
 */
@Composable
private fun PlayerControlsPreview(scale: Float) {
    val accent = MaterialTheme.colorScheme.primary
    val textScale = 1f + (scale - 1f) * 0.5f
    val previewLabel = stringResource(Res.string.settings_playback_controls_size_preview)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF2A2F3A),
                    0.45f to Color(0xFF15181F),
                    1f to Color(0xFF07080B),
                ),
            ),
    ) {
        Text(
            text = previewLabel,
            modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelMedium,
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("20:14", color = Color.White, fontSize = (13 * textScale).sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.28f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.62f)
                            .height(4.dp)
                            .background(accent),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("23:50", color = Color.White, fontSize = (13 * textScale).sp, fontWeight = FontWeight.SemiBold)
            }
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size((56 * scale).dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PlayerControlIcons.Play,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size((24 * scale).dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                PreviewIcon(PlayerControlIcons.Replay, scale)
                PreviewIcon(PlayerControlIcons.Forward, scale)
                PreviewIcon(PlayerControlIcons.SkipNext, scale)
                PreviewIcon(PlayerControlIcons.Audio, scale)
                Spacer(Modifier.weight(1f))
                PreviewIcon(PlayerControlIcons.Episodes, scale)
                PreviewIcon(PlayerControlIcons.Source, scale)
                PreviewIcon(PlayerControlIcons.Subtitles, scale)
                PreviewIcon(PlayerControlIcons.Aspect, scale)
                PreviewIcon(PlayerControlIcons.Options, scale)
            }
        }
    }
}

@Composable
private fun PreviewIcon(icon: ImageVector, scale: Float) {
    val button: Dp = (44 * scale).dp
    Box(modifier = Modifier.size(button), contentAlignment = Alignment.Center) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size((22 * scale).dp),
        )
    }
}
