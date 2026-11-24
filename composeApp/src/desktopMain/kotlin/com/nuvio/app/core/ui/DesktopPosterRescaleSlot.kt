package com.nuvio.app.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
private val DesktopPosterRasterDispatcher = Dispatchers.Default.limitedParallelism(
    (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4),
)

/** One requested size and one completed raster per visible painter. Calls are on the Compose thread. */
internal class DesktopPosterRescaleSlot<T : Any>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = DesktopPosterRasterDispatcher,
    private val rasterize: (IntSize) -> T,
) {
    private data class Request(val size: IntSize)

    private var requested: Request? = null
    private var pending: Job? = null
    private var cached by mutableStateOf<Pair<IntSize, T>?>(null)

    fun request(size: IntSize): T? {
        val hit = cached?.takeIf { it.first == size }
        if (hit != null) {
            if (requested?.size != size) {
                pending?.cancel()
                pending = null
                requested = Request(size)
            }
            return hit.second
        }
        if (requested?.size == size) return null
        val request = Request(size)
        requested = request
        pending?.cancel()
        pending = scope.launch {
            // While resizing the painter draws from the original at its usual
            // filter quality; this worker never performs canvas drawing.
            val result = try {
                withContext(dispatcher) { rasterize(size) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            if (result != null && requested === request) cached = size to result
        }
        return null
    }

    fun cancelPending() {
        pending?.cancel()
        pending = null
        requested = null
    }

    fun clear() {
        cancelPending()
        if (cached != null) cached = null
    }
}
