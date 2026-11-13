/*
 * Capsule MUSIC — spatial light cast by the visible video, not a stretched
 * copy of its pixels. A few local regions illuminate nearby wallpaper.
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

// The light field has only 12 broad emitters. 112x176 is ample for smooth
// bilinear upsampling, saving 37% of per-readback raster work and texture bytes.
private const val VIDEO_LIGHT_WIDTH = 112
private const val VIDEO_LIGHT_HEIGHT = 176

private data class VideoLight(
    val x: Float, val y: Float,
    val red: Float, val green: Float, val blue: Float,
    val energy: Float,
)

/**
 * Preserve the red object on the LEFT and blue sky on the RIGHT. No full-page
 * colour averaging, no copying a 20x16 grid to the entire page, no hard blocks.
 *
 * 4x3 spatial patches become soft finite light sources anchored to the actual
 * video window. The bottom controls sit on a naturally dark, stable floor.
 */
internal fun capsuleVideoSpatialBitmap(
    field: CapsuleColorField,
    fallbackFloor: Color,
): ImageBitmap {
    val lights = ArrayList<VideoLight>(12)
    for (gy in 0 until 3) for (gx in 0 until 4) {
        val x0 = gx * field.width / 4
        val x1 = ((gx + 1) * field.width / 4).coerceAtLeast(x0 + 1)
        val y0 = gy * field.height / 3
        val y1 = ((gy + 1) * field.height / 3).coerceAtLeast(y0 + 1)
        var red = 0f; var green = 0f; var blue = 0f
        var count = 0
        for (cy in y0 until y1) for (cx in x0 until x1) {
            val c = field.cell(cx, cy)
            red += c.red; green += c.green; blue += c.blue
            count++
        }
        val r = red / count; val g = green / count; val b = blue / count
        val peak = maxOf(r, g, b)
        // Dark objects are not light emitters. Never turn letterboxing into
        // a grey block or add fake saturation to neutral cinematography.
        val energy = ((peak - 0.065f) / 0.65f).coerceIn(0f, 1f)
        lights.add(
            VideoLight(
                x = (gx + 0.5f) / 4f,
                y = 0.16f + (gy + 0.5f) / 3f * 0.36f,
                red = r, green = g, blue = b, energy = energy,
            ),
        )
    }

    val w = VIDEO_LIGHT_WIDTH
    val h = VIDEO_LIGHT_HEIGHT
    val pixels = IntArray(w * h)
    fun byte(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    // A dark stable paper underneath the video. Derived colour lives only in
    // its soft local illuminations, not in an always-on fullscreen tint.
    val groundR = 0.045f + fallbackFloor.red * 0.18f
    val groundG = 0.046f + fallbackFloor.green * 0.18f
    val groundB = 0.052f + fallbackFloor.blue * 0.18f

    for (py in 0 until h) {
        val y = (py + 0.5f) / h
        val floorFade = (1f - (y - 0.54f).coerceAtLeast(0f) * 1.55f).coerceIn(0.30f, 1f)
        for (px in 0 until w) {
            val x = (px + 0.5f) / w
            var wr = 0f; var wg = 0f; var wb = 0f; var sum = 0f
            for (light in lights) {
                if (light.energy < 0.015f) continue
                val dx = (x - light.x) / 0.31f
                val dy = (y - light.y) / 0.31f
                // Finite, smooth falloff without allocating blur buffers or
                // computing expensive exponentials for every video sample.
                val spread = 1f / (1f + dx * dx + dy * dy)
                val weight = spread * spread * light.energy
                wr += light.red * weight
                wg += light.green * weight
                wb += light.blue * weight
                sum += weight
            }
            // Local colour stays where the subject is. No arbitrary colour
            // synthesis: the RGB ratios come only from the actual video.
            val glow = (sum / 2.25f).coerceIn(0f, 0.93f) * floorFade
            val r = groundR + if (sum > 0f) wr / sum * glow else 0f
            val g = groundG + if (sum > 0f) wg / sum * glow else 0f
            val b = groundB + if (sum > 0f) wb / sum * glow else 0f
            pixels[py * w + px] = (0xFF shl 24) or
                (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        .apply { setPixels(pixels, 0, w, 0, 0, w, h) }
        .asImageBitmap()
}
