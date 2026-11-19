package com.nuvio.app.features.lists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class SettingsFakeService(override val id: ListServiceId, connected: Boolean = true) : ListService {
    override val connected: StateFlow<Boolean> = MutableStateFlow(connected)
    override val sorts: List<ListSort> = emptyList()
    override fun covers(kind: ListTitleKind, meta: MetaDetails) = true
    override fun capabilities(type: ListMediaType): ListCapabilities = error("unused")
    override fun profileUrl(): String? = null
    override suspend fun summary(): ListSummary? = null
    override suspend fun page(status: ListStatus?, sort: ListSort, offset: Int): ListPage = error("unused")
    override suspend fun entryFor(meta: MetaDetails): ListEntry? = null
    override suspend fun impact(entry: ListEntry, change: ListChange): ListChangeImpact = error("unused")
    override suspend fun apply(entry: ListEntry, change: ListChange): ListEntry = error("unused")
    override suspend fun removeFromHistory(entry: ListEntry): Int = error("unused")
}

@OptIn(ExperimentalTestApi::class)
class ListServicesSettingsCardUiTest {
    private val previousLocale = Locale.getDefault()
    private val profile = 905

    @BeforeTest fun setUp() {
        Locale.setDefault(Locale.US)
        clearStore()
        ListPreferencesRepository.activeProfileProvider = { profile }
        // Force a reload for the test profile.
        ListPreferencesRepository.ensureLoaded()
    }

    @AfterTest fun tearDown() {
        Locale.setDefault(previousLocale)
        ListServices.overrideForTests = null
        clearStore()
        ListPreferencesRepository.activeProfileProvider = { ProfileRepository.activeProfileId }
        ListPreferencesRepository.ensureLoaded()
    }

    private fun clearStore() {
        val store = DesktopStorage.store("list_services")
        listOf("order", "tab", "title", "last_tab").forEach { store.remove("${it}_$profile") }
    }

    private fun connected(vararg ids: ListServiceId, disconnected: Set<ListServiceId> = emptySet()) {
        ListServices.overrideForTests = ids.map { SettingsFakeService(it, it !in disconnected) }
    }

    @Test fun rowsFollowPreferenceOrderAndMove() = runDesktopComposeUiTest(900, 700) {
        connected(ListServiceId.MYANIMELIST, ListServiceId.SIMKL, ListServiceId.TRAKT)
        setContent { NuvioTheme { Surface { Box(Modifier.size(900.dp, 700.dp)) { ListServicesSettingsCard() } } } }
        waitForIdle()
        onNodeWithContentDescription("Move MyAnimeList up").assertIsNotEnabled()
        onNodeWithContentDescription("Move Trakt down").assertIsNotEnabled()
        onNodeWithContentDescription("Move Simkl up").assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(
            listOf(ListServiceId.SIMKL, ListServiceId.MYANIMELIST, ListServiceId.TRAKT, ListServiceId.MDBLIST),
            ListPreferencesRepository.state.value.order,
        )
        onNodeWithContentDescription("Move Simkl up").assertIsNotEnabled()
        val directory = File("build/lists-settings-capture").apply { mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "settings-card.png"))
    }

    @Test fun togglesWriteThrough() = runDesktopComposeUiTest(900, 700) {
        connected(ListServiceId.SIMKL, ListServiceId.TRAKT)
        setContent { NuvioTheme { Surface { Box(Modifier.size(900.dp, 700.dp)) { ListServicesSettingsCard() } } } }
        waitForIdle()
        onNodeWithContentDescription("Show Simkl in Lists tab").assertIsOff().performClick()
        waitForIdle()
        assertTrue(ListServiceId.SIMKL in ListPreferencesRepository.state.value.inTab)
        onNodeWithContentDescription("Show Simkl in Lists tab").assertIsOn()
        onNodeWithContentDescription("Show Trakt on title pages").assertIsOn().performClick()
        waitForIdle()
        assertFalse(ListServiceId.TRAKT in ListPreferencesRepository.state.value.onTitlePages)
        onNodeWithContentDescription("Show Trakt on title pages").assertIsOff()
    }

    @Test fun disconnectedServicesAreNotListed() = runDesktopComposeUiTest(900, 700) {
        connected(ListServiceId.SIMKL, ListServiceId.TRAKT, disconnected = setOf(ListServiceId.TRAKT))
        setContent { NuvioTheme { Surface { Box(Modifier.size(900.dp, 700.dp)) { ListServicesSettingsCard() } } } }
        waitForIdle()
        onNodeWithText("Simkl").assertExists()
        onNodeWithText("Trakt").assertDoesNotExist()
        onNodeWithText("MyAnimeList").assertDoesNotExist()
    }

    @Test fun movingStepsOverDisconnectedServices() = runDesktopComposeUiTest(900, 700) {
        connected(ListServiceId.MYANIMELIST, ListServiceId.SIMKL, ListServiceId.TRAKT, disconnected = setOf(ListServiceId.SIMKL))
        setContent { NuvioTheme { Surface { Box(Modifier.size(900.dp, 700.dp)) { ListServicesSettingsCard() } } } }
        waitForIdle()
        onNodeWithContentDescription("Move Trakt up").assertIsEnabled().performClick()
        waitForIdle()
        val order = ListPreferencesRepository.state.value.order
        assertEquals(ListServiceId.entries.toSet(), order.toSet())
        assertTrue(order.indexOf(ListServiceId.TRAKT) < order.indexOf(ListServiceId.MYANIMELIST))
        onNodeWithContentDescription("Move Trakt up").assertIsNotEnabled()
        onNodeWithContentDescription("Move MyAnimeList down").assertIsNotEnabled()
    }

    @Test fun hintShownWhenNothingIsConnected() = runDesktopComposeUiTest(900, 700) {
        connected(ListServiceId.SIMKL, disconnected = setOf(ListServiceId.SIMKL))
        setContent { NuvioTheme { Surface { Box(Modifier.size(900.dp, 700.dp)) { ListServicesSettingsCard() } } } }
        waitForIdle()
        onNodeWithText("Connect a service above to choose where your lists show.").assertExists()
    }
}
