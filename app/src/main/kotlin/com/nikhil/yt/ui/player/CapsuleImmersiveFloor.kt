/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.get
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.nikhil.yt.innertube.toHighResThumbnail
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.ui.motion.CapsuleStandardEasing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small enough that averaging it costs nothing, large enough that one row is not one object. */
private const val EDGE_SAMPLE_SIZE = 48

/** How much of the cover's foot is averaged. */
private const val EDGE_SAMPLE_ROWS = 6

/** Neutral first frame; never show a random theme accent while artwork is loading. */
internal val IMMERSIVE_NEUTRAL_COLOR = Color(0xFF262626)

/** As long as the artwork crossfade, so the dissolve never flashes between tones. */
private const val EDGE_TRANSITION_MS = 1_400

internal data class ImmersiveArtworkTone(val edge: Color, val landscape: Boolean = false)

private val edgeColorCache = object : LinkedHashMap<String, ImmersiveArtworkTone>(24, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImmersiveArtworkTone>?): Boolean =
        size > 64
}

/**
 * The colour along the very bottom of the cover.
 *
 * The first version of this screen took its floor from the artwork palette, which reports the
 * colours an image is *about* — the vibrant ones. On a painting of a red robe on a pale grey
 * floor that is the robe, so the page went maroon underneath a cover that ended in grey, and the
 * join the whole design rests on became the most visible line on the screen.
 *
 * What the gradient meets is not the picture's subject but its last few rows of pixels, so that
 * is what is measured: a short strip averaged off the foot of a thumbnail small enough that doing
 * so costs nothing. Once per track, off the main thread, and remembered — no clock, no polling.
 *
 * [fallback] is only ever seen before the first measurement of the session has landed, and the
 * crossfade runs from it, so even that one is a fade rather than a jump.
 */
@Composable
internal fun rememberImmersiveEdgeColor(mediaMetadata: MediaMetadata?): ImmersiveArtworkTone {
    val context = LocalContext.current
    val thumbnailUrl = mediaMetadata?.thumbnailUrl
    val cacheKey = mediaMetadata?.id?.let { "$it|$thumbnailUrl" }

    /*
     * Deliberately not keyed on the track.
     *
     * Keyed, it emptied on every change, the caller fell back to the artwork palette for the few
     * hundred milliseconds before the new measurement landed, and the page flashed whatever
     * vivid colour that track happened to be about. Holding the previous track's floor instead
     * means the page only ever moves from one measured colour to the next, and the crossfade
     * below carries it. The palette is a fallback for the very first track and nothing else.
     */
    var measured by remember { mutableStateOf<ImmersiveArtworkTone?>(null) }

    /** The track the held colour belongs to, so a stale one is never kept once its own arrives. */
    var measuredFor by remember { mutableStateOf<String?>(null) }

    val cached = cacheKey?.let { synchronized(edgeColorCache) { edgeColorCache[it] } }
    if (cached != null && measuredFor != cacheKey) {
        measured = cached
        measuredFor = cacheKey
    }

    LaunchedEffect(cacheKey, thumbnailUrl) {
        if (cacheKey == null || thumbnailUrl.isNullOrBlank()) {
            measured = ImmersiveArtworkTone(IMMERSIVE_NEUTRAL_COLOR)
            measuredFor = cacheKey
            return@LaunchedEffect
        }
        if (measuredFor == cacheKey) return@LaunchedEffect
        val request =
            ImageRequest.Builder(context)
                .data(thumbnailUrl.toHighResThumbnail())
                .size(EDGE_SAMPLE_SIZE, EDGE_SAMPLE_SIZE)
                .allowHardware(false)
                .build()
        val sample =
            try {
                val bitmap =
                    withContext(Dispatchers.IO) { context.imageLoader.execute(request) }
                        .image
                        ?.toBitmap()
                        ?: return@LaunchedEffect
                withContext(Dispatchers.Default) {
                    val landscape = bitmap.width > bitmap.height * 1.20f
                    // Video thumbnails can contain a built-in black letterbox. That black strip
                    // is not the artwork's colour: extract from the image body instead.
                    val bottom = bitmap.averageBottomStrip()
                    val middle = bitmap.averageMiddleStrip()
                    val sampled =
                        if ((landscape || bottom.isNearlyBlack()) && bottom.isNearlyBlack() && !middle.isNearlyBlack()) {
                            middle
                        } else {
                            bottom
                        }
                    ImmersiveArtworkTone(sampled.comfortableImmersiveColor(), landscape)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: ImmersiveArtworkTone(IMMERSIVE_NEUTRAL_COLOR)

        synchronized(edgeColorCache) { edgeColorCache[cacheKey] = sample }
        measured = sample
        measuredFor = cacheKey
    }

    val target = measured ?: ImmersiveArtworkTone(IMMERSIVE_NEUTRAL_COLOR)
    val animated by
        animateColorAsState(
            targetValue = target.edge,
            animationSpec = tween(durationMillis = EDGE_TRANSITION_MS, easing = CapsuleStandardEasing),
            label = "immersiveEdge",
        )
    return target.copy(edge = animated)
}

/**
 * The mean colour of the bottom rows, or null if there is nothing to measure.
 *
 * Averaged in linear terms would be more correct and is not worth it here: the strip is a few
 * hundred pixels of what is usually one flat area, and the answer only has to match the pixels
 * directly above it closely enough that no edge is visible.
 */
private fun android.graphics.Bitmap.averageBottomStrip(): Color? {
    val height = height
    val width = width
    if (width <= 0 || height <= 0) return null

    val rows = EDGE_SAMPLE_ROWS.coerceAtMost(height)
    val firstRow = (height - rows).coerceAtLeast(0)
    var red = 0L
    var green = 0L
    var blue = 0L
    var counted = 0L

    for (y in firstRow until height) {
        for (x in 0 until width) {
            val pixel = this[x, y]
            // Fully transparent pixels say nothing about what the edge looks like.
            if ((pixel ushr 24 and 0xFF) < 8) continue
            red += pixel shr 16 and 0xFF
            green += pixel shr 8 and 0xFF
            blue += pixel and 0xFF
            counted += 1
        }
    }

    if (counted == 0L) return null
    return Color(
        red = (red / counted).toInt(),
        green = (green / counted).toInt(),
        blue = (blue / counted).toInt(),
    )
}

/** The artwork may be very bright; controls always need a dark, coloured surface. */
internal fun Color.comfortableImmersiveColor(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(androidx.compose.ui.graphics.toArgb(this), hsv)
    hsv[2] = hsv[2].coerceIn(0.17f, 0.40f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun Color?.isNearlyBlack(): Boolean =
    this == null || (red + green + blue) / 3f < 0.085f

/** Sample the actual subject instead of a baked-in lower black bar on a video frame. */
private fun android.graphics.Bitmap.averageMiddleStrip(): Color? {
    if (width <= 0 || height <= 0) return null
    val startY = (height * 0.46f).toInt().coerceIn(0, height - 1)
    val endY = (startY + EDGE_SAMPLE_ROWS).coerceAtMost(height)
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0L
    for (y in startY until endY) {
        for (x in 0 until width) {
            val pixel = this[x, y]
            if ((pixel ushr 24 and 0xFF) < 8) continue
            red += pixel shr 16 and 0xFF
            green += pixel shr 8 and 0xFF
            blue += pixel and 0xFF
            count++
        }
    }
    return if (count == 0L) null else Color(
        red = (red / count).toInt(),
        green = (green / count).toInt(),
        blue = (blue / count).toInt(),
    )
}
