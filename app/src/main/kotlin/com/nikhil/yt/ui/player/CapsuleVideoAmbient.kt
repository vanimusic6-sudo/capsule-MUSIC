/*
 * Capsule MUSIC — opt-in live video ambient colours.
 * The existing decoder signals new frames. One tiny reused readback buffer
 * supplies spatial colour motion only while the full player is visible.
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import com.nikhil.yt.playback.video.CapsulePlaybackMode
import com.nikhil.yt.playback.video.CapsuleVideoPlaybackState
import com.nikhil.yt.playback.video.CapsuleVideoPhase
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs

// Live, decoder-frame-driven ambient sampling. The Nov 13 colour renderer
// reacts in 180 ms; a fixed 700 ms capture interval made scenes appear stale.
// ~4.5 samples/s when video colours change, slowing to ~1.5/s on static shots.
// This limits synchronous TextureView GPU readbacks without changing video FPS.
internal const val VIDEO_AMBIENT_CAPTURE_INTERVAL_MS = 220L
internal fun capsuleVideoAmbientCaptureIntervalMs(unchangedSamples: Int): Long = when {
    unchangedSamples < 3 -> VIDEO_AMBIENT_CAPTURE_INTERVAL_MS
    unchangedSamples < 8 -> 420L
    else -> 660L
}

/** Preserve the last VIDEO palette during buffer/surface interruptions. */
internal fun capsuleVideoAmbientShouldStayVisible(
    state: CapsuleVideoPlaybackState, mediaId: String?, enabled: Boolean,
    everPresentedFrame: Boolean = false,
): Boolean = enabled && mediaId != null && state.mediaId == mediaId &&
    // A restored/retained palette is NOT a reason to turn the page into VIDEO
    // before the first real frame is on screen. Once it appeared, keep its
    // light across transient rebuffer and decoder surface interruptions.
    (state.firstFrameReady || everPresentedFrame) &&
    state.preferredMode == CapsulePlaybackMode.VIDEO &&
    state.mode == CapsulePlaybackMode.VIDEO &&
    state.phase in setOf(
        CapsuleVideoPhase.PREPARING, CapsuleVideoPhase.PLAYING,
        CapsuleVideoPhase.BUFFERING, CapsuleVideoPhase.RECOVERING,
    )
internal const val VIDEO_AMBIENT_SAMPLE_WIDTH = 48
internal const val VIDEO_AMBIENT_SAMPLE_HEIGHT = 28

/**
 * Keep the last video colour field across transient decoder events.
 * The previous 0.018 noise gate suppressed gradual colour shifts even when
 * frames were available. Restore the original spatial renderer's finer gate;
 * static frames still use an adaptive lower sampling frequency.
 */
internal class CapsuleVideoAmbientCaptureMemory {
    var previous: CapsuleColorField? = null

    fun shouldPublish(frame: CapsuleVideoAmbientFrame): Boolean {
        if (!capsuleFieldChanged(previous, frame.field, threshold = 0.0045f)) return false
        previous = frame.field
        return true
    }
}

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
    // The image's Y structure matters just as much as left/right colours.
    // This small 12x10 grid is our REAL input, not seven painted stripes.
    val field = bitmap.capsuleColorField(width.toFloat() / height, video = true, pixelBuffer = pixels)
    return CapsuleVideoAmbientFrame(mediaId, bands, field)
}

/** Smooth the captured video field like Scene Light's cover-derived field.
 * Execute on the capture worker; only immutable results reach Compose. */
internal fun capsuleSceneLightVideoFrame(
    frame: CapsuleVideoAmbientFrame,
    filter: CapsuleVideoLightFilter,
    nowNs: Long,
): CapsuleVideoAmbientFrame {
    val diffuse = filter.apply(frame.field, nowNs)
    return frame.copy(field = diffuse, bands = capsuleBandsFromField(diffuse))
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
