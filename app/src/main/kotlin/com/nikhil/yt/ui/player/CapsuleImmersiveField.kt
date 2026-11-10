/*
 * Capsule MUSIC — spatial artwork continuation.
 * Cached low-resolution GPU texture replaces dozens of independent vertical
 * gradient strips. The same continuous field is drawn underneath the album
 * and over its lower portion, so the bottom edge cannot change colour.
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import kotlin.math.exp
import kotlin.math.roundToInt

// A single filtered image draw replaces 40 rect draws and 40 gradient shaders.
// Small enough to keep CPU work / uploads infrequent, large enough for smooth tones.
private const val FIELD_WIDTH = 96
private const val FIELD_HEIGHT = 144

/** Fallback legacy palette helper kept for tests and degenerate one-colour art. */
internal fun immersiveSpatialBandColor(bands: List<Color>, fraction: Float): Color {
    if (bands.isEmpty()) return IMMERSIVE_NEUTRAL_COLOR
    if (bands.size == 1) return bands[0]
    val position = fraction.coerceIn(0f, 1f) * (bands.size - 1)
    val a = position.toInt().coerceIn(0, bands.lastIndex)
    val b = (a + 1).coerceAtMost(bands.lastIndex)
    return lerp(bands[a], bands[b], position - a)
}

/**
 * Gaussian convolution smooths transitions between modal colour samples.
 * A whole-image average would eliminate red-left / blue-right asymmetry;
 * a sharp linear 7-strip interpolation produces the vertical bars in screenshots.
 */
internal fun immersiveSoftBandColor(bands: List<Color>, fraction: Float): Color {
    if (bands.isEmpty()) return IMMERSIVE_NEUTRAL_COLOR
    if (bands.size == 1) return bands[0]
    val position = fraction.coerceIn(0f, 1f)
    val sigma = 0.17f
    var weightSum = 0f
    var r = 0f
    var g = 0f
    var b = 0f
    bands.forEachIndexed { index, color ->
        val centre = (index + 0.5f) / bands.size
        val delta = (position - centre) / sigma
        val weight = exp(-0.5f * delta * delta)
        weightSum += weight
        r += color.red * weight
        g += color.green * weight
        b += color.blue * weight
    }
    val divisor = weightSum.coerceAtLeast(0.0001f)
    return Color(
        (r / divisor).coerceIn(0f, 1f),
        (g / divisor).coerceIn(0f, 1f),
        (b / divisor).coerceIn(0f, 1f),
    )
}

private fun smooth5(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
    return t * t * t * (t * (t * 6f - 15f) + 10f)
}

/**
 * Near the artwork boundary each side still owns its colour. Farther down,
 * the hues diffusively converge to the cover's overall tone instead of
 * growing into long vertical bars over the transport controls.
 */
internal fun immersiveSpatialDiffusion(pageY: Float, coverFraction: Float): Float {
    val start = coverFraction * 0.91f
    val end = (coverFraction + 0.45f).coerceAtMost(0.995f)
    return smooth5(start, end, pageY) * 0.84f
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
): ImageBitmap {
    // Precompute the horizontally convolved palette ONCE. The inner pixel
    // loop only evaluates two elementary polynomials and a small lerp.
    val hasSpatialColors = bands.size > 1
    val edgeColumn = Array(FIELD_WIDTH) { x ->
        immersiveSoftBandColor(bands, (x + 0.5f) / FIELD_WIDTH)
    }
    val floorColumn = Array(FIELD_WIDTH) { x ->
        val edge = edgeColumn[x]
        if (!hasSpatialColors) fallbackFloor
        else if (nearWhite || edge.isNearWhiteImmersiveBackground()) edge.immersiveMutedAccent()
        else lerp(edge, Color.Black, 0.10f)
    }
    // Global colour is taken from all bands, not the dominant centre object.
    // It becomes the blurred destination only AFTER each side was extended.
    val globalEdge = if (hasSpatialColors) Color(
        bands.sumOf { it.red.toDouble() }.toFloat() / bands.size,
        bands.sumOf { it.green.toDouble() }.toFloat() / bands.size,
        bands.sumOf { it.blue.toDouble() }.toFloat() / bands.size,
    ) else edgeColumn[0]
    val globalFloor = if (!hasSpatialColors) fallbackFloor
        else if (nearWhite || globalEdge.isNearWhiteImmersiveBackground())
            globalEdge.immersiveMutedAccent()
        else lerp(globalEdge, Color.Black, 0.10f)
    val startShade = coverFraction * 0.76f
    val endShade = (coverFraction + tailFraction.coerceAtLeast(0.02f) * 2.9f)
        .coerceAtMost(0.96f).coerceAtLeast(startShade + 0.04f)
    val pixels = IntArray(FIELD_WIDTH * FIELD_HEIGHT)
    val start = (0.46f - texture.coerceIn(0f, 1f) * 0.085f -
        if (landscape) 0.02f else 0f).coerceAtLeast(0.28f)
    for (y in 0 until FIELD_HEIGHT) {
        val photoY = (y + 0.5f) / FIELD_HEIGHT
        val pageY = if (photoOverlay) photoY * coverFraction else photoY
        val shade = smooth5(startShade, endShade, pageY)
        val diffusion = immersiveSpatialDiffusion(pageY, coverFraction)
        val commonTone = lerp(globalEdge, globalFloor, shade)
        val alpha = if (photoOverlay) {
            if (y == FIELD_HEIGHT - 1) 1f else smooth5(start, 0.985f, photoY)
        } else 1f
        for (x in 0 until FIELD_WIDTH) {
            val local = lerp(edgeColumn[x], floorColumn[x], shade)
            val pixel = lerp(local, commonTone, diffusion)
            pixels[y * FIELD_WIDTH + x] = pixel.copy(alpha = alpha).toArgb()
        }
    }
    return Bitmap.createBitmap(FIELD_WIDTH, FIELD_HEIGHT, Bitmap.Config.ARGB_8888)
        .apply { setPixels(pixels, 0, FIELD_WIDTH, 0, 0, FIELD_WIDTH, FIELD_HEIGHT) }
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
): Modifier = this.drawWithCache {
    // Rebuilt when art / geometry changes, never every video frame / animation
    // tick. GPU bilinear sampling produces a continuous surface without strips.
    val textureBitmap = immersiveSpatialFieldBitmap(
        bands, fallbackFloor, coverFraction, tailFraction,
        texture, landscape, nearWhite, photoOverlay,
    )
    val destination = IntSize(size.width.roundToInt().coerceAtLeast(1),
        size.height.roundToInt().coerceAtLeast(1))
    onDrawBehind {
        drawImage(image = textureBitmap, dstSize = destination,
            filterQuality = FilterQuality.Medium)
    }
}
