/*
 * Capsule MUSIC — image-backed Immersion, GPL-3.0.
 * Composition adapted from ArchiveTune's ImmersiveBackdrop:
 * rukamori/ArchiveTune@0c15cc3b468841b72f6b8ddfaa940844830aa957
 * app/src/main/kotlin/moe/rukamori/archivetune/ui/player/immersive/ImmersivePlayerScreen.kt
 *
 * ArchiveTune combines the stage photo and a rotated continuation beneath a
 * blurred source, then overlays the sharp stage with a mask and scrim. Here
 * the still-photo backdrop is prepared once off-main instead of live Haze.
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI

internal const val IMMERSIVE_VISIBLE_SAMPLE_END = 0.82f
private const val COVER_COLUMNS = 32
private const val COVER_ROWS = 24
private const val PAGE_COLUMNS = 32
private const val PAGE_ROWS = 48
private const val PAINT_WIDTH = 192
private const val PAINT_HEIGHT = 304

/** The same centre crop/overscan as the sharp photo; hidden bottom pixels never enter it. */
internal fun Bitmap.capsuleImmersiveVisibleField(aspect: Float, landscape: Boolean): CapsuleColorField {
    val target = aspect.coerceIn(0.55f, 2.2f)
    val natural = width.toFloat() / height
    val cropWidth = if (natural > target) height * target else width.toFloat()
    val cropHeight = if (natural < target) width / target else height.toFloat()
    val left = (width - cropWidth) * 0.5f
    val top = (height - cropHeight) * 0.5f
    val zoom = if (landscape) 1.06f else 1f
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    val cells = List(COVER_COLUMNS * COVER_ROWS) { index ->
        var r = 0f; var g = 0f; var b = 0f; var count = 0
        for (sy in 0..3) for (sx in 0..3) {
            val x = (index % COVER_COLUMNS + (sx + 0.5f) / 4f) / COVER_COLUMNS
            val y = (index / COVER_COLUMNS + (sy + 0.5f) / 4f) / COVER_ROWS * IMMERSIVE_VISIBLE_SAMPLE_END
            val px = (left + cropWidth * ((x - 0.5f) / zoom + 0.5f)).toInt().coerceIn(0, width - 1)
            val py = (top + cropHeight * ((y - 0.5f) / zoom + 0.5f)).toInt().coerceIn(0, height - 1)
            val pixel = pixels[py * width + px]
            if (pixel ushr 24 < 128) continue
            r += (pixel ushr 16 and 255) / 255f
            g += (pixel ushr 8 and 255) / 255f
            b += (pixel and 255) / 255f
            count++
        }
        if (count == 0) IMMERSIVE_NEUTRAL_COLOR else Color(r / count, g / count, b / count)
    }
    return capsuleImmersiveRejectSmallMarks(CapsuleColorField(COVER_COLUMNS, COVER_ROWS, cells))
}

