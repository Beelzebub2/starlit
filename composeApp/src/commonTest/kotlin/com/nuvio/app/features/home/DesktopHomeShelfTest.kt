package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.CatalogTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopHomeShelfTest {
    @Test
    fun laterPageDoesNotReplaceTheAlreadyVisiblePrefix() = runBlocking {
        val first = shelf(1..20)
        val fetching = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tail = async {
            completeDesktopHomeShelf(first, initialSkip = 20) { skip, maxItems ->
                assertEquals(20, skip)
                assertEquals(44, maxItems)
                fetching.complete(Unit)
                release.await()
                CatalogPage(items(15..40), rawItemCount = 26, nextSkip = null)
            }
        }
        withTimeout(3_000) { fetching.await() }
        assertEquals(items(1..20), first.items)
        assertFalse(tail.isCompleted)
        release.complete(Unit)
        val full = withTimeout(3_000) { tail.await() }
        assertEquals(items(1..40), full.items)
        assertEquals(46, full.availableItemCount)
        assertFalse(full.hasMore)
    }

    @Test
    fun respectsNextCursorAndStopsOnNonAdvancingPages() = runBlocking {
        val cursors = mutableListOf<Int>()
        val result = completeDesktopHomeShelf(shelf(1..2), 20) { skip, _ ->
            cursors += skip
            CatalogPage(items(3..4), rawItemCount = 2, nextSkip = skip)
        }
        assertEquals(listOf(20), cursors)
        assertEquals(items(1..4), result.items)
        assertFalse(result.hasMore)
    }

    @Test
    fun boundsBothItemCountAndTheNumberOfRequests() = runBlocking {
        val cursors = mutableListOf<Int>()
        val result = completeDesktopHomeShelf(shelf(1..2), 2) { skip, _ ->
            cursors += skip
            CatalogPage(items(1..2), rawItemCount = 2, nextSkip = skip + 2)
        }
        assertEquals(listOf(2, 4, 6), cursors)
        assertTrue(result.hasMore)
        val large = completeDesktopHomeShelf(shelf(1..20), 20) { _, limit ->
            assertEquals(44, limit)
            CatalogPage(items(21..100), rawItemCount = 80, nextSkip = 100)
        }
        assertEquals(items(1..64), large.items)
        assertEquals(100, large.availableItemCount)
        assertTrue(large.hasMore)
    }

    @Test
    fun nonForcedReloadRetainsTailOnlyForTheSameVisiblePrefixAndTarget() {
        val full = shelf(1..64)
        val first = shelf(1..20).copy(items = items(1..20).map { it.copy(name = "Fresh") })
        val retained = checkNotNull(full.retainingDesktopTail(first))
        assertEquals(first.items, retained.items.take(20))
        assertEquals(full.items.drop(20), retained.items.drop(20))
        assertNull(full.retainingDesktopTail(shelf(2..21)))
        assertNull(full.retainingDesktopTail(shelf(1..20, catalogId = "other")))
        assertNull(full.retainingDesktopTail(first.copy(hasMore = false)))
    }

    private fun items(range: IntRange) = range.map { MetaPreview(id = "item-" + it, type = "movie", name = "Fixture " + it) }

    private fun shelf(range: IntRange, catalogId: String = "fixture") = HomeCatalogSection(
        key = catalogId,
        title = "Fixture",
        subtitle = "",
        addonName = "Fixture",
        target = CatalogTarget.Addon("https://example.invalid/manifest.json", "movie", catalogId, supportsPagination = true),
        items = items(range),
        hasMore = true,
    )
}
