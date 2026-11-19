package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.DEFAULT_PLAYER_CONTROLS_SCALE_PERCENT
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

class PlayerControlsSizeSettingUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsPreviewAndResetsToDefault() {
        val saved = mutableIntStateOf(140)
        compose.setContent {
            NuvioTheme {
                Surface(
                    modifier = Modifier.width(760.dp).testTag("setting"),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    PlayerControlsSizeSetting(
                        scalePercent = saved.intValue,
                        isTablet = true,
                        onScalePercentChange = { saved.intValue = it },
                    )
                }
            }
        }

        compose.onNodeWithText("Preview").assertExists()
        compose.onNodeWithText("140%").assertExists()
        val directory = File("build/reports/player-controls-size").apply { mkdirs() }
        ImageIO.write(compose.onNodeWithTag("setting").captureToImage().toAwtImage(), "png", File(directory, "size-140.png"))

        compose.onNodeWithText("Reset").performClick()
        compose.waitForIdle()
        assertEquals(DEFAULT_PLAYER_CONTROLS_SCALE_PERCENT, saved.intValue)
        compose.onNodeWithText("100%").assertExists()
        ImageIO.write(compose.onNodeWithTag("setting").captureToImage().toAwtImage(), "png", File(directory, "size-100.png"))
    }
}