/** Reject isolated pale labels, but retain broad white paper and saturated real lights. */
private fun capsuleImmersiveRejectSmallMarks(field: CapsuleColorField): CapsuleColorField {
    val light = field.cells.map { maxOf(it.red, it.green, it.blue) }.sorted()
    val median = light[light.size / 2]
    val whiteArea = field.cells.count {
        maxOf(it.red, it.green, it.blue) > 0.80f &&
            maxOf(it.red, it.green, it.blue) - minOf(it.red, it.green, it.blue) < 0.15f
    }.toFloat() / field.cells.size
    if (whiteArea >= 0.20f || median >= 0.65f) return field
    val markThreshold = maxOf(0.55f, median + 0.20f)
    val marks = field.cells.map { c ->
        val high = maxOf(c.red, c.green, c.blue)
        high > markThreshold && high - minOf(c.red, c.green, c.blue) < 0.20f
    }
    val material = field.cells.filterIndexed { index, _ -> !marks[index] }
    if (material.isEmpty()) return field
    fun medianColor(colours: List<Color>): Color {
        val mid = colours.size / 2
        return Color(colours.map { it.red }.sorted()[mid],
            colours.map { it.green }.sorted()[mid], colours.map { it.blue }.sorted()[mid])
    }
    val fallback = medianColor(material)
    return field.copy(cells = field.cells.mapIndexed { index, color ->
        if (!marks[index]) color else {
            val neighbours = ArrayList<Color>(25)
            for (dy in -2..2) for (dx in -2..2) {
                val x = index % field.width + dx; val y = index / field.width + dy
                if (x !in 0 until field.width || y !in 0 until field.height) continue
                if (!marks[y * field.width + x]) neighbours += field.cell(x, y)
            }
            // Preserve a little of the real highlight, but use surrounding
            // RGB material, not a grey exposure cap that creates a pale lamp.
            val local = if (neighbours.isEmpty()) fallback else medianColor(neighbours)
            // For the AMBIENT field, sparse white glyphs must inherit the
            // surrounding material exactly. Even a 10% white residual became
            // a false bright lamp after the new 2D blur (9/255 pixel error).
            // The original, sharp album artwork is never modified.
            local
        }
    })
}

/** Broad visible-image colour, used only as a loading/base tone, never as a seam scanline. */
internal fun capsuleImmersiveVisibleMean(field: CapsuleColorField): Color {
    var r = 0f; var g = 0f; var b = 0f
    for (c in field.cells) { r += c.red; g += c.green; b += c.blue }
    return Color(r / field.cells.size, g / field.cells.size, b / field.cells.size)
}

private val stageMaskPositions = floatArrayOf(0f, 0.44f, 0.58f, 0.70f, 0.82f, 1f)
private val stageMaskOpacity = floatArrayOf(1f, 1f, 0.96f, 0.70f, 0.28f, 0f)

internal fun immersivePhotoFeatherAlpha(fraction: Float): Float {
    if (!fraction.isFinite()) return 0f
    val y = fraction.coerceIn(0f, 1f)
    val stops = stageMaskPositions
    val alpha = stageMaskOpacity
    for (i in 1 until stops.size) if (y <= stops[i]) {
        // Zero slope at each stop: piecewise linear fade created horizontal
        // band edges on detailed covers. Called only for the cached mask.
        val p = ((y - stops[i - 1]) / (stops[i] - stops[i - 1])).coerceIn(0f, 1f)
        val ease = p * p * p * (p * (p * 6f - 15f) + 10f)
        return alpha[i - 1] + (alpha[i] - alpha[i - 1]) * ease
    }
    return 0f
}

private fun gaussianWeights(size: Int, sigma: Float) = FloatArray(size * size).also { weights ->
    for (out in 0 until size) {
        var sum = 0f
        for (source in 0 until size) {
            val d = (source - out) / sigma
            val weight = exp(-0.5f * d * d)
            weights[out * size + source] = weight; sum += weight
        }
        for (source in 0 until size) weights[out * size + source] /= sum
    }
}

private val backdropHorizontalWeights = gaussianWeights(PAGE_COLUMNS, PAGE_COLUMNS * 0.16f)
private val backdropVerticalWeights = gaussianWeights(PAGE_ROWS, PAGE_ROWS * 0.10f)

private class SplineAxis(output: Int, source: Int) {
    val indices = IntArray(output * 4)
    val weights = FloatArray(output * 4)
    init {
        for (p in 0 until output) {
            val position = (p + 0.5f) / output * source - 0.5f
            val base = floor(position).toInt(); val t = position - base
            val t2 = t * t; val t3 = t2 * t
            val w = floatArrayOf((1f - t) * (1f - t) * (1f - t) / 6f,
                (3f * t3 - 6f * t2 + 4f) / 6f,
                (-3f * t3 + 3f * t2 + 3f * t + 1f) / 6f, t3 / 6f)
            for (i in 0..3) { indices[p * 4 + i] = (base + i - 1).coerceIn(0, source - 1); weights[p * 4 + i] = w[i] }
        }
    }
}
private val backdropSplineX = SplineAxis(PAINT_WIDTH, PAGE_COLUMNS)
private val backdropSplineY = SplineAxis(PAINT_HEIGHT, PAGE_ROWS)

