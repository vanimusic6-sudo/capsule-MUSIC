/*
 * Capsule MUSIC — cinematic, image-derived ambient colour.
 *
 * Colour columns and Gaussian diffusion, based on the 9c641af renderer.
 * Photo and page read the same cached field at the same physical Y.
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


/** Compatibility helpers for existing fallback and regression tests. */
internal fun immersiveSpatialBandColor(bands: List<Color>, fraction: Float): Color {
    if (bands.isEmpty()) return IMMERSIVE_NEUTRAL_COLOR
    if (bands.size == 1) return bands[0]
    val position = fraction.coerceIn(0f, 1f) * (bands.size - 1)
    val first = position.toInt().coerceIn(0, bands.lastIndex)
    return lerp(bands[first], bands[(first + 1).coerceAtMost(bands.lastIndex)], position - first)
}

internal fun immersiveSoftBandColor(bands: List<Color>, fraction: Float): Color =
    immersiveBlurredBandColor(bands, fraction, 0.29f)

private fun immersiveBlurredBandColor(bands: List<Color>, fraction: Float, radius: Float): Color {
    if (bands.isEmpty()) return IMMERSIVE_NEUTRAL_COLOR
    val x = fraction.coerceIn(0f, 1f)
    var weight = 0f
    var r = 0f
    var g = 0f
    var b = 0f
    bands.forEachIndexed { index, value ->
        val dx = (x - (index + 0.5f) / bands.size) / radius
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

/** Small precomputed textures; no full-screen CPU rasterization per frame. */
private const val EDGE_WIDTH = 144
private const val EDGE_HEIGHT = 176

private fun smoothEdge(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun capsuleImmersiveBleedEnd(cover: Float, extension: Float): Float =
    (cover + extension.coerceIn(0.02f, 0.06f)).coerceAtMost(0.985f)

/** A robust lower-crop representative. Use the actual colour nearest the
 * channel medians: narrow dark borders cannot win just because a textured
 * bright background is split across many quantisation buckets.
 */
internal fun capsuleDominantEdgeColor(field: CapsuleColorField): Color {
    val samples = (0 until field.width).flatMap { x ->
        (maxOf(0, field.height - 4) until field.height).map { y -> field.cell(x, y) }
    }
    fun median(channel: (Color) -> Float): Float =
        samples.map(channel).sorted()[samples.size / 2]
    val r = median { it.red }
    val g = median { it.green }
    val b = median { it.blue }
    return samples.minBy { c ->
        val dr = c.red - r
        val dg = c.green - g
        val db = c.blue - b
        dr * dr + dg * dg + db * db
    }
}

/** Retain the source hue and dark colours; cap only bright floors for white controls. */
internal fun capsuleImmersiveFloorColor(edge: Color): Color {
    val peak = maxOf(edge.red, edge.green, edge.blue)
    val scale = if (peak > 0.44f) 0.44f / peak else 1f
    return Color(edge.red * scale, edge.green * scale, edge.blue * scale)
}

/**
 * Restore the previous coloured-column continuation, with progressively wider
 * Gaussian feathering. The photograph dissolves gradually into this field;
 * the field retains broad left/right colour rather than becoming one flat fill.
 * An isolated dark border on pale art loses support shortly below the seam.
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
    val source = bands.takeIf { it.isNotEmpty() } ?: field?.let { grid ->
        List(grid.width) { x ->
            val lower = (maxOf(0, grid.height - 4) until grid.height).map { y -> grid.cell(x, y) }
            Color(lower.sumOf { it.red.toDouble() }.toFloat() / lower.size,
                lower.sumOf { it.green.toDouble() }.toFloat() / lower.size,
                lower.sumOf { it.blue.toDouble() }.toFloat() / lower.size)
        }
    } ?: listOf(fallbackFloor)
    val dominant = field?.let(::capsuleDominantEdgeColor)
        ?: immersiveSoftBandColor(source, 0.5f)
    val paleMinority = (nearWhite || dominant.isNearWhiteImmersiveBackground()) &&
        source.count { maxOf(it.red, it.green, it.blue) < 0.30f } <= source.size / 3
    val common = if (paleMinority) dominant else Color(
        source.sumOf { it.red.toDouble() }.toFloat() / source.size,
        source.sumOf { it.green.toDouble() }.toFloat() / source.size,
        source.sumOf { it.blue.toDouble() }.toFloat() / source.size,
    )
    val commonFloor = capsuleImmersiveFloorColor(common)
    val local = Array(EDGE_WIDTH) { x ->
        immersiveBlurredBandColor(source, (x + 0.5f) / EDGE_WIDTH, 0.14f)
    }
    val feathered = Array(EDGE_WIDTH) { x ->
        immersiveSoftBandColor(source, (x + 0.5f) / EDGE_WIDTH)
    }
    val cover = coverFraction.coerceIn(0.05f, 0.95f)
    val tail = tailFraction.coerceIn(0.02f, 0.06f)
    val shadeStart = cover * 0.88f
    val shadeEnd = (cover + tail * 1.8f).coerceAtMost(0.995f)
    val fadeStart = (0.62f - texture.coerceIn(0f, 1f) * 0.035f -
        if (landscape) 0.015f else 0f).coerceIn(0.56f, 0.62f)
    val pixels = IntArray(EDGE_WIDTH * EDGE_HEIGHT)
    fun channel(x: Float): Int = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    for (row in 0 until EDGE_HEIGHT) {
        val y = (row + 0.5f) / EDGE_HEIGHT
        val pageY = if (photoOverlay) y * cover else y
        val shade = smoothEdge(shadeStart, shadeEnd, pageY)
        val spread = smoothEdge(cover * 0.65f, cover + tail * 1.6f, pageY)
        val diffuse = if (paleMinority) smoothEdge(cover, cover + tail, pageY)
            else smoothEdge(cover, (cover + 0.28f).coerceAtMost(0.995f), pageY) * 0.60f
        // The hidden upper page stays quiet beside the smaller video window.
        val emerge = smoothEdge(cover * 0.52f, cover * 0.90f, pageY)
        val opacity = if (photoOverlay) smoothEdge(fadeStart, 0.985f, y) else 1f
        val alpha = channel(opacity) shl 24
        for (col in 0 until EDGE_WIDTH) {
            val edge = lerp(local[col], feathered[col], spread)
            val own = lerp(edge, capsuleImmersiveFloorColor(edge), shade)
            val shared = lerp(common, commonFloor, shade)
            val colour = lerp(commonFloor, lerp(own, shared, diffuse), emerge)
            // Identical physical-Y colour on photo/page; only photo alpha differs.
            val d = ((((col * 71 + row * 137) xor (col * 13 + row * 43)) and 3) - 1.5f) / 1250f
            pixels[row * EDGE_WIDTH + col] = alpha or
                (channel(colour.red + d) shl 16) or
                (channel(colour.green + d) shl 8) or channel(colour.blue + d)
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
