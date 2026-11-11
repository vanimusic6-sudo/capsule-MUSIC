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
private const val FIELD_WIDTH = 192
private const val FIELD_HEIGHT = 304

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
    (cover + extension.coerceIn(0.02f, 0.08f) * 1.12f).coerceAtMost(0.985f)

/** The isolated dark stripe must NOT change the colour of a mostly pale floor. */
internal fun capsuleDominantEdgeColor(field: CapsuleColorField): Color {
    val cells = (0 until field.width).map { x ->
        lerp(field.cell(x, field.height - 2),
             field.cell(x, field.height - 1), 0.78f)
    }
    fun median(channel: (Color) -> Float): Float =
        cells.map(channel).sorted()[cells.size / 2]
    return Color(median { it.red }, median { it.green }, median { it.blue })
}

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
    val source = field ?: CapsuleColorField(
        CAPSULE_FIELD_COLUMNS, CAPSULE_FIELD_ROWS,
        List(CAPSULE_FIELD_COLUMNS * CAPSULE_FIELD_ROWS) { index ->
            immersiveSoftBandColor(
                bands.ifEmpty { listOf(fallbackFloor) },
                (index % CAPSULE_FIELD_COLUMNS + 0.5f) / CAPSULE_FIELD_COLUMNS,
            )
        },
    )
    val dominant = capsuleDominantEdgeColor(source)
    val floor = Color(
        (0.052f + 0.165f * dominant.red).coerceIn(0f, 1f),
        (0.050f + 0.165f * dominant.green).coerceIn(0f, 1f),
        (0.057f + 0.165f * dominant.blue).coerceIn(0f, 1f),
    )
    val base = (0 until source.width).map { x ->
        lerp(source.cell(x, source.height - 2),
             source.cell(x, source.height - 1), 0.78f)
    }
    val locals = List(source.width) { x ->
        val before = base[(x - 1).coerceAtLeast(0)]
        val after = base[(x + 1).coerceAtMost(base.lastIndex)]
        lerp(lerp(before, after, 0.5f), base[x], 0.78f)
    }
    val support = FloatArray(source.width) { x ->
        val target = locals[x]
        var similar = 0
        for (delta in -3..3) {
            val other = locals[(x + delta).coerceIn(0, locals.lastIndex)]
            val distance = kotlin.math.abs(other.red - target.red) +
                kotlin.math.abs(other.green - target.green) +
                kotlin.math.abs(other.blue - target.blue)
            if (distance < 0.42f) similar++
        }
        similar / 7f
    }
    // Interpolate ONCE per output column, not 25k Compose Color allocations.
    val edge = Array(EDGE_WIDTH) { column ->
        immersiveSpatialBandColor(locals, (column + 0.5f) / EDGE_WIDTH)
    }
    val edgeSupport = FloatArray(EDGE_WIDTH) { column ->
        val pos = (column + 0.5f) / EDGE_WIDTH * (support.size - 1)
        val k = pos.toInt().coerceIn(0, support.lastIndex)
        val next = (k + 1).coerceAtMost(support.lastIndex)
        support[k] + (support[next] - support[k]) * (pos - k)
    }
    val cover = coverFraction.coerceIn(0.05f, 0.95f)
    val normalEnd = capsuleImmersiveBleedEnd(cover, tailFraction)
    val end = if (nearWhite) cover + (normalEnd - cover) * 0.64f else normalEnd
    // Keep the photograph clean longer; only the last ~20% dissolves.
    val fadeStart = (0.78f - texture.coerceIn(0f, 1f) * 0.018f -
        if (landscape) 0.012f else 0f).coerceIn(0.74f, 0.80f)
    val pixels = IntArray(EDGE_WIDTH * EDGE_HEIGHT)
    fun channel(x: Float): Int = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    for (row in 0 until EDGE_HEIGHT) {
        val y = (row + 0.5f) / EDGE_HEIGHT
        val pageY = if (photoOverlay) y * cover else y
        val below = (pageY - cover).coerceAtLeast(0f)
        val floorMix = smoothEdge(cover, end, pageY)
        val drift = smoothEdge(0f, (end - cover) * 0.65f, below) * 0.48f
        // The overlay starts late, and is fully opaque before the photo ends.
        // Both renderers read EXACTLY the same local edge at the join.
        val opacity = if (photoOverlay) smoothEdge(fadeStart, 0.975f, y) else 1f
        val alpha = channel(opacity) shl 24
        for (col in 0 until EDGE_WIDTH) {
            val local = edge[col]
            val decay = smoothEdge(
                0f, (end - cover) * (0.21f + 0.68f * edgeSupport[col]), below,
            )
            val localWeight = 1f - maxOf(decay, drift)
            // No waves, no 2D projection below the image. A local dark stripe
            // dissolves shortly after the seam; the majority colour wins.
            val remaining = 1f - floorMix
            val r = (dominant.red + (local.red - dominant.red) * localWeight) *
                remaining + floor.red * floorMix
            val g = (dominant.green + (local.green - dominant.green) * localWeight) *
                remaining + floor.green * floorMix
            val b = (dominant.blue + (local.blue - dominant.blue) * localWeight) *
                remaining + floor.blue * floorMix
            val d = if (photoOverlay) 0f else
                ((((col * 71 + row * 137) xor (col * 13 + row * 43)) and 3) - 1.5f) / 1250f
            pixels[row * EDGE_WIDTH + col] = alpha or
                (channel(r + d) shl 16) or (channel(g + d) shl 8) or channel(b + d)
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
