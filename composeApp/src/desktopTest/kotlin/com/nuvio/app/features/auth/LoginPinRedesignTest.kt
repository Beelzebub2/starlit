package com.nuvio.app.features.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.PinEntryDialog
import com.nuvio.app.features.profiles.PinVerifyResult
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks the redesigned sign-in and profile PIN screens on desktop and saves screenshots to
 * build/login-pin-capture for visual review.
 */
@OptIn(ExperimentalTestApi::class)
class LoginPinRedesignTest {
    private fun ComposeUiTest.capture(name: String, node: SemanticsNodeInteraction = onRoot()) {
        waitForIdle()
        Thread.sleep(400)
        waitForIdle()
        val shot = node.captureToImage().toAwtImage()
        val directory = File("build/login-pin-capture").apply { mkdirs() }
        ImageIO.write(shot, "png", File(directory, "$name.png"))
    }

    @Test
    fun desktopSignInShowsModeTabsAndEditableFields() = runDesktopComposeUiTest(width = 1440, height = 900) {
        setContent { NuvioTheme { AuthScreen() } }
        onNodeWithTag("auth-mode-sign-in").assertIsDisplayed()
        onNodeWithTag("auth-continue-without-account").assertIsDisplayed()
        assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2)
        capture("desktop-sign-in")

        onNodeWithTag("auth-mode-sign-up").performClick()
        waitForIdle()
        capture("desktop-create-account")
    }

    @Test
    fun narrowWindowUsesThePhoneLayout() = runDesktopComposeUiTest(width = 400, height = 860) {
        setContent { NuvioTheme { AuthScreen() } }
        onNodeWithTag("auth-mode-sign-in").assertIsDisplayed()
        assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2)
        capture("narrow-sign-in")
    }

    @Test
    fun pinDialogVerifiesAfterFourDigitsAndShowsTheError() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val attempts = mutableListOf<String>()
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF09090D)))
                PinEntryDialog(
                    profileName = "Sam",
                    profile = NuvioProfile(name = "Sam", avatarColorHex = "#4B63F0"),
                    onVerify = { pin ->
                        attempts += pin
                        PinVerifyResult(unlocked = false)
                    },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag("pin-key-1").performClick()
        onNodeWithTag("pin-key-2").performClick()
        capture("desktop-pin", onAllNodes(isRoot()).onLast())

        onNodeWithTag("pin-key-3").performClick()
        onNodeWithTag("pin-key-4").performClick()
        waitForIdle()
        assertEquals(listOf("1234"), attempts)
        capture("desktop-pin-error", onAllNodes(isRoot()).onLast())
    }

    @Test
    fun qrEncoderProducesASquareMatrix() {
        val matrix = assertNotNull(encodeAuthQr("https://nuvio.tv/link?code=K7Q4MX"))
        assertTrue(matrix.size >= 21)
        // Finder pattern corners are always dark.
        assertTrue(matrix[0, 0] && matrix[matrix.size - 1, 0] && matrix[0, matrix.size - 1])
    }

    @Test
    fun pinKeypadExposesEveryDigit() = runDesktopComposeUiTest(width = 1440, height = 900) {
        setContent {
            NuvioTheme {
                PinEntryDialog(
                    profileName = "Sam",
                    onVerify = { PinVerifyResult(unlocked = true) },
                    onDismiss = {},
                )
            }
        }
        (0..9).forEach { digit -> assertEquals(1, onAllNodesWithTag("pin-key-$digit").fetchSemanticsNodes().size) }
        onNodeWithTag("pin-key-backspace").assertIsDisplayed()
    }
}
