package com.nikhil.yt.ui.player

import androidx.compose.ui.graphics.ImageBitmap

/** Small texture-only cache. No Activity, painter or animation tree is retained here. */
internal class CapsuleImmersivePaintCache(private val capacity: Int = 3) {
    private val images = LinkedHashMap<String, ImageBitmap>(capacity, 0.75f, true)

    @Synchronized operator fun get(key: String): ImageBitmap? = images[key]

    @Synchronized operator fun set(key: String, bitmap: ImageBitmap) {
        images[key] = bitmap
        while (images.size > capacity) images.remove(images.keys.first())
    }
}

// A foreground remount can draw the exact previous continuation on its first
// frame, while the full-size artwork remains in Player's existing painter cache.
internal val immersivePaintCache = CapsuleImmersivePaintCache()
