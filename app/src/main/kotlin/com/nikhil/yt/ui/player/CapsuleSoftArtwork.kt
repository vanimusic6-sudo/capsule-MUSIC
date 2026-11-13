package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt

/** Once per cover, on a worker: progressively diffuse real photo detail before its alpha feather. */
internal const val IMMERSIVE_SOFT_ARTWORK_FRACTION = 0.16f
internal const val IMMERSIVE_REFERENCE_SOFT_START = 0.54f
internal const val IMMERSIVE_REFERENCE_FADE_START = 0.67f

/** Move the COMPLETE approved long effect into the short bottom strip. */
internal fun immersiveReferencePhotoPosition(fraction: Float): Float {
    if (fraction >= 1f) return 1f
    val start = 1f - IMMERSIVE_SOFT_ARTWORK_FRACTION
    val progress = ((fraction - start) / IMMERSIVE_SOFT_ARTWORK_FRACTION).coerceIn(0f, 1f)
    return IMMERSIVE_REFERENCE_SOFT_START + (1f - IMMERSIVE_REFERENCE_SOFT_START) * progress
}

private fun referenceSmooth(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun immersiveSoftLevelProgress(fraction: Float): Float =
    referenceSmooth((immersiveReferencePhotoPosition(fraction) - IMMERSIVE_REFERENCE_SOFT_START) / 0.40f)

internal fun immersiveSoftCoverage(fraction: Float): Float =
    referenceSmooth((immersiveReferencePhotoPosition(fraction) - IMMERSIVE_REFERENCE_SOFT_START) / 0.17f)

internal fun capsuleSoftArtwork(source: Bitmap, aspect: Float, landscape: Boolean): ImageBitmap {
    val width = 256
    val height = (width / aspect.coerceIn(0.55f, 2.2f)).roundToInt()
    val scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val zoom = if (landscape) 1.06f else 1f
    val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height) * zoom
    val w = source.width * scale
    val h = source.height * scale
    Canvas(scaled).drawBitmap(source, null,
        RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f),
        Paint(Paint.FILTER_BITMAP_FLAG))
    val softStart = 1f - IMMERSIVE_SOFT_ARTWORK_FRACTION
    val outputStart = (height * softStart).toInt()
    // Restore the reference kernels. Two radius-28 passes require 56 rows
    // of context; only the final short strip is retained or drawn.
    val begin = (outputStart - 60).coerceAtLeast(0)
    val stripHeight = height - begin
    val original = IntArray(width * stripHeight)
    scaled.getPixels(original, 0, width, 0, begin, width, stripHeight)
    scaled.recycle()

    fun blur(input: IntArray, radius: Int): IntArray {
        var from = input
        repeat(2) {
            val horizontal = IntArray(from.size)
            val result = IntArray(from.size)
            val rowSums = Array(3) { IntArray(width + 1) }
            for (y in 0 until stripHeight) {
                val sums = rowSums
                for (x in 0 until width) for (c in 0..2)
                    sums[c][x + 1] = sums[c][x] + (from[y * width + x] shr (c * 8) and 255)
                for (x in 0 until width) {
                    val left = (x - radius).coerceAtLeast(0)
                    val right = (x + radius + 1).coerceAtMost(width)
                    var pixel = -0x1000000
                    for (c in 0..2) pixel = pixel or (((sums[c][right] - sums[c][left]) / (right - left)) shl (c * 8))
                    horizontal[y * width + x] = pixel
                }
            }
            val columnSums = Array(3) { IntArray(stripHeight + 1) }
            for (x in 0 until width) {
                val sums = columnSums
                for (y in 0 until stripHeight) for (c in 0..2)
                    sums[c][y + 1] = sums[c][y] + (horizontal[y * width + x] shr (c * 8) and 255)
                for (y in 0 until stripHeight) {
                    val top = (y - radius).coerceAtLeast(0)
                    val bottom = (y + radius + 1).coerceAtMost(stripHeight)
                    var pixel = -0x1000000
                    for (c in 0..2) pixel = pixel or (((sums[c][bottom] - sums[c][top]) / (bottom - top)) shl (c * 8))
                    result[y * width + x] = pixel
                }
            }
            from = result
        }
        return from
    }
    // Exact kernel sequence from the user-approved c485859 reference.
    val levels = listOf(original, blur(original, 2), blur(original, 6), blur(original, 14), blur(original, 28))
    val outputHeight = height - outputStart
    val output = IntArray(width * outputHeight)
    for (y in outputStart until height) {
        val fraction = (y + 0.5f) / height
        val level = immersiveSoftLevelProgress(fraction) * (levels.size - 1)
        val lo = level.toInt().coerceAtMost(levels.lastIndex)
        val hi = (lo + 1).coerceAtMost(levels.lastIndex)
        val mix = level - lo
        val alpha = if (y == outputStart) 0f else immersiveSoftCoverage(fraction)
        for (x in 0 until width) {
            val index = (y - begin) * width + x
            var pixel = (alpha * 255f).roundToInt() shl 24
            for (c in 0..2) {
                val a = levels[lo][index] shr (c * 8) and 255
                val b = levels[hi][index] shr (c * 8) and 255
                pixel = pixel or ((a + (b - a) * mix).roundToInt() shl (c * 8))
            }
            output[(y - outputStart) * width + x] = pixel
        }
    }
    return Bitmap.createBitmap(width, outputHeight, Bitmap.Config.ARGB_8888).apply {
        setPixels(output, 0, width, 0, 0, width, outputHeight)
    }.asImageBitmap()
}

internal val immersiveSoftArtworkCache = CapsuleImmersivePaintCache()
