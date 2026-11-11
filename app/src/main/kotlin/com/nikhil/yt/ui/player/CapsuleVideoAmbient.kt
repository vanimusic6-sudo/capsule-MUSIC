/*
 * Capsule MUSIC — opt-in live video ambient colours.
 * No second Media3 decoder, network request, frame listener or continuous GPU
 * blur: the existing TextureView is sampled as a tiny bitmap only when visible.
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs

internal const val VIDEO_AMBIENT_CAPTURE_INTERVAL_MS = 1_550L
internal const val VIDEO_AMBIENT_SAMPLE_WIDTH = 48
internal const val VIDEO_AMBIENT_SAMPLE_HEIGHT = 28

internal data class CapsuleVideoAmbientFrame(
    // Original audio track ID (not a possibly different chosen official clip ID).
    val mediaId: String,
    val bands: List<Color>,
)

/** Only the mounted full player provides a sink; all other video surfaces are inert. */
internal val LocalCapsuleVideoAmbientEnabled = staticCompositionLocalOf { false }
internal val LocalCapsuleVideoAmbientSink =
    staticCompositionLocalOf<((CapsuleVideoAmbientFrame) -> Unit)?> { null }

/**
 * Pull colours out of an already rendered, 48×28 frame. Ignore wide letterbox
 * bars and fully black padding, without running Palette/ML, allocating another
 * decoder, or reading the original video file.
 */
internal fun capsuleVideoAmbientFrameFromBitmap(bitmap: Bitmap, mediaId: String): CapsuleVideoAmbientFrame? {
    if (bitmap.width < 7 || bitmap.height < 5 || mediaId.isBlank()) return null
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val bandCount = 7
    var nonBlack = 0
    val bands = List(bandCount) { band ->
        val xStart = band * width / bandCount
        val xEnd = ((band + 1) * width / bandCount).coerceAtMost(width)
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        // Sample the visible heart of the frame, not codec black bars.
        for (y in height / 6 until height * 5 / 6 step 2) {
            for (x in xStart until xEnd) {
                val argb = pixels[y * width + x]
                val rr = argb ushr 16 and 255
                val gg = argb ushr 8 and 255
                val bb = argb and 255
                if (rr + gg + bb < 31) continue
                r += rr
                g += gg
                b += bb
                n++
            }
        }
        nonBlack += n
        if (n == 0) Color(0xFF101014)
        else {
            // Limit brightness to keep white controls legible and avoid lighting
            // the entire page like a second bright video.
            val sampled = Color(r.toFloat() / n / 255f,
                g.toFloat() / n / 255f, b.toFloat() / n / 255f)
            lerp(sampled, Color(0xFF101014), 0.50f)
        }
    }
    // Keep the last valid colours during black cuts / unstable first frames.
    if (nonBlack < 4) return null
    return CapsuleVideoAmbientFrame(mediaId, bands)
}

/** Ignore tiny colour noise: refreshing the wallpaper costs more than it adds. */
internal fun capsuleVideoAmbientChanged(previous: List<Color>?, next: List<Color>): Boolean {
    if (previous == null || previous.size != next.size) return true
    val average = previous.indices.sumOf { index ->
        val a = previous[index]
        val b = next[index]
        (abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)).toDouble()
    } / (next.size * 3)
    return average > 0.018
}
