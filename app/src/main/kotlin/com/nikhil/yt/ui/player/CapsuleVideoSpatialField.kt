/*
 * Capsule MUSIC — diffuse 2D ambience extracted from the *visible* video frame.
 *
 * Build one small RGB texture per accepted readback on Dispatchers.Default.
 * No per-display-frame bitmap allocation, full-resolution blur or video decoder.
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs

private const val VIDEO_FIELD_TEXTURE_WIDTH = 176
private const val VIDEO_FIELD_TEXTURE_HEIGHT = 256

/**
 * Unlike an edge-only album-cover continuation, a video lights up the player
 * in both axes. This keeps a red object on the right and a blue light at the
 * top in their own positions instead of painting one solid average.
 */
internal fun capsuleVideoSpatialBitmap(
    field: CapsuleColorField,
    fallbackFloor: Color,
): ImageBitmap {
    val sw = field.width
    val sh = field.height
    // Smooth the *source* cells before upsampling. 5x5 separable triangular
    // support prevents the visible 20x16 grid from turning into square patches.
    val smooth = FloatArray(sw * sh * 3)
    for (y in 0 until sh) for (x in 0 until sw) {
        var red = 0f
        var green = 0f
        var blue = 0f
        var total = 0f
        for (dy in -2..2) for (dx in -2..2) {
            val weight = ((3 - abs(dx)) * (3 - abs(dy))).toFloat()
            val color = field.cell(x + dx, y + dy)
            red += color.red * weight
            green += color.green * weight
            blue += color.blue * weight
            total += weight
        }
        val i = (y * sw + x) * 3
        smooth[i] = red / total
        smooth[i + 1] = green / total
        smooth[i + 2] = blue / total
    }

    val pixels = IntArray(VIDEO_FIELD_TEXTURE_WIDTH * VIDEO_FIELD_TEXTURE_HEIGHT)
    fun byte(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val w = VIDEO_FIELD_TEXTURE_WIDTH
    val h = VIDEO_FIELD_TEXTURE_HEIGHT
    for (y in 0 until h) {
        val sy = (y + 0.5f) / h * (sh - 1)
        val y0 = sy.toInt().coerceIn(0, sh - 1)
        val y1 = (y0 + 1).coerceAtMost(sh - 1)
        val fy = sy - y0
        val ny = (y + 0.5f) / h
        for (x in 0 until w) {
            val sx = (x + 0.5f) / w * (sw - 1)
            val x0 = sx.toInt().coerceIn(0, sw - 1)
            val x1 = (x0 + 1).coerceAtMost(sw - 1)
            val fx = sx - x0
            val a = (y0 * sw + x0) * 3
            val b = (y0 * sw + x1) * 3
            val c = (y1 * sw + x0) * 3
            val d = (y1 * sw + x1) * 3
            // A gentle edge and lower-control vignette, not a single colour
            // wash. Each RGB channel interpolates independently in 2D.
            val nx = (x + 0.5f) / w
            val edge = abs(nx - 0.5f) * 2f
            val shade = (0.075f + edge * edge * 0.10f + ny * ny * 0.21f).coerceIn(0f, 0.43f)
            fun sampled(channel: Int, floor: Float): Int {
                val top = smooth[a + channel] * (1f - fx) + smooth[b + channel] * fx
                val bottom = smooth[c + channel] * (1f - fx) + smooth[d + channel] * fx
                return byte((top * (1f - fy) + bottom * fy) * (1f - shade) + floor * shade)
            }
            val red = sampled(0, fallbackFloor.red)
            val green = sampled(1, fallbackFloor.green)
            val blue = sampled(2, fallbackFloor.blue)
            pixels[y * w + x] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        .apply { setPixels(pixels, 0, w, 0, 0, w, h) }
        .asImageBitmap()
}
