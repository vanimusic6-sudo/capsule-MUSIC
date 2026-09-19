package com.nikhil.yt.ui.component

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** A bounded blur computed once off the UI thread, including on Android 8–11. */
internal fun createAlbumArtworkBlur(source: Bitmap): Bitmap {
    val scale = minOf(1f, 128f / maxOf(source.width, source.height))
    val width = (source.width * scale).roundToInt().coerceAtLeast(1)
    val height = (source.height * scale).roundToInt().coerceAtLeast(1)
    val preview = Bitmap.createScaledBitmap(source, width, height, true)
    val input = IntArray(width * height)
    preview.getPixels(input, 0, width, 0, 0, width, height)
    // Never recycle the image owned by Coil's memory cache.
    if (preview !== source) preview.recycle()
    val horizontal = IntArray(input.size)
    val output = IntArray(input.size)
    val radius = 5
    val samples = radius * 2 + 1
    fun pass(from: IntArray, to: IntArray, alongX: Boolean) {
        for (y in 0 until height) for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (delta in -radius..radius) {
                val sampleX = if (alongX) (x + delta).coerceIn(0, width - 1) else x
                val sampleY = if (alongX) y else (y + delta).coerceIn(0, height - 1)
                val pixel = from[sampleY * width + sampleX]
                alpha += pixel ushr 24
                red += pixel ushr 16 and 255
                green += pixel ushr 8 and 255
                blue += pixel and 255
            }
            to[y * width + x] = ((alpha / samples) shl 24) or ((red / samples) shl 16) or
                ((green / samples) shl 8) or (blue / samples)
        }
    }
    pass(input, horizontal, alongX = true)
    pass(horizontal, output, alongX = false)
    return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
}
