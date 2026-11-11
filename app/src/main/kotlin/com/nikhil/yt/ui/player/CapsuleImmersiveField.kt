/*
 * Capsule MUSIC — cinematic, image-derived ambient colour.
 *
 * No bars, no tiled rectangles, no sharp grid cell edges. A small real
 * image is blurred at two spatial scales, reconstructed with a cubic
 * B-spline, and drawn as ONE immutable GPU texture.
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntSize
import kotlin.math.exp
import kotlin.math.roundToInt

// 192x304 (~228 KiB). Prepared on a worker, never every animation frame.

/** Compatibility helpers for existing fallback and regression tests. */
internal fun immersiveSpatialBandColor(bands: List<Color>, fraction: Float): Color {
    if (bands.isEmpty()) return IMMERSIVE_NEUTRAL_COLOR
    if (bands.size == 1) return bands[0]
    val position = fraction.coerceIn(0f, 1f) * (bands.size - 1)
    val first = position.toInt().coerceIn(0, bands.lastIndex)
    return lerp(bands[first], bands[(first + 1).coerceAtMost(bands.lastIndex)], position - first)
}

internal fun immersiveSoftBandColor(bands: List<Color>, fraction: Float): Color {
    if (bands.isEmpty()) return IMMERSIVE_NEUTRAL_COLOR
    val x = fraction.coerceIn(0f, 1f)
    var weight = 0f
    var r = 0f
    var g = 0f
    var b = 0f
    bands.forEachIndexed { index, value ->
        val dx = (x - (index + 0.5f) / bands.size) / 0.29f
        val w = exp(-0.5f * dx * dx)
        r += value.red * w; g += value.green * w; b += value.blue * w
        weight += w
    }
    return Color(r / weight, g / weight, b / weight)
}

private fun smooth(from: Float, to: Float, value: Float): Float {
    val t = ((value - from) / (to - from).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun immersiveSpatialDiffusion(pageY: Float, coverFraction: Float): Float =
    smooth(coverFraction * 0.64f, (coverFraction + 0.29f).coerceAtMost(0.995f), pageY) * 0.97f

/**
 * EDGE-ONLY BLEED. The former 20×16 projected image produced floating
 * squares, long colour rays and dirty black patches. This renderer reads
 * ONLY the lower crop, preserves its x-position at the boundary, and
 * reaches a stable median-derived floor within a short distance.
 */
private const val EDGE_WIDTH = 144
private const val EDGE_HEIGHT = 176

private fun smoothEdge(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun capsuleImmersiveBleedEnd(cover: Float, extension: Float): Float =
    (cover + extension.coerceIn(0.02f, 0.06f)).coerceAtMost(0.985f)

/** Choose an actual lower-crop colour, with support across the image.
 * Channel-wise medians could invent a hue absent from the source. A small
 * quantised mode rejects narrow black borders and sparse bright lettering.
 */
internal fun capsuleDominantEdgeColor(field: CapsuleColorField): Color {
    val samples = (0 until field.width).flatMap { x ->
        (maxOf(0, field.height - 4) until field.height).map { y -> field.cell(x, y) }
    }
    fun bucket(c: Color): Int =
        ((c.red * 7f).roundToInt() shl 6) or
            ((c.green * 7f).roundToInt() shl 3) or (c.blue * 7f).roundToInt()
    val groups = samples.groupBy(::bucket)
    val dominant = groups.values.maxBy { it.size }
    return Color(dominant.sumOf { it.red.toDouble() }.toFloat() / dominant.size,
        dominant.sumOf { it.green.toDouble() }.toFloat() / dominant.size,
        dominant.sumOf { it.blue.toDouble() }.toFloat() / dominant.size)
}

/** Retain the source hue and dark colours; cap only bright floors for white controls. */
internal fun capsuleImmersiveFloorColor(edge: Color): Color {
    val peak = maxOf(edge.red, edge.green, edge.blue)
    val scale = if (peak > 0.44f) 0.44f / peak else 1f
    return Color(edge.red * scale, edge.green * scale, edge.blue * scale)
}

/**
 * One short dissolve to a coherent lower-crop colour. No per-column rays:
 * local edge details disappear INSIDE the photo, before reaching the floor.
 * The page and photo sample exactly the same curve at their shared boundary.
 */
internal fun immersiveSpatialFieldBitmap(
    bands: List<Color>,
    fallbackFloor: Color,
    coverFraction: Float,
    tailFraction: Float,
    texture: Float,
    landscape: Boolean,
    nearWhite: Boolean,
    photoOverlay: Boolean,
    field: CapsuleColorField? = null,
): ImageBitmap {
    val seam = field?.let(::capsuleDominantEdgeColor)
        ?: bands.takeIf { it.isNotEmpty() }?.let { immersiveSoftBandColor(it, 0.5f) }
        ?: fallbackFloor
    val floor = capsuleImmersiveFloorColor(seam)
    val cover = coverFraction.coerceIn(0.05f, 0.95f)
    val end = capsuleImmersiveBleedEnd(cover, tailFraction)
    val pixels = IntArray(EDGE_WIDTH * EDGE_HEIGHT)
    fun channel(x: Float): Int = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    for (row in 0 until EDGE_HEIGHT) {
        val y = (row + 0.5f) / EDGE_HEIGHT
        val pageY = if (photoOverlay) y * cover else y
        val lowerMix = smoothEdge(cover, end, pageY)
        // Behind a shrinking video card the hidden upper wallpaper is uniform.
        val topMix = 1f - smoothEdge(cover * 0.78f, cover, pageY)
        val colour = lerp(seam, floor, maxOf(lowerMix, topMix))
        val opacity = if (photoOverlay) smoothEdge(0.86f, 0.995f, y) else 1f
        val alpha = channel(opacity) shl 24
        for (col in 0 until EDGE_WIDTH) {
            // Neutral dither avoids 8-bit banding without adding a coloured tint.
            val d = if (photoOverlay) 0f else
                ((((col * 71 + row * 137) xor (col * 13 + row * 43)) and 3) - 1.5f) / 1250f
            // The photo overlay approaches the untouched source hue at its end.
            val c = if (photoOverlay) seam else colour
            pixels[row * EDGE_WIDTH + col] = alpha or
                (channel(c.red + d) shl 16) or (channel(c.green + d) shl 8) or channel(c.blue + d)
        }
    }
    return Bitmap.createBitmap(EDGE_WIDTH, EDGE_HEIGHT, Bitmap.Config.ARGB_8888)
        .apply { setPixels(pixels, 0, EDGE_WIDTH, 0, 0, EDGE_WIDTH, EDGE_HEIGHT) }
        .asImageBitmap()
}

/** Compatibility renderer; Immersion's primary path precomputes off-main. */
internal fun Modifier.immersiveSpatialBackdrop(
    bands: List<Color>,
    fallbackFloor: Color,
    coverFraction: Float,
    tailFraction: Float,
    texture: Float,
    landscape: Boolean,
    nearWhite: Boolean,
    photoOverlay: Boolean,
    field: CapsuleColorField? = null,
): Modifier = this.drawWithCache {
    val bitmap = immersiveSpatialFieldBitmap(
        bands, fallbackFloor, coverFraction, tailFraction,
        texture, landscape, nearWhite, photoOverlay, field,
    )
    val destination = IntSize(
        size.width.roundToInt().coerceAtLeast(1),
        size.height.roundToInt().coerceAtLeast(1),
    )
    onDrawBehind {
        drawImage(
            image = bitmap, dstSize = destination,
            filterQuality = FilterQuality.Medium,
        )
    }
}
