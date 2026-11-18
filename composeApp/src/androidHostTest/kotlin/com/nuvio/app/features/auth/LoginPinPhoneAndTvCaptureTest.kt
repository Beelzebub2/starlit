package com.nuvio.app.features.auth

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.nuvio.app.LocalTelevisionUi
import com.nuvio.app.core.auth.AuthStorage
import com.nuvio.app.core.network.ServerConfigurationStorage
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.profiles.AvatarStorage
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.PinEntryDialog
import com.nuvio.app.features.profiles.PinVerifyResult
import com.nuvio.app.features.profiles.ProfileStorage
import com.nuvio.app.features.settings.ThemeSettingsStorage
import java.io.File
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the redesigned sign-in and PIN screens on a phone and an Android TV (1920×1080) and saves
 * them to build/login-pin-capture for visual review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class LoginPinPhoneAndTvCaptureTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun initializeStorage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runCatching { ThemeSettingsStorage.initialize(context) }
        runCatching { AuthStorage.initialize(context) }
        runCatching { ServerConfigurationStorage.initialize(context) }
        runCatching { ProfileStorage.initialize(context) }
        runCatching { AvatarStorage.initialize(context) }
    }

    private fun capture(name: String, node: SemanticsNodeInteraction = compose.onRoot()) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val bitmap = node.captureToImage().asAndroidBitmap()
        val file = File("build/login-pin-capture/$name.png").apply { parentFile.mkdirs() }
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(television: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalTelevisionUi provides television) {
                NuvioTheme { content() }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun pinDialog() {
        PinEntryDialog(
            profileName = "Sam",
            profile = NuvioProfile(name = "Sam", avatarColorHex = "#4B63F0"),
            onVerify = { PinVerifyResult(unlocked = false) },
            onDismiss = {},
        )
    }

    @Test
    fun phoneSignIn() {
        show { AuthScreen() }
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2)
        capture("phone-sign-in")
        compose.onNodeWithTag("auth-mode-sign-up").performClick()
        capture("phone-create-account")
    }

    @Test
    fun phonePin() {
        show {
            Box(Modifier.fillMaxSize().background(Color(0xFF09090D)))
            pinDialog()
        }
        compose.onNodeWithTag("pin-key-1").performClick()
        compose.onNodeWithTag("pin-key-2").performClick()
        capture("phone-pin", compose.onAllNodes(isRoot()).onLast())
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun televisionSignIn() {
        show(television = true) { AuthScreen() }
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2)
        capture("tv-sign-in")
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun televisionPin() {
        show(television = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF09090D)))
            pinDialog()
        }
        compose.onNodeWithTag("pin-key-4").performClick()
        capture("tv-pin", compose.onAllNodes(isRoot()).onLast())
    }
}
