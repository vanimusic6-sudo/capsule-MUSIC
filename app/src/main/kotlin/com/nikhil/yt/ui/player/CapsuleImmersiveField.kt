/*
 * Capsule MUSIC — image-derived, two-dimensional ambient colour field.
 * No columns/strips/shaders: the field is a softened miniature of the
 * ACTUAL visible artwork or video frame, with two-dimensional diffusion.
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

// Low memory (~55 KB per field). Not a full-screen GPU framebuffer blur.
private const val FIELD_WIDTH = 96
private const val FIELD_HEIGHT = 144

/** Retained for other callers/tests, never used for the new 2D photo field. */
internal fun immersiveSpatialBandColor(bands: List<Color>, fraction: Float): Color {
    if (bands.isEmpty()) return IMMERSIVE_NEUTRAL_COLOR
    if (bands.size == 1) return bands[0]
    val p = fraction.coerceIn(0f, 1f) * (bands.size - 1)
    val index = p.toInt().coerceIn(0, bands.lastIndex)
    return lerp(bands[index], bands[(index + 1).coerceAtMost(bands.lastIndex)], p - index)
}

internal fun immersiveSoftBandColor(bands: List<Color>, fraction: Float): Color {
    if (bands.isEmpty()) return IMMERSIVE_NEUTRAL_COLOR
    val x = fraction.coerceIn(0f, 1f)
    val sigma = 0.29f
    var sum = 0f
    var red = 0f
    var green = 0f
    var blue = 0f
    bands.forEachIndexed { i, c ->
        val d = (x - (i + 0.5f) / bands.size) / sigma
        val w = exp(-0.5f * d * d)
        sum += w
        red += w * c.red
        green += w * c.green
        blue += w * c.blue
    }
    return Color(red / sum, green / sum, blue / sum)
}

