package com.nuvio.app.features.lists

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ListPreferences(
    val order: List<ListServiceId>,
    val inTab: Set<ListServiceId>,
    val onTitlePages: Set<ListServiceId>,
    val lastTabService: ListServiceId?,
)

internal fun defaultListPreferences(malTabWasShown: Boolean): ListPreferences = ListPreferences(
    order = ListServiceId.entries.toList(),
    inTab = if (malTabWasShown) setOf(ListServiceId.MYANIMELIST) else emptySet(),
    onTitlePages = ListServiceId.entries.toSet(),
    lastTabService = null,
)

object ListPreferencesRepository {
    private val store = DesktopStorage.store("list_services")
    private val legacyMalStore = DesktopStorage.store("myanimelist")
    private val mutableState = MutableStateFlow(defaultListPreferences(false))
    val state: StateFlow<ListPreferences> = mutableState.asStateFlow()
    @Volatile private var loadedProfile: Int? = null

    /** Test seam: replaces the active-profile lookup. */
    internal var activeProfileProvider: () -> Int = { ProfileRepository.activeProfileId }

    @Synchronized fun ensureLoaded() {
        val profile = activeProfileProvider()
        if (loadedProfile == profile) return
        loadedProfile = profile
        mutableState.value = read(profile)
    }

    @Synchronized fun move(id: ListServiceId, up: Boolean) {
        ensureLoaded()
        val order = mutableState.value.order.toMutableList()
        val index = order.indexOf(id)
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in order.indices) return
        order[index] = order[target].also { order[target] = order[index] }
        update(mutableState.value.copy(order = order))
    }

    @Synchronized fun setInTab(id: ListServiceId, enabled: Boolean) {
        ensureLoaded()
        val current = mutableState.value
        update(current.copy(inTab = if (enabled) current.inTab + id else current.inTab - id))
    }

    @Synchronized fun setOnTitlePages(id: ListServiceId, enabled: Boolean) {
        ensureLoaded()
        val current = mutableState.value
        update(current.copy(onTitlePages = if (enabled) current.onTitlePages + id else current.onTitlePages - id))
    }

    @Synchronized fun setLastTabService(id: ListServiceId) {
        ensureLoaded()
        update(mutableState.value.copy(lastTabService = id))
    }

    private fun read(profile: Int): ListPreferences {
        val defaults = defaultListPreferences(legacyMalStore.getString("show_tab_$profile") == "true")
        val savedOrder = store.getString("order_$profile")?.let(::parseIds)
        // Keep the saved order and append any service added since it was saved.
        val order = savedOrder?.let { saved -> saved + defaults.order.filter { it !in saved } } ?: defaults.order
        return ListPreferences(
            order = order,
            inTab = store.getString("tab_$profile")?.let { parseIds(it).toSet() } ?: defaults.inTab,
            onTitlePages = store.getString("title_$profile")?.let { parseIds(it).toSet() } ?: defaults.onTitlePages,
            lastTabService = store.getString("last_tab_$profile")
                ?.let { s -> ListServiceId.entries.firstOrNull { it.storageId == s } },
        )
    }

    private fun update(prefs: ListPreferences) {
        val profile = loadedProfile ?: activeProfileProvider()
        store.putString("order_$profile", prefs.order.joinToString(",") { it.storageId })
        store.putString("tab_$profile", prefs.inTab.joinToString(",") { it.storageId })
        store.putString("title_$profile", prefs.onTitlePages.joinToString(",") { it.storageId })
        store.putString("last_tab_$profile", prefs.lastTabService?.storageId)
        mutableState.value = prefs
    }

    private fun parseIds(raw: String): List<ListServiceId> = raw.split(',').mapNotNull { s ->
        ListServiceId.entries.firstOrNull { it.storageId == s.trim() }
    }.distinct()
}
