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
/**
 * SCENE CONTINUATION v3.
 *
 * This is deliberately NOT a palette gradient or a colour overlay on the
 * photo. The backdrop takes the last visible rows of the real cropped image,
 * spreads them spatially as the distance grows, and converges on the scene's
 * separate, coherent dark floor. The actual album picture remains untouched.
 *
 * One low-resolution bitmap per selected cover, computed on a worker thread.
 * No dynamic blur, hidden Canvas layers, GPU readbacks or per-frame work.
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
    val width = EDGE_WIDTH
    val height = EDGE_HEIGHT
    val pixels = IntArray(width * height)

    // Old callers can request a photographic overlay. Deliberately return
    // transparent pixels: tinting the photo itself was the recurring stripe.
    if (photoOverlay) {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .apply { setPixels(pixels, 0, width, 0, 0, width, height) }
            .asImageBitmap()
    }

    val source = edgeMap ?: field ?: CapsuleColorField(
        CAPSULE_FIELD_COLUMNS, CAPSULE_FIELD_ROWS,
        List(CAPSULE_FIELD_COLUMNS * CAPSULE_FIELD_ROWS) { index ->
            immersiveSoftBandColor(
                bands.ifEmpty { listOf(fallbackFloor) },
                (index % CAPSULE_FIELD_COLUMNS + 0.5f) / CAPSULE_FIELD_COLUMNS,
            )
        },
    )
    val cover = coverFraction.coerceIn(0.05f, 0.90f)
    // Keep the continuation active across most of the remaining page.
    // Finishing the blend after only ~10% of screen height caused a visible
    // second boundary: a short blur followed by a flat solid-colour floor.
    // The last 7% still converges exactly to fallbackFloor for readability.
    val end = (cover + (1f - cover) * 0.92f).coerceAtMost(0.99f)
    // Real photo alpha dissolves over its bottom 5%. The SAME backdrop has
    // to extend under those pixels, otherwise an offscreen mask would merely
    // expose a dark rectangle instead of the continuation of the photograph.
    val photoStart = cover - cover * 0.050f

    // We cache only a few increasingly diffused levels. The first one is a
    // near-faithful sample of the *last* visible row; later levels borrow
    // from higher rows and further away neighbours in TWO dimensions.
    val levels = Array(8) { level ->
        val rx = level * 3
        val ry = (level + 1) / 2
        FloatArray(source.width * 3).also { output ->
            for (x in 0 until source.width) {
                var r = 0f
                var g = 0f
                var b = 0f
                var total = 0f
                for (dy in 0..ry) for (dx in -rx..rx) {
                    val sample = source.cell(x + dx, source.height - 1 - dy)
                    val horizontal = (rx + 1 - kotlin.math.abs(dx)).toFloat()
                    val vertical = (ry + 1 - dy).toFloat()
                    val w = horizontal * vertical
                    r += sample.red * w
                    g += sample.green * w
                    b += sample.blue * w
                    total += w
                }
                output[3 * x] = r / total
                output[3 * x + 1] = g / total
                output[3 * x + 2] = b / total
            }
        }
    }
    fun channel(level: Int, pos: Float, c: Int): Float {
        val x0 = pos.toInt().coerceIn(0, source.width - 1)
        val x1 = (x0 + 1).coerceAtMost(source.width - 1)
        val frac = pos - x0
        val row = levels[level]
        return row[3 * x0 + c] * (1f - frac) + row[3 * x1 + c] * frac
    }
    fun byte(value: Float) = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val floorPixel = (0xFF shl 24) or
        (byte(fallbackFloor.red) shl 16) or
        (byte(fallbackFloor.green) shl 8) or byte(fallbackFloor.blue)
    val floorChannels = floatArrayOf(fallbackFloor.red, fallbackFloor.green, fallbackFloor.blue)
    for (py in 0 until height) {
        val y = (py + 0.5f) / height
        if (y < photoStart || y >= end) {
            java.util.Arrays.fill(pixels, py * width, (py + 1) * width, floorPixel)
            continue
        }
        val withinPhoto = y < cover
        val p = if (withinPhoto) 0f else smoothEdge(cover, end, y)
        // Blur radius grows with distance while every coloured region retains
        // its x-position. Luminance is eased down separately to protect the
        // title on very bright artwork; real hues are not remixed into grey.
        val levelPosition = kotlin.math.sqrt(p) * (levels.size - 1)
        val lo = levelPosition.toInt().coerceIn(0, levels.lastIndex)
        val hi = (lo + 1).coerceAtMost(levels.lastIndex)
        val mix = levelPosition - lo
        val illumination = if (withinPhoto) 1f else
            1f - (if (nearWhite) 0.67f else 0.52f) *
                smoothEdge(cover, (cover + 0.085f).coerceAtMost(end), y)
        val photoDistance = if (withinPhoto)
            smoothEdge(photoStart, cover, y) else 1f
        val photoRow = (source.height - 1f -
            (1f - photoDistance) * minOf(4, source.height - 1)).coerceIn(
            0f, (source.height - 1).toFloat())
        val photoY0 = photoRow.toInt()
        val photoY1 = (photoY0 + 1).coerceAtMost(source.height - 1)
        val photoYmix = photoRow - photoY0
        for (px in 0 until width) {
            val sx = (px + 0.5f) / width * (source.width - 1)
            val sampleX0 = sx.toInt().coerceIn(0, source.width - 1)
            val sampleX1 = (sampleX0 + 1).coerceAtMost(source.width - 1)
            val sampleXmix = sx - sampleX0
            fun value(c: Int): Int {
                if (withinPhoto) {
                    fun component(x: Int, y: Int): Float {
                        val color = source.cell(x, y)
                        return when (c) {
                            0 -> color.red
                            1 -> color.green
                            else -> color.blue
                        }
                    }
                    val top = component(sampleX0, photoY0) * (1f - sampleXmix) +
                        component(sampleX1, photoY0) * sampleXmix
                    val bottom = component(sampleX0, photoY1) * (1f - sampleXmix) +
                        component(sampleX1, photoY1) * sampleXmix
                    return byte(top * (1f - photoYmix) + bottom * photoYmix)
                }
                val start = channel(lo, sx, c)
                val blurred = start + (channel(hi, sx, c) - start) * mix
                // The lights fade across the whole lower screen rather than
                // being snapped to the floor just below the song metadata.
                return byte(blurred * illumination * (1f - p) + floorChannels[c] * p)
            }
            pixels[py * width + px] = (0xFF shl 24) or
                (value(0) shl 16) or (value(1) shl 8) or value(2)
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
