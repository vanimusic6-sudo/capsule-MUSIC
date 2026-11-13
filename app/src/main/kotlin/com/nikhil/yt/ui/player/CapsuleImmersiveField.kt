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
private const val EDGE_WIDTH = 192
private const val EDGE_HEIGHT = 240

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
    // Use several actual rows of the visible artwork, not one sampled edge
    // line. A dark twig/logo in the bottommost row cannot become a long bar.
    val edge = Array(source.width) { x ->
        val row0 = source.cell(x, (source.height - 4).coerceAtLeast(0))
        val row1 = source.cell(x, (source.height - 3).coerceAtLeast(0))
        val row2 = source.cell(x, (source.height - 2).coerceAtLeast(0))
        val row3 = source.cell(x, source.height - 1)
        Color(
            row0.red * 0.10f + row1.red * 0.22f + row2.red * 0.36f + row3.red * 0.32f,
            row0.green * 0.10f + row1.green * 0.22f + row2.green * 0.36f + row3.green * 0.32f,
            row0.blue * 0.10f + row1.blue * 0.22f + row2.blue * 0.36f + row3.blue * 0.32f,
        )
    }
    // Multi-scale horizontal diffusion: blur radius grows with distance
    // from the artwork. This is a true convolution on real source colours,
    // not a scaled 20-column stripe map. Only ~120 pixels are filtered.
    val levels = Array(7) { level ->
        val radius = 1 + level
        FloatArray(source.width * 3).also { output ->
            for (x in 0 until source.width) {
                var red = 0f; var green = 0f; var blue = 0f; var total = 0f
                for (dx in -radius..radius) {
                    val sample = edge[(x + dx).coerceIn(0, edge.lastIndex)]
                    val w = (radius + 1 - kotlin.math.abs(dx)).toFloat()
                    red += sample.red * w
                    green += sample.green * w
                    blue += sample.blue * w
                    total += w
                }
                output[x * 3] = red / total
                output[x * 3 + 1] = green / total
                output[x * 3 + 2] = blue / total
            }
        }
    }
    val cover = coverFraction.coerceIn(0.05f, 0.95f)
    val normalEnd = capsuleImmersiveBleedEnd(cover, tailFraction)
    val end = if (nearWhite) cover + (normalEnd - cover) * 0.76f else normalEnd
    val fadeStart = (0.79f - texture.coerceIn(0f, 1f) * 0.018f -
        if (landscape) 0.01f else 0f).coerceIn(0.75f, 0.81f)
    // The remote floor remains dark enough for controls but retains the
    // dominant cover hue; never insert synthetic blue/graphite into it.
    val floor = Color(
        (0.046f + dominant.red * 0.19f).coerceIn(0f, 1f),
        (0.046f + dominant.green * 0.19f).coerceIn(0f, 1f),
        (0.047f + dominant.blue * 0.19f).coerceIn(0f, 1f),
    )
    val pixels = IntArray(EDGE_WIDTH * EDGE_HEIGHT)
    fun byte(x: Float): Int = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    for (row in 0 until EDGE_HEIGHT) {
        val y = (row + 0.5f) / EDGE_HEIGHT
        val pageY = if (photoOverlay) y * cover else y
        val fade = smoothEdge(cover, end, pageY)
        val radialDistance = ((pageY - cover) / (end - cover).coerceAtLeast(0.005f))
            .coerceIn(0f, 1f)
        val levelFloat = radialDistance * (levels.size - 1)
        val level0 = levelFloat.toInt().coerceIn(0, levels.lastIndex)
        val level1 = (level0 + 1).coerceAtMost(levels.lastIndex)
        val levelMix = levelFloat - level0
        val aboveWeight = if (photoOverlay || pageY >= cover) 1f else
            smoothEdge(cover * 0.59f, cover * 0.94f, pageY)
        val opacity = if (photoOverlay) smoothEdge(fadeStart, 0.99f, y) else 1f
        val alpha = byte(opacity) shl 24
        for (col in 0 until EDGE_WIDTH) {
            val pos = (col + 0.5f) / EDGE_WIDTH * (source.width - 1)
            val x0 = pos.toInt().coerceIn(0, source.width - 1)
            val x1 = (x0 + 1).coerceAtMost(source.width - 1)
            val fx = pos - x0
            fun channel(index: Int): Float {
                val low = levels[level0]
                val high = levels[level1]
                val a = low[x0 * 3 + index] * (1f - fx) +
                    low[x1 * 3 + index] * fx
                val b = high[x0 * 3 + index] * (1f - fx) +
                    high[x1 * 3 + index] * fx
                return a * (1f - levelMix) + b * levelMix
            }
            // Horizontal detail disperses gradually. The projection becomes
            // the median background instead of leaving individual colour rays.
            val soften = radialDistance * 0.52f
                fun lit(raw: Float, base: Float, dark: Float): Float {
                val diffuse = raw * (1f - soften) + base * soften
                return (base + (diffuse - base) * aboveWeight) *
                    (1f - fade) + dark * fade
            }
            val r = lit(channel(0), dominant.red, floor.red)
            val g = lit(channel(1), dominant.green, floor.green)
            val b = lit(channel(2), dominant.blue, floor.blue)
            pixels[row * EDGE_WIDTH + col] = alpha or
                (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
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
