package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.memory.MemoryCache

internal actual val platformProvidesImageLoader: Boolean = false

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    return memoryCache {
        // Desktop heap ergonomics scale with system RAM; decoded artwork needs a fixed budget.
        MemoryCache.Builder().maxSizeBytes(96L * 1024 * 1024).build()
    }.components {
        add(SkiaGifDecoder.Factory())
    }
}
