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
import androidx.compose.ui.graphics.lerp
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
    return capsuleImmersiveKeepTrueDarkBackground(
        capsuleImmersiveRejectSmallMarks(CapsuleColorField(COVER_COLUMNS, COVER_ROWS, cells)),
    )
}

/**
 * Correct the low-resolution ambient colour *extraction*, not the renderer.
 * Some sleeves have a broad solid black background and very bright foreground
 * subjects. Blurring that foreground into the lower screen invents a muddy
 * colour which was never in the background.
 *
 * Require both whole-image and border evidence before modifying the palette:
 * a fire-filled photograph with a dark coat must remain vividly coloured.
 * Full-resolution cover artwork is not touched. This runs only on decode.
 */
internal fun capsuleImmersiveKeepTrueDarkBackground(field: CapsuleColorField): CapsuleColorField {
    if (field.width < 2 || field.height < 2 || field.cells.isEmpty()) return field
    fun dark(c: Color) = maxOf(c.red, c.green, c.blue) < 0.12f
    val darkPixels = field.cells.filter(::dark)
    if (darkPixels.size < field.cells.size * 0.55f) return field
    val edge = field.cells.filterIndexed { index, _ ->
        val x = index % field.width
        val y = index / field.width
        x < (field.width / 7).coerceAtLeast(1) ||
            x >= field.width - (field.width / 7).coerceAtLeast(1) ||
            y >= field.height * 3 / 4
    }
    if (edge.isEmpty() || edge.count(::dark) < edge.size * 0.72f) return field
    val darkMedian = darkPixels.map { maxOf(it.red, it.green, it.blue) }
        .sorted()[darkPixels.size / 2].coerceIn(0f, 0.07f)
    val canvas = Color(darkMedian, darkMedian, darkMedian)
    return field.copy(cells = field.cells.mapIndexed { index, original ->
        val y = index / field.width
        // Keep the bright upper artwork details visible. Only the AMBIENT
        // field progressively adopts its genuine black background toward
        // the lower artwork, which the renderer reuses for the floor.
        // This alters colour extraction, never the existing warp/blur geometry.
        val fraction = y.toFloat() / (field.height - 1)
        val fade = ((fraction - 0.33f) / 0.23f).coerceIn(0f, 1f)
        val ease = fade * fade * (3f - 2f * fade)
        val remaining = 0.65f - 0.57f * ease
        androidx.compose.ui.graphics.lerp(canvas, original, remaining)
    })
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
    // Preserve the proven pale-label detector from the previous renderer.
    // Boundary pixels of white text on dark art are often < 0.55f after
    // sampling, and should not bloom into ambient highlights.
    val markThreshold = maxOf(0.35f, median + 0.04f)
    val marks = field.cells.map { c ->
        val high = maxOf(c.red, c.green, c.blue)
        high > markThreshold && high - minOf(c.red, c.green, c.blue) < 0.22f
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

/**
 * Population-based background selection, inspired by the palette principle of
 * Metrolist's PlayerColorExtractor (GPL-3.0), independently implemented here.
 * Require both broad coverage and agreement around the visible artwork perimeter.
 * A vivid face, logo or isolated stripe cannot recolour a neutral/solid sleeve.
 */
internal fun capsuleImmersiveDominantGround(field: CapsuleColorField): Color? {
    if (field.width < 2 || field.height < 2 || field.cells.isEmpty()) return null
    val buckets = IntArray(4096)
    val borderBuckets = IntArray(4096)
    val edge = BooleanArray(field.cells.size)
    var edgeCount = 0
    val rim = IntArray(field.cells.size)
    val rimTotals = IntArray(3)
    fun bucket(c: Color): Int =
        ((c.red * 15f + 0.5f).toInt().coerceIn(0, 15) shl 8) or
            ((c.green * 15f + 0.5f).toInt().coerceIn(0, 15) shl 4) or
            (c.blue * 15f + 0.5f).toInt().coerceIn(0, 15)
    for ((index, c) in field.cells.withIndex()) {
        val x = index % field.width
        val y = index / field.width
        val isEdge = x < (field.width / 6).coerceAtLeast(1) ||
            x >= field.width - (field.width / 6).coerceAtLeast(1) ||
            y >= field.height * 3 / 4 || y < field.height / 8
        // Narrow upper border zones catch a studio backdrop behind a large
        // central subject even when the subject covers most of the image.
        val zone = when {
            y < (field.height / 8).coerceAtLeast(1) -> 1
            y < field.height * 3 / 5 && x < (field.width / 12).coerceAtLeast(1) -> 2
            y < field.height * 3 / 5 && x >= field.width - (field.width / 12).coerceAtLeast(1) -> 3
            else -> 0
        }
        rim[index] = zone
        if (zone > 0) rimTotals[zone - 1]++
        val key = bucket(c)
        buckets[key]++
        if (isEdge) {
            edge[index] = true
            edgeCount++
            borderBuckets[key]++
        }
    }
    val candidates = buckets.indices.filter { borderBuckets[it] > 0 }
        .sortedByDescending { buckets[it] + borderBuckets[it] * 2 }.take(6)
    var bestScore = 0f
    var best: Color? = null
    for (key in candidates) {
        val r = (key shr 8 and 15) / 15f
        val g = (key shr 4 and 15) / 15f
        val b = (key and 15) / 15f
        var count = 0
        var border = 0
        val rimMatches = IntArray(3)
        var sumR = 0f; var sumG = 0f; var sumB = 0f
        for ((index, c) in field.cells.withIndex()) {
            val dr = c.red - r; val dg = c.green - g; val db = c.blue - b
            if (dr * dr + dg * dg + db * db > 0.0196f) continue
            count++
            if (edge[index]) border++
            if (rim[index] > 0) rimMatches[rim[index] - 1]++
            sumR += c.red; sumG += c.green; sumB += c.blue
        }
        val coverage = count.toFloat() / field.cells.size
        val edgeCoverage = border.toFloat() / edgeCount.coerceAtLeast(1)
        val broad = coverage >= 0.55f && edgeCoverage >= 0.70f
        val framed = coverage >= 0.20f && rimTotals.indices.all { i ->
            rimTotals[i] > 0 && rimMatches[i].toFloat() / rimTotals[i] >= 0.65f
        }
        if (!broad && !framed) continue
        val score = coverage + 2f * edgeCoverage + if (framed) 1f else 0f
        if (score > bestScore) {
            bestScore = score
            best = Color(sumR / count, sumG / count, sumB / count)
        }
    }
    return best
}

/** Broad visible-image colour, used only as a loading/base tone, never as a seam scanline. */
internal fun capsuleImmersiveVisibleMean(field: CapsuleColorField): Color {
    if (field.cells.isEmpty()) return IMMERSIVE_NEUTRAL_COLOR
    capsuleImmersiveDominantGround(field)?.let { return it }
    var r = 0f; var g = 0f; var b = 0f
    for (c in field.cells) { r += c.red; g += c.green; b += c.blue }
    return Color(r / field.cells.size, g / field.cells.size, b / field.cells.size)
}

/**
 * Actual photo blur, not a colour-field approximation. The original decoded
 * artwork is centre-cropped into a small texture once on the worker. Three
 * separable box passes approximate a Gaussian without a live GPU blur.
 * Metrolist's progressive artwork blur is the visual reference; this is an
 * independent CPU implementation with no reflection or new dependencies.
 */
internal fun Bitmap.capsuleImmersivePhotoBlur(aspect: Float, landscape: Boolean): ImageBitmap {
    val target = aspect.coerceIn(0.55f, 2.2f)
    val natural = width.toFloat() / height.coerceAtLeast(1)
    val zoom = if (landscape) 1.06f else 1f
    val cropWidth = (if (natural > target) height * target else width.toFloat()) / zoom
    val cropHeight = (if (natural < target) width / target else height.toFloat()) / zoom
    val w = 192
    val h = (w / target).toInt().coerceIn(88, 350)
    val left = ((width - cropWidth) * 0.5f).toInt().coerceIn(0, width - 1)
    val top = ((height - cropHeight) * 0.5f).toInt().coerceIn(0, height - 1)
    val right = (left + cropWidth.toInt()).coerceIn(left + 1, width)
    val bottom = (top + cropHeight.toInt()).coerceIn(top + 1, height)
    val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    scaled.eraseColor(android.graphics.Color.BLACK)
    android.graphics.Canvas(scaled).drawBitmap(
        this, android.graphics.Rect(left, top, right, bottom),
        android.graphics.Rect(0, 0, w, h),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
    )
    val original = IntArray(w * h)
    try { scaled.getPixels(original, 0, w, 0, 0, w, h) }
    finally { scaled.recycle() }

    // Sliding-window convolution; edge clamping, not mirrored pixels.
    fun pass(source: IntArray, horizontal: Boolean, radius: Int): IntArray {
        val output = IntArray(source.size)
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val stride = if (horizontal) 1 else w
        val divisor = radius * 2 + 1
        for (line in 0 until lines) {
            val start = if (horizontal) line * w else line
            fun pixel(pos: Int) = source[start + pos.coerceIn(0, length - 1) * stride]
            var r = 0; var g = 0; var b = 0
            for (i in -radius..radius) {
                val p = pixel(i)
                r += p ushr 16 and 255; g += p ushr 8 and 255; b += p and 255
            }
            for (i in 0 until length) {
                output[start + i * stride] = 0xFF000000.toInt() or
                    ((r / divisor) shl 16) or ((g / divisor) shl 8) or (b / divisor)
                val remove = pixel(i - radius)
                val add = pixel(i + radius + 1)
                r += (add ushr 16 and 255) - (remove ushr 16 and 255)
                g += (add ushr 8 and 255) - (remove ushr 8 and 255)
                b += (add and 255) - (remove and 255)
            }
        }
        return output
    }
    fun blur(input: IntArray, radius: Int): IntArray {
        var data = input
        repeat(3) { data = pass(pass(data, true, radius), false, radius) }
        return data
    }
    val soft = blur(original, 4)
    val diffuse = blur(soft, 13)
    val result = IntArray(original.size)
    for (y in 0 until h) {
        val p = ((y.toFloat() / (h - 1) - 0.42f) / 0.46f).coerceIn(0f, 1f)
        val mix = p * p * (3f - 2f * p)
        for (x in 0 until w) {
            val i = y * w + x
            fun channel(shift: Int): Int {
                val a = soft[i] ushr shift and 255
                val b = diffuse[i] ushr shift and 255
                return (a + (b - a) * mix + 0.5f).toInt().coerceIn(0, 255)
            }
            result[i] = 0xFF000000.toInt() or (channel(16) shl 16) or
                (channel(8) shl 8) or channel(0)
        }
    }
    return Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Single C2-continuous fade envelope: no intermediate opacity shelves. */
private fun immersiveSmootherStep(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * t * (t * (t * 6f - 15f) + 10f)
}

internal fun immersivePhotoFeatherAlpha(fraction: Float): Float {
    if (!fraction.isFinite()) return 0f
    return 1f - immersiveSmootherStep(0.34f, 0.88f, fraction)
}

/** Total contribution of the sharp and blurred artwork over the colour field. */
internal fun immersivePhotoCombinedAlpha(fraction: Float): Float {
    if (!fraction.isFinite()) return 0f
    return 1f - immersiveSmootherStep(0.44f, 1f, fraction)
}

/**
 * Derive the underlay mask from the desired combined opacity, rather than
 * independently fading two overlapping images. Source-over compositing is
 * sharp + (1 - sharp) * blurred; using that equation avoids a visible shelf
 * when the sharp image disappears and the blurred image starts fading.
 */
internal fun immersiveBlurredPhotoAlpha(fraction: Float): Float {
    if (!fraction.isFinite() || fraction <= 0.34f || fraction >= 1f) return 0f
    val sharp = immersivePhotoFeatherAlpha(fraction)
    val total = immersivePhotoCombinedAlpha(fraction)
    val remainder = 1f - sharp
    if (remainder <= 0.000001f) return 1f
    return ((total - sharp) / remainder).coerceIn(0f, 1f)
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
private val backdropVerticalWeights = gaussianWeights(PAGE_ROWS, PAGE_ROWS * 0.12f)

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
 * Extend the lower colour field without reflecting the photograph.
 * Sample a narrow band of the actual edge with a bounded spatial variation;
 * never sweep back through the face or other upper artwork content.
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
    val spread = 0.065f * envelope * (0.55f + 0.45f * cos(
        2f * PI.toFloat() * (pageX * 0.93f + pageY * 0.43f)
    ))
    return (IMMERSIVE_VISIBLE_SAMPLE_END - spread)
        .coerceIn(0f, IMMERSIVE_VISIBLE_SAMPLE_END)
}

/** Full photographic continuation, baked once on a worker. No progressive blur strip. */
internal fun capsuleImmersiveImageBackdropBitmap(field: CapsuleColorField, stageFraction: Float): ImageBitmap {
    val stage = stageFraction.coerceIn(0.35f, 0.75f)
    val raw = FloatArray(PAGE_COLUMNS * PAGE_ROWS * 3)
    val ground = capsuleImmersiveDominantGround(field)
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
        val edgeColor = sample(sourceX, visibleY / IMMERSIVE_VISIBLE_SAMPLE_END)
        // Let the real surrounding hue emerge under the photo fade, not
        // only after the controls begin. Keep ambiguous/multicolour images intact.
        val onset = stage * 0.66f
        val t = ((pageY - onset) / (1f - onset)).coerceIn(0f, 1f)
        val mix = t * t * (3f - 2f * t)
        val color = if (ground != null) lerp(edgeColor, ground, mix * 0.94f) else edgeColor
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
    val pixels = IntArray(PAINT_WIDTH * PAINT_HEIGHT)
    for (y in 0 until PAINT_HEIGHT) {
        val pageY = (y + 0.5f) / PAINT_HEIGHT
        // Preserve the old darkness at the top, middle and bottom, but
        // eliminate changes of slope at hard-coded horizontal boundaries.
        val scrim = 0.40f * Math.pow(pageY.toDouble(), 2.7).toFloat()
        for (x in 0 until PAINT_WIDTH) {
            var pixel = -0x1000000
            for (c in 0..2) {
                var value = 0f
                for (dy in 0..3) for (dx in 0..3) {
                    val sx = backdropSplineX.indices[x * 4 + dx]
                    val sy = backdropSplineY.indices[y * 4 + dy]
                    value += blurred[(sy * PAGE_COLUMNS + sx) * 3 + c] *
                        backdropSplineX.weights[x * 4 + dx] * backdropSplineY.weights[y * 4 + dy]
                }
                pixel = pixel or ((value * (1f - scrim) * 255f + 0.5f).toInt().coerceIn(0, 255) shl (16 - c * 8))
            }
            pixels[y * PAINT_WIDTH + x] = pixel
        }
    }
    return Bitmap.createBitmap(pixels, PAINT_WIDTH, PAINT_HEIGHT, Bitmap.Config.ARGB_8888).asImageBitmap()
}
