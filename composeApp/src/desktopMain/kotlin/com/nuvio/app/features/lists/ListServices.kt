package com.nuvio.app.features.lists

import com.nuvio.app.features.details.MetaDetails

object ListServices {
    /** Tests (and later UI tests) inject fakes here. */
    internal var overrideForTests: List<ListService>? = null

    // Adapters are added as Tasks 3-6 land.
    private val registered: List<ListService> = emptyList()

    fun all(): List<ListService> = overrideForTests ?: registered

    fun service(id: ListServiceId): ListService =
        all().firstOrNull { it.id == id } ?: error("No list service registered for $id")
}

fun titleCandidates(
    services: List<ListService>,
    prefs: ListPreferences,
    kind: ListTitleKind,
    meta: MetaDetails,
): List<ListService> = services
    .filter { it.connected.value && it.id in prefs.onTitlePages && it.covers(kind, meta) }
    .sortedBy { orderIndex(prefs, it.id) }

fun tabServices(services: List<ListService>, prefs: ListPreferences): List<ListService> = services
    .filter { it.connected.value && it.id in prefs.inTab }
    .sortedBy { orderIndex(prefs, it.id) }

private fun orderIndex(prefs: ListPreferences, id: ListServiceId): Int =
    prefs.order.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it }