/**
 * Keep the generated, blurred continuation horizontally aligned with the actual
 * centre-cropped photo at the seam. The old implementation instantaneously
 * narrowed the sample to stageFraction at pageY == stageFraction, introducing
 * an image-dependent horizontal jump (visible as a stripe on busy covers).
 *
 * Ease the narrowing ONLY below the artwork boundary. The sharp cover and
 * sampled source colour are untouched; no extra GPU layer or frame loop.
 */
internal fun capsuleImmersiveBackdropSourceX(
    pageX: Float,
    pageY: Float,
    stageFraction: Float,
): Float {
    val stage = stageFraction.coerceIn(0.35f, 0.75f)
    val progress = ((pageY - stage) / (1f - stage)).coerceIn(0f, 1f)
    val eased = progress * progress * (3f - 2f * progress)
    val widthScale = 1f - (1f - stage) * eased
    // The old affine X map created long, ruler-straight colour columns
    // regardless of the artwork. A bounded two-dimensional displacement
    // bends the *precomputed* colour field, not the sharp photograph.
    // It is exactly zero at the cover seam and bottom, so neither boundary
    // acquires a new mismatch. No frame-clock, GPU blur or allocations.
    val envelope = sin(PI.toFloat() * progress).let { it * it }
    // A broad, elliptical colour-cloud drift rather than a tiny pixel wiggle.
    // The original 5.5% warp vanished into the horizontal Gaussian blur;
    // this low-frequency 13% displacement remains visible after smoothing.
    val flow = sin(2f * PI.toFloat() *
        (pageY * 1.15f + pageX * 0.25f + 0.21f))
    val bend = 0.13f * envelope * flow
    return (0.5f + (pageX - 0.5f) * widthScale + bend).coerceIn(0f, 1f)
}

/**
 * Reflect the actual sampled lower photo smoothly into the continuation.
 * The previous source Y stayed pinned at 0.82 over a large region, forming
 * a perfectly horizontal flat band. Both halves now join at 0.82 without
 * any clamped plateau, then diffuse deeper into the picture.
 */
internal fun capsuleImmersiveBackdropSourceY(
    pageX: Float, pageY: Float, stageFraction: Float,
): Float {
    val stage = stageFraction.coerceIn(0.35f, 0.75f)
    if (pageY <= stage) {
        return ((pageY / stage).coerceIn(0f, 1f) * IMMERSIVE_VISIBLE_SAMPLE_END)
    }
    val progress = ((pageY - stage) / (1f - stage)).coerceIn(0f, 1f)
    val envelope = sin(PI.toFloat() * progress).let { it * it }
    val curved = 0.045f * envelope * cos(
        2f * PI.toFloat() * (pageX * 0.93f + pageY * 0.43f)
    )
    return (IMMERSIVE_VISIBLE_SAMPLE_END - progress * 0.43f + curved)
        .coerceIn(0f, IMMERSIVE_VISIBLE_SAMPLE_END)
}

/**
 * Small regional colour histogram, inspired by Metrolist's preference for
 * colours that occupy real artwork area rather than one arbitrary edge pixel.
 * Unlike a global primary swatch, a separate x-column anchor preserves
 * left/right colour composition and never invents hue on neutral covers.
 *
 * Work happens once on the worker that prepares the bitmap, not per frame.
 */