private fun smooth(start: Float, finish: Float, value: Float): Float {
    val t = ((value - start) / (finish - start).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Continuity near the art edge; slower diffusion below the cover. */
internal fun immersiveSpatialDiffusion(pageY: Float, coverFraction: Float): Float =
    smooth(coverFraction * 0.64f, (coverFraction + 0.29f).coerceAtMost(0.995f), pageY) * 0.97f

/**
 * A 5x5 separable spatial Gaussian. The 2D source has 120 image-derived
 * samples: the blur removes tiny details while retaining coloured shapes.
 * Runs ONCE per decoded cover/video capture, never on every display frame.
 */
private fun blurredCells(grid: CapsuleColorField): FloatArray {
    val w = grid.width
    val h = grid.height
    val temp = FloatArray(w * h * 3)
    val dst = FloatArray(w * h * 3)
    val kernel = floatArrayOf(0.06136f, 0.24477f, 0.38774f, 0.24477f, 0.06136f)
    for (y in 0 until h) for (x in 0 until w) {
        val base = (y * w + x) * 3
        for (k in -2..2) {
            val col = grid.cell(x + k, y)
            val weight = kernel[k + 2]
            temp[base] += col.red * weight
            temp[base + 1] += col.green * weight
            temp[base + 2] += col.blue * weight
        }
    }
    for (y in 0 until h) for (x in 0 until w) {
        val base = (y * w + x) * 3
        for (k in -2..2) {
            val source = (y + k).coerceIn(0, h - 1) * w * 3 + x * 3
            val weight = kernel[k + 2]
            dst[base] += temp[source] * weight
            dst[base + 1] += temp[source + 1] * weight
            dst[base + 2] += temp[source + 2] * weight
        }
    }
    return dst
}

/** Bilinear sampling from a 2D image, not horizontal strip interpolation. */
private fun sample2D(
    src: FloatArray, width: Int, height: Int,
    x: Float, y: Float, out: FloatArray,
) {
    val fx = x.coerceIn(0f, 1f) * (width - 1)
    val fy = y.coerceIn(0f, 1f) * (height - 1)
    val x0 = fx.toInt().coerceIn(0, width - 1)
    val y0 = fy.toInt().coerceIn(0, height - 1)
    val x1 = (x0 + 1).coerceAtMost(width - 1)
    val y1 = (y0 + 1).coerceAtMost(height - 1)
    val tx = fx - x0
    val ty = fy - y0
    val a = (y0 * width + x0) * 3
    val b = (y0 * width + x1) * 3
    val c = (y1 * width + x0) * 3
    val d = (y1 * width + x1) * 3
    for (k in 0..2) {
        val upper = src[a + k] + (src[b + k] - src[a + k]) * tx
        val lower = src[c + k] + (src[d + k] - src[c + k]) * tx
        out[k] = upper + (lower - upper) * ty
    }
}

/**
 * This fallback exists only for incomplete artwork while the actual decoded
 * image loads. Normal audio and video always supply a real 2D field.
 */
private fun fieldFallback(bands: List<Color>, fallbackFloor: Color): CapsuleColorField {
    val safe = bands.ifEmpty { listOf(fallbackFloor) }
    val values = List(CAPSULE_FIELD_COLUMNS * CAPSULE_FIELD_ROWS) { index ->
        val x = (index % CAPSULE_FIELD_COLUMNS + 0.5f) / CAPSULE_FIELD_COLUMNS
        val y = (index / CAPSULE_FIELD_COLUMNS + 0.5f) / CAPSULE_FIELD_ROWS
        val shiftedX = (x + 0.18f * sin(y * 5.3f + x * 2.4f))
            .coerceIn(0f, 1f)
        lerp(immersiveSoftBandColor(safe, shiftedX), fallbackFloor,
            (0.19f + 0.18f * y).coerceIn(0f, 1f))
    }
    return CapsuleColorField(CAPSULE_FIELD_COLUMNS, CAPSULE_FIELD_ROWS, values)
}

/**
 * One cached ImageBitmap: there are no per-column brushes and no huge
 * intermediate textures. The 2D field warps GENTLY away from the image seam
 * and slowly becomes darker lower down. Fixed microdither prevents low-bit
 * colour steps without a noisy animated overlay.
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
    val source = blurredCells(grid)
    val out = IntArray(FIELD_WIDTH * FIELD_HEIGHT)
    val rgb = FloatArray(3)
    val fraction = coverFraction.coerceIn(0.05f, 0.95f)
    val coverEnd = 0.93f
    val fadeStart = (0.30f - texture.coerceIn(0f, 1f) * 0.035f -
        if (landscape) 0.02f else 0f).coerceAtLeast(0.23f)
    // Complementary image/floor shader at the exact cover line.
    val mergeStart = (fraction - 0.025f).coerceAtLeast(0f)
    val mergeEnd = (fraction + tailFraction * 3f).coerceAtMost(0.98f)
    fun byte(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    for (iy in 0 until FIELD_HEIGHT) {
        val imageY = (iy + 0.5f) / FIELD_HEIGHT
        val pageY = if (photoOverlay) imageY * fraction else imageY
        val below = ((pageY - fraction) / (1f - fraction).coerceAtLeast(0.01f))
            .coerceIn(0f, 1f)
        // The same source coordinate reaches both sides of the photo seam.
        // Under the cover the field curls through the image instead of
        // extending any column vertically: the crucial non-striped property.
        val sourceY = if (pageY <= fraction)
            (pageY / fraction * coverEnd).coerceIn(0f, 1f)
        else (coverEnd - 0.34f * smooth(0f, 1f, below))
        val warpAmount = smooth(mergeStart, (fraction + 0.22f).coerceAtMost(0.99f),
            pageY) * 0.13f
        val darken = smooth(fraction, 0.99f, pageY) *
            (if (nearWhite) 0.17f else 0.30f)
        val neutral = smooth((fraction + 0.08f).coerceAtMost(0.98f), 1f, pageY) * 0.12f
        val alpha = if (photoOverlay) smooth(fadeStart, 0.92f, imageY) else 1f
        for (ix in 0 until FIELD_WIDTH) {
            val nx = (ix + 0.5f) / FIELD_WIDTH
            val x = nx + warpAmount * (
                sin(pageY * 7.1f + nx * 2.8f) * 0.75f +
                    cos(pageY * 4.5f - nx * 5.4f) * 0.25f
                )
            sample2D(source, grid.width, grid.height, x, sourceY, rgb)
            // The blackening starts at the boundary, not halfway into
            // the image: no dark horizontal gap at the photo seam.
            val shade = 1f - darken
            val drift = neutral
            val noise = (((ix * 131 + iy * 197 + ix * iy * 17) xor
                (ix * 23 + iy * 51)) and 3) - 1.5f
            val dither = noise / 700f
            val r = byte(rgb[0] * shade * (1f - drift) +
                fallbackFloor.red * drift + dither)
            val g = byte(rgb[1] * shade * (1f - drift) +
                fallbackFloor.green * drift + dither)
            val b = byte(rgb[2] * shade * (1f - drift) +
                fallbackFloor.blue * drift + dither)
            out[iy * FIELD_WIDTH + ix] = (byte(alpha) shl 24) or
                (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(FIELD_WIDTH, FIELD_HEIGHT, Bitmap.Config.ARGB_8888)
        .apply { setPixels(out, 0, FIELD_WIDTH, 0, 0, FIELD_WIDTH, FIELD_HEIGHT) }
        .asImageBitmap()
}

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
            image = bitmap,
            dstSize = destination,
            filterQuality = FilterQuality.Medium,
        )
    }
}
