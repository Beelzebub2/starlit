package com.nuvio.app.core.ui

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPosterRescaleSlotTest {
    @Test
    fun schedulesOffTheDrawPathAndCoalescesToTheNewestSize() = runTest {
        val sizes = mutableListOf<IntSize>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val slot = DesktopPosterRescaleSlot(this, dispatcher) { size ->
            sizes += size
            size.toString()
        }
        val old = IntSize(100, 150)
        val current = IntSize(140, 210)
        assertNull(slot.request(old))
        assertNull(slot.request(old))
        assertNull(slot.request(current))
        assertEquals(emptyList(), sizes, "Calling from draw cannot rasterize synchronously")
        advanceUntilIdle()
        assertEquals(listOf(current), sizes)
        assertEquals(current.toString(), slot.request(current))
    }

    @Test
    fun clearingTheSlotCancelsWorkAndAResizeFailureDoesNotLoop() = runTest {
        val sizes = mutableListOf<IntSize>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val slot = DesktopPosterRescaleSlot(this, dispatcher) { size ->
            sizes += size
            error("Unsupported raster")
        }
        val size = IntSize(100, 150)

        assertNull(slot.request(size))
        slot.clear()
        advanceUntilIdle()
        assertEquals(emptyList(), sizes)

        assertNull(slot.request(size))
        advanceUntilIdle()
        assertNull(slot.request(size))
        advanceUntilIdle()
        assertEquals(listOf(size), sizes)
    }
}
