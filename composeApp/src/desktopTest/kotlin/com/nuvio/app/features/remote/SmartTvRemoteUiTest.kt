package com.nuvio.app.features.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.nuvio
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeSmartTvTransport : SmartTvRemoteTransport {
    override val supported = true
    val sent = mutableListOf<SmartTvKey>()
    var discoveries = 0
    var disconnects = 0
    val connected = mutableListOf<SmartTvDevice>()
    override fun discover() { discoveries++ }
    override fun connect(device: SmartTvDevice) { connected += device }
    override fun forget(device: SmartTvDevice) = Unit
    override fun disconnect() { disconnects++ }
    override fun send(key: SmartTvKey) { sent += key }
}

@OptIn(ExperimentalTestApi::class)
class SmartTvRemoteUiTest {
    private val livingRoom = SmartTvDevice("192.168.1.40", SmartTvPlatform.SamsungTizen, "Living room TV")
    private val bedroom = SmartTvDevice("192.168.1.41", SmartTvPlatform.LgWebOs, "Bedroom TV")

    @AfterTest
    fun reset() {
        SmartTvRemote.active.value = null
        SmartTvRemote.discovered.value = emptyList()
        SmartTvRemote.saved.value = emptyList()
        SmartTvRemote.scanning.value = false
        SmartTvRemote.connecting.value = false
        SmartTvRemote.status.value = ""
        StarlitTvPlayback.connected.value = false
        StarlitTvPlayback.pairing.value = false
        StarlitTvPlayback.verification.value = ""
        StarlitTvPlayback.error.value = false
        StarlitTvPlayback.name.value = ""
    }

    private fun ComposeUiTest.capture(name: String) {
        val file = File("build/remote-ui-capture/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    private fun ComposeUiTest.show(transport: SmartTvRemoteTransport) {
        setContent {
            NuvioTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.nuvio.colors.background)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SmartTvRemotePage(transport)
                }
            }
        }
    }

    @Test
    fun findPageListsTvsAndConnectsOnTap() = runDesktopComposeUiTest(width = 412, height = 1500) {
        val transport = FakeSmartTvTransport()
        SmartTvRemote.discovered.value = listOf(livingRoom)
        SmartTvRemote.saved.value = listOf(bedroom)
        show(transport)
        onNodeWithTag("smart-tv-search").assertIsDisplayed()
        // One "Connect to a TV" heading: the Starlit pairing has its own section now.
        onAllNodesWithText("Connect to a TV").assertCountEquals(1)
        onNodeWithText("Starlit on your TV".uppercase()).assertIsDisplayed()
        onNodeWithText("Living room TV").assertIsDisplayed()
        onNodeWithText("Bedroom TV").assertIsDisplayed()
        // The typed address waits behind its row until it is needed.
        onNodeWithTag("smart-tv-manual-ip").assertDoesNotExist()
        capture("tv-remote-find")
        onNodeWithTag("smart-tv-${livingRoom.platform.name}-${livingRoom.address}").performClick()
        runOnIdle { assertEquals(listOf(livingRoom), transport.connected) }
    }

    @Test
    fun typedAddressNamesTheTvAndConnectsToTheChosenPlatform() =
        runDesktopComposeUiTest(width = 412, height = 1500) {
            val transport = FakeSmartTvTransport()
            SmartTvRemote.scanning.value = true
            SmartTvRemote.status.value = "Searching for Samsung and LG TVs…"
            show(transport)
            onNodeWithTag("smart-tv-remote-status").assertTextEquals("Searching for Samsung and LG TVs…")
            onNodeWithTag("smart-tv-search").assertIsNotEnabled()
            onNodeWithTag("smart-tv-enter-address").performScrollTo().performClick()
            onNodeWithText("TV local IPv4 address").performScrollTo().assertIsDisplayed()
            onNodeWithText("PC local IPv4 address").assertDoesNotExist()
            onNodeWithTag("smart-tv-manual-connect").assertIsNotEnabled()
            onNodeWithTag("smart-tv-platform-LgWebOs").performClick()
            onNodeWithTag("smart-tv-manual-ip").performTextInput("192.168.1.77")
            onNodeWithTag("smart-tv-manual-connect").assertIsEnabled()
            capture("tv-remote-find-manual")
            onNodeWithTag("smart-tv-manual-connect").performClick()
            runOnIdle {
                assertEquals(listOf(SmartTvDevice("192.168.1.77", SmartTvPlatform.LgWebOs)), transport.connected)
            }
        }

    @Test
    fun starlitPairingShowsTheCodeToApproveOnTheTv() = runDesktopComposeUiTest(width = 412, height = 1500) {
        StarlitTvPlayback.pairing.value = true
        StarlitTvPlayback.verification.value = "4821"
        show(FakeSmartTvTransport())
        onNodeWithTag("starlit-tv-verification").performScrollTo().assertTextEquals("4821")
        onNodeWithTag("starlit-tv-pair-cancel").assertIsDisplayed()
        onNodeWithTag("starlit-tv-pair-code").assertDoesNotExist()
        capture("tv-remote-starlit-approve")
    }

    @Test
    fun connectedRemoteSendsEveryKey() = runDesktopComposeUiTest(width = 412, height = 1300) {
        val transport = FakeSmartTvTransport()
        SmartTvRemote.active.value = livingRoom
        show(transport)
        onNodeWithTag("smart-tv-disconnect").assertIsDisplayed()
        onNodeWithTag("smart-tv-search").assertDoesNotExist()
        capture("tv-remote-connected")
        SmartTvKey.entries.forEach { key ->
            onNodeWithTag("smart-tv-key-${key.name}").performScrollTo().performClick()
        }
        runOnIdle { assertEquals(SmartTvKey.entries.toList(), transport.sent) }
        // The arrows are also real buttons for screen readers.
        onNodeWithContentDescription("Left").performSemanticsAction(SemanticsActions.OnClick)
        runOnIdle { assertEquals(SmartTvKey.Left, transport.sent.last()) }
        // Under a live remote, Starlit pairing folds behind its row until it is wanted.
        onNodeWithTag("starlit-tv-pair-code").assertDoesNotExist()
        onNodeWithTag("starlit-tv-pair-row").performScrollTo().performClick()
        onNodeWithTag("starlit-tv-pair-code").performScrollTo().assertIsDisplayed()
        onNodeWithTag("smart-tv-disconnect").assertContentDescriptionEquals("Disconnect")
            .performScrollTo().performClick()
        runOnIdle { assertEquals(1, transport.disconnects) }
    }

    @Test
    fun remoteFitsANarrowPhone() = runDesktopComposeUiTest(width = 360, height = 1250) {
        SmartTvRemote.active.value = SmartTvDevice("192.168.1.40", SmartTvPlatform.SamsungTizen)
        StarlitTvPlayback.connected.value = true
        StarlitTvPlayback.name.value = "Starlit on Living room TV"
        show(FakeSmartTvTransport())
        onNodeWithTag("smart-tv-key-Up").assertIsDisplayed()
        onNodeWithTag("smart-tv-key-VolumeUp").performScrollTo().assertIsDisplayed()
        onNodeWithTag("starlit-tv-unpair").performScrollTo().assertIsDisplayed()
        capture("tv-remote-connected-narrow")
    }
}
