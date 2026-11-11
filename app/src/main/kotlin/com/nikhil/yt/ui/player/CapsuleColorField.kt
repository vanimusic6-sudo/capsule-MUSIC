/*
 * Capsule MUSIC — low-cost, genuinely TWO-DIMENSIONAL colour extraction.
 *
 * The old seven vertical bands could never become a natural 2D ambience,
 * no matter how large their horizontal Gaussian radius was.
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs

internal const val CAPSULE_FIELD_COLUMNS = 12
internal const val CAPSULE_FIELD_ROWS = 10

/**
 * A tiny immutable spatial colour grid, in row-major order. Equality is by
 * visible cell colour rather than bitmap identity, so Compose skips unchanged
 * videos. No full-size frame is retained after sampling.
 */
internal data class CapsuleColorField(
    val width: Int,
    val height: Int,
    val cells: List<Color>,
) {
    init { require(width > 0 && height > 0 && cells.size == width * height) }

    fun cell(x: Int, y: Int): Color =
        cells[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
}

/** Samples the same centre crop actually displayed by ContentScale.Crop. */
internal fun Bitmap.capsuleColorField(
    artworkAspect: Float,
    video: Boolean = false,
): CapsuleColorField {
    val ratio = artworkAspect.coerceIn(0.55f, 2.2f)
    val natural = width.toFloat() / height.coerceAtLeast(1)
    val croppedWidth = (if (natural > ratio) height * ratio else width.toFloat())
        .toInt().coerceIn(1, width)
    val croppedHeight = (if (natural < ratio) width / ratio else height.toFloat())
        .toInt().coerceIn(1, height)
    val left = (width - croppedWidth) / 2
    val top = (height - croppedHeight) / 2
    val cells = List(CAPSULE_FIELD_ROWS * CAPSULE_FIELD_COLUMNS) { index ->
        val cx = index % CAPSULE_FIELD_COLUMNS
        val cy = index / CAPSULE_FIELD_COLUMNS
        // Each cell is area averaged (3x3) to reject tiny lettering, edges
        // and subtitle noise. Sample inside the cell to avoid crop boundaries.
        var r = 0f
        var g = 0f
        var b = 0f
        var n = 0
        for (sy in 0..2) for (sx in 0..2) {
            val fx = (cx + (sx + 0.5f) / 3f) / CAPSULE_FIELD_COLUMNS
            val fy = (cy + (sy + 0.5f) / 3f) / CAPSULE_FIELD_ROWS
            val px = (left + (fx * croppedWidth).toInt()).coerceIn(0, width - 1)
            val py = (top + (fy * croppedHeight).toInt()).coerceIn(0, height - 1)
            val argb = getPixel(px, py)
            if ((argb ushr 24 and 255) < 128) continue
            val rr = (argb ushr 16 and 255) / 255f
            val gg = (argb ushr 8 and 255) / 255f
            val bb = (argb and 255) / 255f
            // Avoid creating a false black stripe from letterboxing.
            if (video && rr + gg + bb < 0.08f) continue
            r += rr; g += gg; b += bb; n++
        }
        val color = if (n > 0) Color(r / n, g / n, b / n)
            else Color(0xFF18171C)
        // Keep the atmosphere darker than the actual video/cover, but do NOT
        // crush its local chroma: different scene regions must stay distinct.
        if (video) lerp(color, Color(0xFF0C0D13), 0.48f)
        else if (color.isNearWhiteImmersiveBackground()) color
        else color.comfortableImmersiveColor()
    }
    return CapsuleColorField(CAPSULE_FIELD_COLUMNS, CAPSULE_FIELD_ROWS, cells)
}

internal fun capsuleFieldAt(
    from: CapsuleColorField?,
    to: CapsuleColorField?,
    fraction: Float,
): CapsuleColorField? {
    if (to == null) return from
    if (from == null || from.width != to.width || from.height != to.height) return to
    val t = fraction.coerceIn(0f, 1f)
    return if (t <= 0f) from else if (t >= 1f) to else to.copy(
        cells = from.cells.indices.map { lerp(from.cells[it], to.cells[it], t) },
    )
}

/** Ignore tiny video codec noise rather than waking GPU for every readback. */
internal fun capsuleFieldChanged(
    from: CapsuleColorField?,
    to: CapsuleColorField,
): Boolean {
    if (from == null || from.width != to.width || from.height != to.height) return true
    var delta = 0f
    for (i in from.cells.indices) {
        val a = from.cells[i]
        val b = to.cells[i]
        delta += abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
    }
    return delta / (from.cells.size * 3f) > 0.018f
}
