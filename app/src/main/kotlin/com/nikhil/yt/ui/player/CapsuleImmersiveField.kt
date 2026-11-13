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

/**
 * Artwork continuation, not a painted colour bar:
 *
 *  - at the join, sample the *actually visible* final photo scanline;
 *  - spread light horizontally and into earlier photo rows with distance;
 *  - settle onto the exact player floor colour without a second dark ramp;
 *  - never paint an opaque beige/grey wash over the lower 20% of the photo.
 *
 * Two tiny textures are computed once on Dispatchers.Default when the cover
 * changes. No fragment/bitmap blur runs while the song is playing.
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
    edgeMap: CapsuleColorField? = null,
): ImageBitmap {
    val source = edgeMap ?: field ?: CapsuleColorField(
        CAPSULE_FIELD_COLUMNS, CAPSULE_FIELD_ROWS,
        List(CAPSULE_FIELD_COLUMNS * CAPSULE_FIELD_ROWS) { index ->
            immersiveSoftBandColor(
                bands.ifEmpty { listOf(fallbackFloor) },
                (index % CAPSULE_FIELD_COLUMNS + 0.5f) / CAPSULE_FIELD_COLUMNS,
            )
        },
    )
    val cover = coverFraction.coerceIn(0.05f, 0.95f)
    val regularEnd = capsuleImmersiveBleedEnd(cover, tailFraction)
    val end = if (nearWhite) cover + (regularEnd - cover) * 0.76f else regularEnd

    val width = EDGE_WIDTH
    val height = EDGE_HEIGHT
    val pixels = IntArray(width * height)
    fun byte(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    // The edge map is already sampled from the visible, overscanned crop.
    // Convolve a progressively larger 2D area into just SEVEN cached levels.
    // Each result is reused across all output rows rather than blurred again.
    val levels = if (photoOverlay) emptyArray<FloatArray>() else Array(7) { level ->
        val rx = 1 + level * 2
        val ry = level.coerceAtMost(source.height - 1)
        FloatArray(source.width * 3).also { output ->
            for (x in 0 until source.width) {
                var red = 0f
                var green = 0f
                var blue = 0f
                var total = 0f
                for (dx in -rx..rx) for (dy in 0..ry) {
                    val cell = source.cell(x + dx, source.height - 1 - dy)
                    val weight = (rx + 1 - kotlin.math.abs(dx)).toFloat() *
                        (ry + 1 - dy).toFloat()
                    red += cell.red * weight
                    green += cell.green * weight
                    blue += cell.blue * weight
                    total += weight
                }
                output[x * 3] = red / total
                output[x * 3 + 1] = green / total
                output[x * 3 + 2] = blue / total
            }
        }
    }

    // The first colour below the photo is its real lower edge. The median
    // is used only to calm small isolated lines deeper in the continuation.
    val dominant = capsuleDominantEdgeColor(source)
    val firstRow = source.height - 1

    for (row in 0 until height) {
        val y = (row + 0.5f) / height
        if (photoOverlay) {
            // Earlier versions faded an OPAQUE flat colour over the bottom
            // 20-25% of the image. That WAS the broad beige strip.
            // Only the final ~3.8% is feathered into the matching edge.
            // The old 95%-opaque edge band showed as a thin dark seam on
            // light covers. Leave nearly all artwork intact; a narrow, low-
            // opacity feather only tucks the last pixels into the backdrop.
            val alpha = smoothEdge(0.982f, 1f, y) * 0.72f
            if (alpha <= 0f) continue
            for (x in 0 until width) {
                val sx = (x + 0.5f) / width * (source.width - 1)
                val x0 = sx.toInt().coerceIn(0, source.width - 1)
                val x1 = (x0 + 1).coerceAtMost(source.width - 1)
                val t = sx - x0
                val a = source.cell(x0, firstRow)
                val b = source.cell(x1, firstRow)
                pixels[row * width + x] = (byte(alpha) shl 24) or
                    (byte(a.red + (b.red - a.red) * t) shl 16) or
                    (byte(a.green + (b.green - a.green) * t) shl 8) or
                    byte(a.blue + (b.blue - a.blue) * t)
            }
            continue
        }

        // Everything above the cover is occluded by the photo. Use its
        // eventual floor colour rather than computing hidden soft lights.
        if (y < cover) {
            val fill = (0xFF shl 24) or
                (byte(fallbackFloor.red) shl 16) or
                (byte(fallbackFloor.green) shl 8) or byte(fallbackFloor.blue)
            java.util.Arrays.fill(pixels, row * width, (row + 1) * width, fill)
            continue
        }
        // Start diffusion fractionally beneath the opaque cover. The 1dp
        // artwork overlap hides resampling gaps without drawing a stripe.
        val seamStart = (cover - 0.003f).coerceAtLeast(0f)
        val progress = smoothEdge(seamStart, end, y)
        val levelPos = progress * (levels.size - 1)
        val lo = levelPos.toInt().coerceIn(0, levels.lastIndex)
        val hi = (lo + 1).coerceAtMost(levels.lastIndex)
        val mix = levelPos - lo
        val softened = progress * 0.46f

        for (x in 0 until width) {
            val sx = (x + 0.5f) / width * (source.width - 1)
            val x0 = sx.toInt().coerceIn(0, source.width - 1)
            val x1 = (x0 + 1).coerceAtMost(source.width - 1)
            val t = sx - x0
            fun colour(channel: Int, average: Float, target: Float): Int {
                val a = levels[lo][x0 * 3 + channel] * (1f - t) +
                    levels[lo][x1 * 3 + channel] * t
                val b = levels[hi][x0 * 3 + channel] * (1f - t) +
                    levels[hi][x1 * 3 + channel] * t
                val local = a + (b - a) * mix
                val diffused = local * (1f - softened) + average * softened
                // Start at the photo edge, end at the EXACT background
                // colour already used by Box: there cannot be a floor seam.
                return byte(diffused * (1f - progress) + target * progress)
            }
            val r = colour(0, dominant.red, fallbackFloor.red)
            val g = colour(1, dominant.green, fallbackFloor.green)
            val b = colour(2, dominant.blue, fallbackFloor.blue)
            pixels[row * width + x] = (0xFF shl 24) or
                (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        .apply { setPixels(pixels, 0, width, 0, 0, width, height) }
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
