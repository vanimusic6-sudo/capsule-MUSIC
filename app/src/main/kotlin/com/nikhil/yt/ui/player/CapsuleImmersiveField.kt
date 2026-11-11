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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

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
 * 7-tap separable Gaussian smoothing at the CELL scale; a second pass
 * gives a broad colour atmosphere. Tiny lettering/subjects are diffused
 * while region position is kept. No full-resolution bitmap blur or layer.
 */
private fun gaussianPass(source: FloatArray, width: Int, height: Int): FloatArray {
    val k = floatArrayOf(0.03008f, 0.10498f, 0.22225f, 0.28538f,
        0.22225f, 0.10498f, 0.03008f)
    val tmp = FloatArray(source.size)
    val out = FloatArray(source.size)
    for (y in 0 until height) for (x in 0 until width) {
        val dst = (y * width + x) * 3
        for (delta in -3..3) {
            val ix = (y * width + (x + delta).coerceIn(0, width - 1)) * 3
            val weight = k[delta + 3]
            tmp[dst] += source[ix] * weight
            tmp[dst + 1] += source[ix + 1] * weight
            tmp[dst + 2] += source[ix + 2] * weight
        }
    }
    for (y in 0 until height) for (x in 0 until width) {
        val dst = (y * width + x) * 3
        for (delta in -3..3) {
            val iy = ((y + delta).coerceIn(0, height - 1) * width + x) * 3
            val weight = k[delta + 3]
            out[dst] += tmp[iy] * weight
            out[dst + 1] += tmp[iy + 1] * weight
            out[dst + 2] += tmp[iy + 2] * weight
        }
    }
    return out
}

private fun blurredCells(grid: CapsuleColorField): FloatArray {
    val source = FloatArray(grid.cells.size * 3)
    grid.cells.forEachIndexed { index, color ->
        source[index * 3] = color.red
        source[index * 3 + 1] = color.green
        source[index * 3 + 2] = color.blue
    }
    val fine = gaussianPass(source, grid.width, grid.height)
    val broad = gaussianPass(fine, grid.width, grid.height)
    // Perceptually broad, layered ambient lights rather than a pixel-map.
    return FloatArray(fine.size) { index ->
        0.22f * fine[index] + 0.78f * broad[index]
    }
}

private fun cubicWeights(t: Float, out: FloatArray) {
    // Cubic B-spline: smooth first AND second derivative, non-negative
    // weights and no overshoot/ringing on strongly contrasting artwork.
    val t2 = t * t
    val t3 = t2 * t
    out[0] = (1f - 3f * t + 3f * t2 - t3) / 6f
    out[1] = (4f - 6f * t2 + 3f * t3) / 6f
    out[2] = (1f + 3f * t + 3f * t2 - 3f * t3) / 6f
    out[3] = t3 / 6f
}

/** Bicubic spatial sampling; bilinear interpolation caused visible cell facets. */
private fun sample2D(
    source: FloatArray,
    width: Int,
    height: Int,
    x: Float,
    y: Float,
    rgb: FloatArray,
    wx: FloatArray,
    wy: FloatArray,
) {
    val u = x.coerceIn(0f, 1f) * (width - 1)
    val v = y.coerceIn(0f, 1f) * (height - 1)
    val ix = u.toInt()
    val iy = v.toInt()
    cubicWeights(u - ix, wx)
    cubicWeights(v - iy, wy)
    rgb.fill(0f)
    for (dy in -1..2) {
        val cy = (iy + dy).coerceIn(0, height - 1)
        for (dx in -1..2) {
            val cx = (ix + dx).coerceIn(0, width - 1)
            val offset = (cy * width + cx) * 3
            val weight = wy[dy + 1] * wx[dx + 1]
            rgb[0] += source[offset] * weight
            rgb[1] += source[offset + 1] * weight
            rgb[2] += source[offset + 2] * weight
        }
    }
}