internal fun capsuleImmersiveArtworkAccentColumns(field: CapsuleColorField): List<Color?> {
    if (field.width <= 0 || field.height <= 0) return emptyList()
    val columns = ArrayList<Color?>(field.width)
    for (x in 0 until field.width) {
        val sumsR = FloatArray(18)
        val sumsG = FloatArray(18)
        val sumsB = FloatArray(18)
        val scores = FloatArray(18)
        var eligible = 0
        var total = 0
        var nearWhite = 0
        val range = (x - 2).coerceAtLeast(0)..(x + 2).coerceAtMost(field.width - 1)
        val hsv = FloatArray(3)
        for (y in 0 until field.height) for (sx in range) {
            val c = field.cell(sx, y)
            val bright = maxOf(c.red, c.green, c.blue)
            val dark = minOf(c.red, c.green, c.blue)
            total++
            if (bright > 0.80f && bright - dark < 0.12f) nearWhite++
            if (bright < 0.17f || bright - dark < 0.075f) continue
            android.graphics.Color.colorToHSV(c.toArgb(), hsv)
            val saturation = hsv[1]
            if (saturation < 0.24f) continue
            eligible++
            val hueBin = (hsv[0] / 20f).toInt().coerceIn(0, 17)
            // Dominant population, not a tiny saturated logo.
            val weight = 0.8f + 0.2f * saturation
            scores[hueBin] += weight
            sumsR[hueBin] += c.red * weight
            sumsG[hueBin] += c.green * weight
            sumsB[hueBin] += c.blue * weight
        }
        val selected = scores.indices.maxByOrNull { scores[it] } ?: 0
        val share = eligible.toFloat() / total.coerceAtLeast(1)
        val whiteShare = nearWhite.toFloat() / total.coerceAtLeast(1)
        val winnerShare = scores[selected] / eligible.coerceAtLeast(1)
        if (share < 0.24f || whiteShare > 0.40f || winnerShare < 0.42f) {
            columns += null
        } else {
            val n = scores[selected].coerceAtLeast(0.001f)
            val source = Color(sumsR[selected] / n, sumsG[selected] / n, sumsB[selected] / n)
            // A dark tint of the album's OWN hue provides a readable controls
            // floor; RGB ratios remain intact and neutral artwork stays neutral.
            val brightest = maxOf(source.red, source.green, source.blue).coerceAtLeast(0.01f)
            val scale = 0.29f / brightest
            columns += Color(
                (source.red * scale).coerceIn(0f, 1f),
                (source.green * scale).coerceIn(0f, 1f),
                (source.blue * scale).coerceIn(0f, 1f),
            )
        }
    }
    return columns
}

