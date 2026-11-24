package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.mergeCatalogItems

internal const val DESKTOP_HOME_SHELF_ITEM_LIMIT = 64
private const val DESKTOP_HOME_SHELF_PAGE_LIMIT = 4

/** A completed preview must not shrink to one page when manifests are refreshed independently. */
internal fun HomeCatalogSection.retainingDesktopTail(first: HomeCatalogSection): HomeCatalogSection? {
    if (!first.hasMore || first.items.isEmpty() || items.size <= first.items.size || target != first.target) return null
    if (!items.take(first.items.size).map(MetaPreview::stableKey).equals(first.items.map(MetaPreview::stableKey))) return null
    return first.copy(
        items = mergeCatalogItems(first.items, items.drop(first.items.size)),
        availableItemCount = maxOf(first.availableItemCount, availableItemCount),
        hasMore = hasMore,
    )
}

/** Loads at most three additional pages and never replaces the already-visible prefix. */
internal suspend fun completeDesktopHomeShelf(
    first: HomeCatalogSection,
    initialSkip: Int,
    fetch: suspend (skip: Int, maxItems: Int) -> CatalogPage,
): HomeCatalogSection {
    var items = first.items
    var rawItemCount = first.availableItemCount
    var nextSkip: Int? = initialSkip.takeIf { first.hasMore && it > 0 }
    var pages = 1
    while (pages < DESKTOP_HOME_SHELF_PAGE_LIMIT && items.size < DESKTOP_HOME_SHELF_ITEM_LIMIT) {
        val skip = nextSkip ?: break
        val page = fetch(skip, DESKTOP_HOME_SHELF_ITEM_LIMIT - items.size)
        items = mergeCatalogItems(items, page.items).take(DESKTOP_HOME_SHELF_ITEM_LIMIT)
        rawItemCount += page.rawItemCount
        nextSkip = page.nextSkip?.takeIf { it > skip }
        pages++
    }
    return first.copy(items = items, availableItemCount = rawItemCount, hasMore = nextSkip != null)
}