/** Never used for normal decoded art; safe neutral placeholder only. */
private fun fieldFallback(bands: List<Color>, fallbackFloor: Color): CapsuleColorField {
    val safe = bands.ifEmpty { listOf(fallbackFloor) }
    return CapsuleColorField(
        CAPSULE_FIELD_COLUMNS,
        CAPSULE_FIELD_ROWS,
        List(CAPSULE_FIELD_COLUMNS * CAPSULE_FIELD_ROWS) { index ->
            val x = (index % CAPSULE_FIELD_COLUMNS + 0.5f) / CAPSULE_FIELD_COLUMNS
            val y = (index / CAPSULE_FIELD_COLUMNS + 0.5f) / CAPSULE_FIELD_ROWS
            val curved = (x + 0.12f * sin(y * 4.8f + x * 1.9f)).coerceIn(0f, 1f)
            lerp(immersiveSoftBandColor(safe, curved), fallbackFloor,
                0.22f + 0.20f * y)
        },
    )
}

/**
 * Generates the photo's overlay and the underlying page using the EXACT
 * same 2D optical coordinates at the cover edge. No extra dark seam.
 * Bitmap work is requested on Dispatchers.Default by the composables.
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
    val grid = field ?: fieldFallback(bands, fallbackFloor)
    val colors = blurredCells(grid)
    val pixels = IntArray(FIELD_WIDTH * FIELD_HEIGHT)
    val rgb = FloatArray(3)
    val wx = FloatArray(4)
    val wy = FloatArray(4)
    val fraction = coverFraction.coerceIn(0.05f, 0.95f)
    val fadeStart = (0.29f - texture.coerceIn(0f, 1f) * 0.04f -
        if (landscape) 0.02f else 0f).coerceAtLeast(0.21f)
    fun toByte(value: Float): Int =
        (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    for (row in 0 until FIELD_HEIGHT) {
        val photoY = (row + 0.5f) / FIELD_HEIGHT
        val pageY = if (photoOverlay) photoY * fraction else photoY
        val extension = ((pageY - fraction) / (1f - fraction).coerceAtLeast(0.01f))
            .coerceIn(0f, 1f)
        val sourceY = if (pageY <= fraction) {
            (pageY / fraction * 0.92f).coerceIn(0f, 1f)
        } else {
            0.92f - 0.29f * smooth(0f, 1f, extension)
        }
        val warp = smooth(fraction, (fraction + 0.34f).coerceAtMost(1f), pageY)
        val shade = 1f - smooth(fraction, 1f, pageY) *
            (if (nearWhite) 0.19f else 0.33f)
        val backdropMix = smooth((fraction + tailFraction).coerceAtMost(0.99f),
            1f, pageY) * 0.14f
        val alpha = if (photoOverlay) smooth(fadeStart, 0.92f, photoY) else 1f
        val a = toByte(alpha) shl 24
        // Precalculate row-only harmonics once, not per pixel.
        val sinY = sin(pageY * 6.6f)
        val cosY = cos(pageY * 4.8f)
        for (column in 0 until FIELD_WIDTH) {
            val x = (column + 0.5f) / FIELD_WIDTH
            val offsetX = (sinY * cos(x * 4.1f) * 0.060f +
                cosY * sin(x * 5.3f) * 0.040f) * warp
            val offsetY = sin(x * 5.2f + pageY * 2.7f) * warp * 0.053f
            sample2D(
                colors, grid.width, grid.height,
                x + offsetX, sourceY + offsetY,
                rgb, wx, wy,
            )
            // Gentle continuous vignette, not squared clipping.
            val fromCenter = kotlin.math.abs(x - 0.5f) * 2f
            val edgeShade = 1f - 0.055f * fromCenter * fromCenter
            val scale = shade * edgeShade * (1f - backdropMix)
            val dither = ((((column * 131 + row * 197 + column * row * 17) xor
                (column * 23 + row * 51)) and 7) - 3.5f) / 1250f
            val r = toByte(rgb[0] * scale + fallbackFloor.red * backdropMix + dither)
            val g = toByte(rgb[1] * scale + fallbackFloor.green * backdropMix + dither)
            val b = toByte(rgb[2] * scale + fallbackFloor.blue * backdropMix + dither)
            pixels[row * FIELD_WIDTH + column] =
                a or (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(FIELD_WIDTH, FIELD_HEIGHT, Bitmap.Config.ARGB_8888)
        .apply { setPixels(pixels, 0, FIELD_WIDTH, 0, 0, FIELD_WIDTH, FIELD_HEIGHT) }
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
