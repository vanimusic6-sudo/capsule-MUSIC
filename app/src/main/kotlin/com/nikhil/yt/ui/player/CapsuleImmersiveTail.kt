/*
 * Capsule MUSIC — a faithful miniature of the ACTUAL visible artwork edge.
 * Generated once per decoded album on the worker thread. No runtime shaders,
 * network requests or per-frame sampling of the full-size artwork.
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

private const val IMMERSIVE_TAIL_COLUMNS = 96
private const val IMMERSIVE_TAIL_ROWS = 12

/**
 * ContentScale.Crop is followed by a 1.06x visual overscan for landscape covers.
 * That means the visible BOTTOM is 97.17% down the centre-cropped source,
 * not at the source image's last pixel. Sampling the actual visible lower
 * edge prevents a bright beige/black bar on the first pixel BELOW the image.
 */
internal fun Bitmap.capsuleImmersiveVisibleTail(
    aspect: Float,
    landscape: Boolean,
    fraction: Float = 0.13f,
): CapsuleColorField {
    val span = fraction.coerceIn(0.05f, 0.3f)
    val rows = (IMMERSIVE_TAIL_ROWS * span / 0.13f).roundToInt().coerceAtLeast(4)
    val target = aspect.coerceIn(0.55f, 2.2f)
    val imageAspect = width.toFloat() / height.coerceAtLeast(1)
    val cropW = (if (imageAspect > target) height * target else width.toFloat())
        .toInt().coerceIn(1, width)
    val cropH = (if (imageAspect < target) width / target else height.toFloat())
        .toInt().coerceIn(1, height)
    val cropX = (width - cropW) / 2f
    val cropY = (height - cropH) / 2f
    val zoom = if (landscape) 1.06f else 1f
    val visibleStart = (1f - 1f / zoom) * 0.5f
    val visibleEnd = 1f - visibleStart

    val cells = List(IMMERSIVE_TAIL_COLUMNS * rows) { index ->
        val cx = index % IMMERSIVE_TAIL_COLUMNS
        val cy = index / IMMERSIVE_TAIL_COLUMNS
        var r = 0f
        var g = 0f
        var b = 0f
        // Last requested portion of the actual on-screen crop, with one tiny area sample
        // per cell. The bottommost row is never sampled OUTSIDE the picture.
        for (oy in 0..1) for (ox in 0..1) {
            val fx = visibleStart + ((cx + (ox + 0.5f) / 2f) / IMMERSIVE_TAIL_COLUMNS) /
                zoom
            val fy = visibleEnd - span / zoom +
                ((cy + (oy + 0.5f) / 2f) / rows) * span / zoom
            val px = (cropX + cropW * fx).toInt().coerceIn(0, width - 1)
            val py = (cropY + cropH * fy).toInt().coerceIn(0, height - 1)
            val pixel = getPixel(px, py)
            r += (pixel ushr 16 and 255) / 255f
            g += (pixel ushr 8 and 255) / 255f
            b += (pixel and 255) / 255f
        }
        Color(r / 4f, g / 4f, b / 4f)
    }
    return CapsuleColorField(IMMERSIVE_TAIL_COLUMNS, rows, cells)
}
