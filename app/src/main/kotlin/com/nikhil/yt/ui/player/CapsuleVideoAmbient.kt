/*
 * Capsule MUSIC — opt-in live video ambient colours.
 * The existing decoder signals new frames. One tiny reused readback buffer
 * supplies spatial colour motion only while the full player is visible.
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs

internal const val VIDEO_AMBIENT_CAPTURE_INTERVAL_MS = 100L
internal const val VIDEO_AMBIENT_SAMPLE_WIDTH = 48
internal const val VIDEO_AMBIENT_SAMPLE_HEIGHT = 28

internal data class CapsuleVideoAmbientFrame(
    // Original audio track ID (not a possibly different chosen official clip ID).
    val mediaId: String,
    val bands: List<Color>,
    val field: CapsuleColorField,
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
internal fun capsuleVideoAmbientFrameFromBitmap(
    bitmap: Bitmap, mediaId: String,
    lightFilter: CapsuleVideoLightFilter = CapsuleVideoLightFilter(),
    nowNs: Long = System.nanoTime(),
): CapsuleVideoAmbientFrame? {
    if (bitmap.width < 7 || bitmap.height < 5 || mediaId.isBlank()) return null
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    // Keep the existing scene through black decoder cuts/letterboxing.
    if (pixels.count { ((it ushr 16 and 255) + (it ushr 8 and 255) + (it and 255)) >= 31 } < 4) return null
    val raw = bitmap.capsuleColorField(bitmap.width.toFloat() / bitmap.height,
        video = true, pixelBuffer = pixels)
    val light = lightFilter.apply(raw, nowNs)
    return CapsuleVideoAmbientFrame(mediaId, capsuleBandsFromField(light), light)
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