/** Extra tone only BELOW the artwork seam; smooth endpoints prevent a band. */
internal fun capsuleImmersiveAccentEnvelope(pageY: Float, stage: Float): Float {
    val start = (stage + 0.12f).coerceAtMost(0.87f)
    val end = (start + 0.23f).coerceAtMost(1f)
    val t = ((pageY - start) / (end - start).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Full photographic continuation, baked once on a worker. No progressive blur strip. */
internal fun capsuleImmersiveImageBackdropBitmap(field: CapsuleColorField, stageFraction: Float): ImageBitmap {
    val stage = stageFraction.coerceIn(0.35f, 0.75f)
    val raw = FloatArray(PAGE_COLUMNS * PAGE_ROWS * 3)
    fun sample(x: Float, y: Float): Color {
        val px = (x * field.width - 0.5f).coerceIn(0f, field.width - 1f)
        val py = (y * field.height - 0.5f).coerceIn(0f, field.height - 1f)
        val x0 = px.toInt(); val y0 = py.toInt(); val fx = px - x0; val fy = py - y0
        return androidx.compose.ui.graphics.lerp(
            androidx.compose.ui.graphics.lerp(field.cell(x0, y0), field.cell(x0 + 1, y0), fx),
            androidx.compose.ui.graphics.lerp(field.cell(x0, y0 + 1), field.cell(x0 + 1, y0 + 1), fx), fy)
    }
    for (y in 0 until PAGE_ROWS) for (x in 0 until PAGE_COLUMNS) {
        val pageY = (y + 0.5f) / PAGE_ROWS; val pageX = (x + 0.5f) / PAGE_COLUMNS
        val sourceX = capsuleImmersiveBackdropSourceX(pageX, pageY, stage)
        val visibleY = capsuleImmersiveBackdropSourceY(pageX, pageY, stage)
        val color = sample(sourceX, visibleY / IMMERSIVE_VISIBLE_SAMPLE_END)
        val i = (y * PAGE_COLUMNS + x) * 3
        raw[i] = color.red; raw[i + 1] = color.green; raw[i + 2] = color.blue
    }
    val horizontal = FloatArray(raw.size)
    val blurred = FloatArray(raw.size)
    for (y in 0 until PAGE_ROWS) for (x in 0 until PAGE_COLUMNS) for (sx in 0 until PAGE_COLUMNS) {
        val weight = backdropHorizontalWeights[x * PAGE_COLUMNS + sx]
        for (c in 0..2) horizontal[(y * PAGE_COLUMNS + x) * 3 + c] += raw[(y * PAGE_COLUMNS + sx) * 3 + c] * weight
    }
    for (y in 0 until PAGE_ROWS) for (x in 0 until PAGE_COLUMNS) for (sy in 0 until PAGE_ROWS) {
        val weight = backdropVerticalWeights[y * PAGE_ROWS + sy]
        for (c in 0..2) blurred[(y * PAGE_COLUMNS + x) * 3 + c] += horizontal[(sy * PAGE_COLUMNS + x) * 3 + c] * weight
    }
    val accents = capsuleImmersiveArtworkAccentColumns(field)
    val pixels = IntArray(PAINT_WIDTH * PAINT_HEIGHT)
    for (y in 0 until PAINT_HEIGHT) {
        val pageY = (y + 0.5f) / PAINT_HEIGHT
        // ArchiveTune's continuous lower scrim; no second flat-colour floor.
        val scrim = when {
            pageY < 0.38f -> pageY / 0.38f * 0.03f
            pageY < 0.68f -> 0.03f + (pageY - 0.38f) / 0.30f * 0.11f
            else -> 0.14f + (pageY - 0.68f) / 0.32f * 0.26f
        }
        val envelope = capsuleImmersiveAccentEnvelope(pageY, stage)
        for (x in 0 until PAINT_WIDTH) {
            var pixel = -0x1000000
            val accentX = ((x + 0.5f) / PAINT_WIDTH * accents.size)
                .toInt().coerceIn(0, (accents.size - 1).coerceAtLeast(0))
            val accent = accents.getOrNull(accentX)
            for (c in 0..2) {
                var value = 0f
                for (dy in 0..3) for (dx in 0..3) {
                    val sx = backdropSplineX.indices[x * 4 + dx]
                    val sy = backdropSplineY.indices[y * 4 + dy]
                    value += blurred[(sy * PAGE_COLUMNS + sx) * 3 + c] *
                        backdropSplineX.weights[x * 4 + dx] * backdropSplineY.weights[y * 4 + dy]
                }
                // Only dark continuation regions receive a gentle cue from
                // the actual artwork's dominant hue. Coloured areas, split
                // colour layouts and the photo join stay unchanged.
                val lift = if (accent == null) 0f else
                    envelope * 0.72f * ((0.23f - value) / 0.23f).coerceIn(0f, 1f)
                val tint = if (accent == null) 0f else when (c) {
                    0 -> accent.red
                    1 -> accent.green
                    else -> accent.blue
                }
                value = value * (1f - lift) + tint * lift
                pixel = pixel or ((value * (1f - scrim) * 255f + 0.5f).toInt().coerceIn(0, 255) shl (16 - c * 8))
            }
            pixels[y * PAINT_WIDTH + x] = pixel
        }
    }
    return Bitmap.createBitmap(pixels, PAINT_WIDTH, PAINT_HEIGHT, Bitmap.Config.ARGB_8888).asImageBitmap()
}
