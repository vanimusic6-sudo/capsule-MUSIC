package com.nikhil.yt.ui.component

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Cached with the image, not recomputed during scrolling. Works on Android 8 and later. */
internal object ArtistPortraitBlurTransformation : Transformation() {
    override val cacheKey = "capsule-artist-progressive-blur-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap =
        createArtistPortraitBlur(input)
}

/** Three separable box passes approximate a soft blur whose radius grows down the photo. */
internal suspend fun createArtistPortraitBlur(source: Bitmap): Bitmap = withContext(Dispatchers.Default) {
    val scale = minOf(1f, 1600f / maxOf(source.width, source.height))
    val width = (source.width * scale).roundToInt().coerceAtLeast(1)
    val height = (source.height * scale).roundToInt().coerceAtLeast(1)
    val preview = Bitmap.createScaledBitmap(source, width, height, true)
    val pixels = IntArray(width * height)
    try {
        preview.getPixels(pixels, 0, width, 0, 0, width, height)
    } finally {
        // The input belongs to Coil; never mutate or recycle its cached bitmap.
        if (preview !== source) preview.recycle()
    }
    val scratch = IntArray(pixels.size)
    val radius = FloatArray(height) { y ->
        val progress = ((y.toFloat() / (height - 1).coerceAtLeast(1) - 0.42f) / 0.58f).coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        eased * (height * 0.026f).coerceAtMost(32f)
    }
    val prefix = Array(4) { IntArray(maxOf(width, height) + 1) }

    suspend fun pass(from: IntArray, to: IntArray, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val stride = if (horizontal) 1 else width
        for (line in 0 until lines) {
            currentCoroutineContext().ensureActive()
            val start = if (horizontal) line * width else line
            for (channel in 0..3) {
                val sums = prefix[channel]
                val shift = channel * 8
                sums[0] = 0
                for (i in 0 until length) {
                    sums[i + 1] = sums[i] + (from[start + i * stride] ushr shift and 255)
                }
            }
            for (i in 0 until length) {
                val index = start + i * stride
                val r = radius[if (horizontal) line else i]
                if (r == 0f) {
                    to[index] = from[index]
                    continue
                }
                val whole = r.toInt()
                val fraction = r - whole
                val low = (i - whole).coerceAtLeast(0)
                val high = (i + whole + 1).coerceAtMost(length)
                val widerLow = (low - 1).coerceAtLeast(0)
                val widerHigh = (high + 1).coerceAtMost(length)
                var pixel = 0
                for (channel in 0..3) {
                    val sums = prefix[channel]
                    val narrow = (sums[high] - sums[low]).toFloat() / (high - low)
                    val wide = (sums[widerHigh] - sums[widerLow]).toFloat() / (widerHigh - widerLow)
                    val value = (narrow + (wide - narrow) * fraction).roundToInt().coerceIn(0, 255)
                    pixel = pixel or (value shl (channel * 8))
                }
                to[index] = pixel
            }
        }
    }

    repeat(3) {
        pass(pixels, scratch, horizontal = true)
        pass(scratch, pixels, horizontal = false)
    }
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
