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

// The prepared continuation begins underneath fully opaque photo pixels.
// Share this geometry with the photo mask, including outgoing track scenes.
internal const val IMMERSIVE_DIFFUSION_START = 0.63f
internal const val IMMERSIVE_PHOTO_FEATHER_START = 0.67f

internal fun immersivePhotoFeatherAlpha(fraction: Float): Float {
    val y = fraction.coerceIn(0f, 1f)
    if (y >= 1f) return 0f
    val softStart = 1f - IMMERSIVE_SOFT_ARTWORK_FRACTION
    // The old mask removed almost all photographic colour while the blur
    // was strongest. Change only opacity INSIDE the existing soft strip.
    // h'(u) = (3u - 1)^2: monotone, matching the old slope at the join;
    // no opacity rebound/bright stripe, no shifted gradient or larger radius.
    val sourceY = if (y <= softStart) y else {
        val u = ((y - softStart) / IMMERSIVE_SOFT_ARTWORK_FRACTION).coerceIn(0f, 1f)
        softStart + IMMERSIVE_SOFT_ARTWORK_FRACTION * (u - 3f * u * u * (1f - u))
    }
    val t = ((sourceY - IMMERSIVE_PHOTO_FEATHER_START) /
        (1f - IMMERSIVE_PHOTO_FEATHER_START)).coerceIn(0f, 1f)
    return (1f - t * t * t * (t * (t * 6f - 15f) + 10f)).coerceIn(0f, 1f)
}

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
    // Real photo alpha dissolves over its bottom third. The SAME backdrop has
    // to extend under those pixels, otherwise an offscreen mask would merely
    // expose a dark rectangle instead of the continuation of the photograph.
    // The diffuse photo-colour layer must BEGIN before the photo alpha
    // mask starts. Otherwise transparent photo pixels expose a uniform dark
    // floor and produce an artificial horizontal seam just above the blur.
    val photoStart = cover * IMMERSIVE_DIFFUSION_START

    // We cache only a few increasingly diffused levels. The first one is a
    // near-faithful sample of the *last* visible row; later levels borrow
    // from higher rows and further away neighbours in TWO dimensions.
    val edgeColours = (0 until source.width).flatMap { x ->
        (0 until minOf(8, source.height)).map { dy -> source.cell(x, source.height - 1 - dy) }
    }
    val edgeLight = edgeColours.map { maxOf(it.red, it.green, it.blue) }.sorted()
    val medianLight = edgeLight[edgeLight.size / 2]
    val whiteCoverage = edgeColours.count {
        val high = maxOf(it.red, it.green, it.blue)
        high > 0.65f && high - minOf(it.red, it.green, it.blue) < 0.18f
    }.toFloat() / edgeColours.size
    val suppressMarks = medianLight < 0.5f && whiteCoverage < 0.22f
    // Exposure is a property of a source cell, not of every convolution tap.
    // Prepare it once, then evaluate the same triangular 2D kernel as two
    // separable passes. This preserves the palette and finite boundaries.
    val samples = source.cells.map { raw ->
        val light = maxOf(raw.red, raw.green, raw.blue)
        val nearNeutral = light - minOf(raw.red, raw.green, raw.blue) < 0.18f
        val limit = if (suppressMarks && nearNeutral) maxOf(0.28f, medianLight + 0.20f) else 1f
        val exposure = if (light > limit) limit / light else 1f
        Color(raw.red * exposure, raw.green * exposure, raw.blue * exposure)
    }
    val levels = Array(8) { level ->
        val rx = 23 + level * 7
        val ry = (4 + level).coerceAtMost(source.height - 1)
        val vertical = FloatArray(source.width * 3)
        val verticalTotal = ((ry + 1) * (ry + 2) / 2).toFloat()
        for (x in 0 until source.width) {
            for (dy in 0..ry) {
                val sample = samples[(source.height - 1 - dy) * source.width + x]
                val weight = (ry + 1 - dy) / verticalTotal
                vertical[3 * x] += sample.red * weight
                vertical[3 * x + 1] += sample.green * weight
                vertical[3 * x + 2] += sample.blue * weight
            }
        }
        FloatArray(source.width * 3).also { output ->
            for (x in 0 until source.width) {
                var total = 0f
                for (sx in (x - rx).coerceAtLeast(0)..(x + rx).coerceAtMost(source.width - 1)) {
                    val weight = (rx + 1 - kotlin.math.abs(sx - x)).toFloat()
                    output[3 * x] += vertical[3 * sx] * weight
                    output[3 * x + 1] += vertical[3 * sx + 1] * weight
                    output[3 * x + 2] += vertical[3 * sx + 2] * weight
                    total += weight
                }
                for (channel in 0..2) output[3 * x + channel] /= total
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
        // Grow the same blur continuously on BOTH sides of the photo edge.
        // Previously sqrt(p) started at the exact edge, creating a crease in
        // the vertical slope even though the adjoining rows had equal RGB.
        val levelPosition = kotlin.math.sqrt(smoothEdge(photoStart, end, y)) * (levels.size - 1)
        val lo = levelPosition.toInt().coerceIn(0, levels.lastIndex)
        val hi = (lo + 1).coerceAtMost(levels.lastIndex)
        val mix = levelPosition - lo
        // One slow luminous falloff shared with the colour continuation.
        // A second independent 8.5%-tall brightness ramp made a dark
        // horizontal band and abruptly erased the scene below the title.
        val illumination = if (withinPhoto) 1f else 1f - 0.13f * p
        for (px in 0 until width) {
            val sx = (px + 0.5f) / width * (source.width - 1)
            fun value(c: Int): Int {
                // The backdrop carries only diffuse light, never enlarged
                // photographic cells. All real detail belongs to the retained
                // full-resolution photo fading above this continuous field.
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
