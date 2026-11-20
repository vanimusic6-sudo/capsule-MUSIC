/*
 * Capsule MUSIC — ambient video diffusion, not circular lamp sprites.
 * Stable, source-coloured light across the entire canvas. A tiny blurred
 * field is computed only when a sampled video frame actually changes.
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs

private const val VIDEO_DIFFUSE_WIDTH = 112
private const val VIDEO_DIFFUSE_HEIGHT = 176

/**
 * A low-frequency two-dimensional video colour field.
 *
 * The old design placed 12 point lights under the player. Their radial
 * inverse-square falloff formed bright circular halos, even when the real
 * video contained a uniform sky or a straight horizon. This version diffuses
 * existing scene pixels (both axes), not synthetic lamps.
 *
 * A dark floor and broad, directional projection retain the colour of
 * left/right objects without circles, rings, vertical hard strips or glare.
 */
internal fun capsuleVideoSpatialBitmap(
    field: CapsuleColorField,
    fallbackFloor: Color,
    alreadyDiffused: Boolean = false,
): ImageBitmap {
    val sw = field.width
    val sh = field.height
    val smoothed = FloatArray(sw * sh * 3)

    // Blur every source pixel with a fixed 9x7 triangular kernel. This is
    // only ~63 * 320 operations per accepted readback, never per display
    // frame. Wide spatial support suppresses hard vertical colour bars.
    if (alreadyDiffused) {
        field.cells.forEachIndexed { i, color ->
            smoothed[i * 3] = color.red; smoothed[i * 3 + 1] = color.green; smoothed[i * 3 + 2] = color.blue
        }
    } else for (cy in 0 until sh) for (cx in 0 until sw) {
        var rr = 0f
        var gg = 0f
        var bb = 0f
        var sum = 0f
        for (dy in -3..3) for (dx in -4..4) {
            val weight = (4 - abs(dy)) * (5 - abs(dx)).toFloat()
            val color = field.cell(cx + dx, cy + dy)
            rr += color.red * weight
            gg += color.green * weight
            bb += color.blue * weight
            sum += weight
        }
        val i = (cy * sw + cx) * 3
        smoothed[i] = rr / sum
        smoothed[i + 1] = gg / sum
        smoothed[i + 2] = bb / sum
    }

    val w = VIDEO_DIFFUSE_WIDTH
    val h = VIDEO_DIFFUSE_HEIGHT
    val pixels = IntArray(w * h)
    val baseR = 0.042f + fallbackFloor.red * 0.12f
    val baseG = 0.045f + fallbackFloor.green * 0.12f
    val baseB = 0.050f + fallbackFloor.blue * 0.12f
    fun byte(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    fun sample(sx: Float, sy: Float, channel: Int): Float {
        val x0 = sx.toInt().coerceIn(0, sw - 1)
        val y0 = sy.toInt().coerceIn(0, sh - 1)
        val x1 = (x0 + 1).coerceAtMost(sw - 1)
        val y1 = (y0 + 1).coerceAtMost(sh - 1)
        val fx = sx - x0
        val fy = sy - y0
        val top = smoothed[(y0 * sw + x0) * 3 + channel] * (1f - fx) +
            smoothed[(y0 * sw + x1) * 3 + channel] * fx
        val bottom = smoothed[(y1 * sw + x0) * 3 + channel] * (1f - fx) +
            smoothed[(y1 * sw + x1) * 3 + channel] * fx
        return top * (1f - fy) + bottom * fy
    }

    for (py in 0 until h) {
        val y = (py + 0.5f) / h
        // Continuous, non-circular gradient: even a still frame with one
        // bright centre cannot create a spotlight beneath the VIDEO window.
        val settle = y * y * (3f - 2f * y)
        val alpha = 0.48f * (1f - settle) + 0.12f
        val sy = (0.16f + y * 0.64f) * (sh - 1)
        for (px in 0 until w) {
            val x = (px + 0.5f) / w
            val sx = x * (sw - 1)
            val r = baseR * (1f - alpha) + sample(sx, sy, 0) * alpha
            val g = baseG * (1f - alpha) + sample(sx, sy, 1) * alpha
            val b = baseB * (1f - alpha) + sample(sx, sy, 2) * alpha
            pixels[py * w + px] = (0xFF shl 24) or
                (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        .apply { setPixels(pixels, 0, w, 0, 0, w, h) }
        .asImageBitmap()
}
