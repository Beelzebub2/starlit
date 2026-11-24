package com.nuvio.app.features.lists

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeService(
    override val id: ListServiceId,
    connected: Boolean = true,
    private val coverage: (ListTitleKind, MetaDetails) -> Boolean = { _, _ -> true },
) : ListService {
    override val connected: StateFlow<Boolean> = MutableStateFlow(connected)
    override val sorts: List<ListSort> = emptyList()
    override fun covers(kind: ListTitleKind, meta: MetaDetails) = coverage(kind, meta)
    override fun capabilities(type: ListMediaType): ListCapabilities = error("unused")
    override fun profileUrl(): String? = null
    override suspend fun summary(): ListSummary? = null
    override suspend fun page(status: ListStatus?, sort: ListSort, offset: Int): ListPage = error("unused")
    override suspend fun entryFor(meta: MetaDetails): ListEntry? = null
    override suspend fun impact(entry: ListEntry, change: ListChange): ListChangeImpact = error("unused")
    override suspend fun apply(entry: ListEntry, change: ListChange): ListEntry = error("unused")
    override suspend fun removeFromHistory(entry: ListEntry): Int = error("unused")
}

class ListPreferencesTest {
    private val animeSeries = ListTitleKind(ListMediaType.SERIES, anime = true)
    private val liveMovie = ListTitleKind(ListMediaType.MOVIE, anime = false)
    private val meta = MetaDetails(id = "tt1", type = "series", name = "A")
    private val malCovers: (ListTitleKind, MetaDetails) -> Boolean = { k, _ -> k.anime }

    @After fun reset() {
        ListPreferencesRepository.activeProfileProvider = { ProfileRepository.activeProfileId }
        val store = DesktopStorage.store("list_services")
        listOf(901, 902, 903).forEach { p -> listOf("order", "tab", "title", "last_tab").forEach { store.remove("${it}_$p") } }
        DesktopStorage.store("myanimelist").remove("show_tab_903")
    }

    @Test fun defaultsFollowMalTabSetting() {
        assertEquals(setOf(ListServiceId.MYANIMELIST), defaultListPreferences(true).inTab)
        assertTrue(defaultListPreferences(false).inTab.isEmpty())
        val d = defaultListPreferences(false)
        assertEquals(listOf(ListServiceId.MYANIMELIST, ListServiceId.SIMKL, ListServiceId.TRAKT, ListServiceId.MDBLIST), d.order)
        assertEquals(ListServiceId.entries.toSet(), d.onTitlePages)
    }

    @Test fun candidatesRespectCoverageConnectionAndOrder() {
        val services = listOf(
            FakeService(ListServiceId.MYANIMELIST, coverage = malCovers),
            FakeService(ListServiceId.SIMKL), FakeService(ListServiceId.TRAKT),
            FakeService(ListServiceId.MDBLIST, connected = false),
        )
        val prefs = defaultListPreferences(false).copy(
            order = listOf(ListServiceId.TRAKT, ListServiceId.MYANIMELIST, ListServiceId.SIMKL, ListServiceId.MDBLIST))
        assertEquals(listOf(ListServiceId.TRAKT, ListServiceId.MYANIMELIST, ListServiceId.SIMKL),
            titleCandidates(services, prefs, animeSeries, meta).map { it.id })
        assertEquals(listOf(ListServiceId.TRAKT, ListServiceId.SIMKL),
            titleCandidates(services, prefs, liveMovie, meta).map { it.id })
        val off = prefs.copy(onTitlePages = prefs.onTitlePages - ListServiceId.SIMKL)
        assertEquals(listOf(ListServiceId.TRAKT, ListServiceId.MYANIMELIST),
            titleCandidates(services, off, animeSeries, meta).map { it.id })
    }

    @Test fun serviceThatCannotIdentifyTitleIsNotACandidate() {
        val trakt = FakeService(ListServiceId.TRAKT, coverage = { _, m -> m.id.startsWith("tt") })
        val prefs = defaultListPreferences(false)
        assertEquals(emptyList(), titleCandidates(listOf(trakt), prefs, liveMovie, meta.copy(id = "kitsu:5")))
        assertEquals(1, titleCandidates(listOf(trakt), prefs, liveMovie, meta).size)
    }

    @Test fun tabServicesFilterAndSort() {
        val services = listOf(FakeService(ListServiceId.MYANIMELIST), FakeService(ListServiceId.SIMKL),
            FakeService(ListServiceId.TRAKT, connected = false))
        val prefs = defaultListPreferences(false).copy(
            order = listOf(ListServiceId.SIMKL, ListServiceId.TRAKT, ListServiceId.MYANIMELIST, ListServiceId.MDBLIST),
            inTab = setOf(ListServiceId.MYANIMELIST, ListServiceId.SIMKL, ListServiceId.TRAKT))
        assertEquals(listOf(ListServiceId.SIMKL, ListServiceId.MYANIMELIST), tabServices(services, prefs).map { it.id })
    }

    @Test fun preferencesArePerProfile() {
        var profile = 901
        ListPreferencesRepository.activeProfileProvider = { profile }
        ListPreferencesRepository.ensureLoaded()
        ListPreferencesRepository.move(ListServiceId.TRAKT, up = true)
        ListPreferencesRepository.setInTab(ListServiceId.SIMKL, true)
        ListPreferencesRepository.setLastTabService(ListServiceId.SIMKL)
        val saved = ListPreferencesRepository.state.value
        assertEquals(ListServiceId.TRAKT, saved.order[1])

        profile = 902
        ListPreferencesRepository.ensureLoaded()
        assertEquals(defaultListPreferences(false), ListPreferencesRepository.state.value)

        profile = 901
        ListPreferencesRepository.ensureLoaded()
        assertEquals(saved, ListPreferencesRepository.state.value)
    }

    @Test fun malTabSettingMigratesIntoDefaults() {
        DesktopStorage.store("myanimelist").putString("show_tab_903", "true")
        ListPreferencesRepository.activeProfileProvider = { 903 }
        ListPreferencesRepository.ensureLoaded()
        assertEquals(setOf(ListServiceId.MYANIMELIST), ListPreferencesRepository.state.value.inTab)
    }
}
