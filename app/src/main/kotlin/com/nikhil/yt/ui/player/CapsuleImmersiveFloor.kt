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

/** As long as the artwork palette's own crossfade, so the two never disagree mid-flight. */
private const val EDGE_TRANSITION_MS = 1_400

private val edgeColorCache = object : LinkedHashMap<String, Color>(24, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?): Boolean =
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
 * Null while it is being worked out, so the caller can hold whatever it was showing rather than
 * flash a guess and correct itself a moment later.
 */
@Composable
internal fun rememberImmersiveEdgeColor(mediaMetadata: MediaMetadata?): Color? {
    val context = LocalContext.current
    val thumbnailUrl = mediaMetadata?.thumbnailUrl
    val cacheKey = mediaMetadata?.id

    val cached = remember(cacheKey) { cacheKey?.let { synchronized(edgeColorCache) { edgeColorCache[it] } } }
    var measured by remember(cacheKey) { mutableStateOf(cached) }

    LaunchedEffect(cacheKey, thumbnailUrl) {
        if (cacheKey == null || thumbnailUrl == null || measured != null) return@LaunchedEffect
        val request =
            ImageRequest.Builder(context)
                .data(thumbnailUrl.toHighResThumbnail())
                .size(EDGE_SAMPLE_SIZE, EDGE_SAMPLE_SIZE)
                .allowHardware(false)
                .build()
        val edge =
            try {
                val bitmap =
                    withContext(Dispatchers.IO) { context.imageLoader.execute(request) }
                        .image
                        ?.toBitmap()
                        ?: return@LaunchedEffect
                withContext(Dispatchers.Default) { bitmap.averageBottomStrip() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@LaunchedEffect

        synchronized(edgeColorCache) { edgeColorCache[cacheKey] = edge }
        measured = edge
    }

    val target = measured ?: return null
    val animated by
        animateColorAsState(
            targetValue = target,
            animationSpec = tween(durationMillis = EDGE_TRANSITION_MS, easing = CapsuleStandardEasing),
            label = "immersiveEdge",
        )
    return animated
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
            val pixel = getPixel(x, y)
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
