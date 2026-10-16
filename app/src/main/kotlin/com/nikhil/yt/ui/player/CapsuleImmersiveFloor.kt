/*
 * Capsule MUSIC — artwork-driven colours for the Immersion player only.
 * The controls and all other player designs do not consume this state.
 * GPL-3.0
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.nikhil.yt.innertube.toHighResThumbnail
import com.nikhil.yt.models.MediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal val IMMERSIVE_NEUTRAL_COLOR = Color(0xFF262626)
private const val ARTWORK_SAMPLE_SIZE = 256
private val videoIdPattern = Regex("^[a-zA-Z0-9_-]{11}$")

internal data class ImmersiveArtworkTone(
    val edge: Color = IMMERSIVE_NEUTRAL_COLOR,
    val landscape: Boolean = false,
    val accent: Color = IMMERSIVE_NEUTRAL_COLOR,
    val bottomTexture: Float = 0f,
    val displayUrl: String? = null,
    // A thumbnail URL alone does not imply the dimensions and background are prepared.
    // The player must not render the raw 16:9 preview while this is false.
    val ready: Boolean = false,
)

/** The raw 16:9 image and its coloured floor must become visible in the same frame. */
internal fun canRevealImmersiveArtwork(tone: ImmersiveArtworkTone, imageLoaded: Boolean): Boolean =
    tone.ready && !tone.displayUrl.isNullOrBlank() && imageLoaded

private val immersiveArtworkCache = object : LinkedHashMap<String, ImmersiveArtworkTone>(24, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImmersiveArtworkTone>?): Boolean =
        size > 64
}

/**
 * ArchiveTune's relevant visual behaviour: try actual YouTube video thumbnails (when the
 * original artwork already comes from ytimg), display widescreen frames with Fit over a
 * separate, filled background, and extract a Palette from the SAME decoded image that
 * will be displayed. Do not prefetch a YouTube image for every ordinary square album cover.
 */
internal fun immersiveArtworkCandidates(mediaId: String?, original: String?): List<String> {
    if (original.isNullOrBlank()) return emptyList()
    val base = original.toHighResThumbnail()
    if (mediaId == null || !videoIdPattern.matches(mediaId) ||
        (!original.contains("ytimg.com/vi/", ignoreCase = true) &&
            !original.contains("ytimg.com/vi_webp/", ignoreCase = true))
    ) return listOf(base)

    return listOf(
        "https://i.ytimg.com/vi/$mediaId/maxresdefault.jpg",
        "https://i.ytimg.com/vi/$mediaId/hq720.jpg",
        "https://i.ytimg.com/vi/$mediaId/hqdefault.jpg",
        base,
    ).distinct()
}

@Composable
internal fun rememberImmersiveEdgeColor(
    mediaMetadata: MediaMetadata?,
    enabled: Boolean = true,
    visibleArtworkAspectRatio: Float = 1f,
): ImmersiveArtworkTone {
    val context = LocalContext.current
    val sourceUrl = mediaMetadata?.thumbnailUrl
    // A different viewport shows a different centre-cropped area of the same frame.
    val artworkAspect = visibleArtworkAspectRatio.coerceIn(0.55f, 2.2f)
    val key = mediaMetadata?.id?.let { "$it|$sourceUrl|${(artworkAspect * 100f).toInt()}" }
    var resolved by remember { mutableStateOf<ImmersiveArtworkTone?>(null) }
    var resolvedKey by remember { mutableStateOf<String?>(null) }
    val cached = key?.let { synchronized(immersiveArtworkCache) { immersiveArtworkCache[it] } }

    LaunchedEffect(key, sourceUrl, enabled) {
        // The player is still composed while its sheet is collapsed. Avoid spending
        // energy (or trying multiple ytimg qualities) for rapidly skipped unseen songs.
        if (!enabled) return@LaunchedEffect
        if (key == null || sourceUrl.isNullOrBlank()) {
            resolved = ImmersiveArtworkTone()
            resolvedKey = key
            return@LaunchedEffect
        }
        cached?.let {
            resolved = it
            resolvedKey = key
            return@LaunchedEffect
        }
        // A failed sample must be retried when the user reopens this sheet; do not
        // permanently freeze an otherwise valid thumbnail in the grey placeholder.
        if (resolvedKey == key && resolved?.ready == true) return@LaunchedEffect

        val candidates = immersiveArtworkCandidates(mediaMetadata.id, sourceUrl)
        val isYtimg = candidates.size > 1
        var resultTone: ImmersiveArtworkTone? = null

        for ((index, url) in candidates.withIndex()) {
            val image = try {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(ARTWORK_SAMPLE_SIZE)
                    .allowHardware(false)
                    .build()
                val result = withContext(Dispatchers.IO) { context.imageLoader.execute(request) }
                (result as? SuccessResult)?.image?.toBitmap()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: continue

            val landscape = image.width.toFloat() / image.height.coerceAtLeast(1) >= 4f / 3f
            // maxresdefault/hq720 may return a small 'unavailable thumbnail' image with HTTP
            // 200. Those must not replace the actual song cover or contaminate its palette.
            if (isYtimg && index < 2 && (!landscape || image.width < 200)) continue

            resultTone = withContext(Dispatchers.Default) {
                // Identify the actual BACKGROUND of the entire visible crop,
                // not the average/mode of its lower strip. Foreground artwork,
                // lyrics, credits and black video letterbox bars must not tint
                // the page. Sample a small spatial grid off-thread, favour the
                // recurring colour along several sides, then mute that SAME
                // extracted tone for the existing page gradient.
                val background = image.immersiveArtworkBackground(artworkAspect)
                val bottomTexture = image.immersiveBottomTexture(artworkAspect)
                ImmersiveArtworkTone(
                    edge = background,
                    // Keep the TRUE edge for the photo and store a SEPARATE
                    // modestly shaded version of the SAME extracted colour.
                    // Never dim the edge itself or treat black as the goal.
                    accent = background.immersiveMutedAccent(),
                    bottomTexture = bottomTexture,
                    landscape = landscape,
                    displayUrl = url,
                    ready = true,
                )
            }
            break
        }
        // A failed decode has neither reliable crop geometry nor a sampled background.
        // Leave the player neutral instead of showing a raw thumbnail with black bars.
        val finalTone = resultTone ?: ImmersiveArtworkTone()
        // Cache only a real decoded image with a sampled background. Transient thumbnail
        // errors should not poison every later attempt for this same track.
        if (resultTone != null) {
            synchronized(immersiveArtworkCache) { immersiveArtworkCache[key] = finalTone }
        }
        resolved = finalTone
        resolvedKey = key
    }

    val target = when {
        cached != null -> cached
        resolvedKey == key -> resolved ?: ImmersiveArtworkTone()
        else -> ImmersiveArtworkTone()
    }
    // A colour animation made the background visibly build itself after the cover appeared.
    // The sample is computed off-thread; apply the completed palette atomically with the
    // matching decoded image in the player. Video-card transitions are animated separately.
    // Do NOT expose the unsampled original URL during a new selection: AsyncImage used
    // to draw it immediately with the default square zoom before the real 16:9 dimensions
    // and matching background had been calculated.
    val readyForCurrentTrack = target.ready && (cached != null || resolvedKey == key)
    return target.copy(
        edge = target.edge,
        accent = target.accent,
        displayUrl = if (readyForCurrentTrack) target.displayUrl else null,
        ready = readyForCurrentTrack,
    )
}

/**
 * Darken the colour actually extracted from the lower visible artwork crop.
 * Preserve its own hue; never inject a synthetic blue/slate/graphite tint.
 * For achromatic artwork saturation remains zero.
 *
 * Only called when decoding/sampling the artwork off the UI thread.
 */
internal fun Color.immersiveMutedAccent(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] = (hsv[1] * 0.92f).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] * 0.78f).coerceIn(0.22f, 0.82f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Extract the *background of the visible album cover*, not a mixture of the
 * final rows. Credits/logos/subjects near the bottom are FOREGROUND.
 *
 * 1. Sample a fixed 48x48 grid over the exact ContentScale.Crop rectangle.
 * 2. Ignore solid black top/bottom letterbox bars when the actual image
 *    interior is nonblack (otherwise a black album stays black).
 * 3. Vote for recurring RGB families with extra weight around the perimeter:
 *    true backgrounds usually appear behind objects along many image edges.
 * 4. Smooth votes over adjacent 5-bit RGB bins so mild texture/JPEG variation
 *    does not split one background colour. Average only that winning family.
 *
 * Nothing on the composition/frame clock: the 256px decoded art is analysed
 * on Dispatchers.Default and the resulting colour is cached per crop.
 */
internal fun Bitmap.immersiveArtworkBackground(artworkAspect: Float): Color {
    if (width <= 0 || height <= 0) return IMMERSIVE_NEUTRAL_COLOR
    val targetAspect = artworkAspect.coerceIn(0.55f, 2.2f)
    val naturalAspect = width.toFloat() / height
    val visibleWidth = if (naturalAspect > targetAspect) (height * targetAspect).toInt() else width
    val visibleHeight = if (naturalAspect < targetAspect) (width / targetAspect).toInt() else height
    val x0 = ((width - visibleWidth) / 2).coerceAtLeast(0)
    val y0 = ((height - visibleHeight) / 2).coerceAtLeast(0)
    val w = visibleWidth.coerceAtLeast(1)
    val h = visibleHeight.coerceAtLeast(1)

    val grid = 48
    val pixels = IntArray(grid * grid)
    val blackByRow = IntArray(grid)
    for (gy in 0 until grid) {
        val y = (y0 + ((gy * 2 + 1) * h) / (grid * 2)).coerceIn(0, height - 1)
        for (gx in 0 until grid) {
            val x = (x0 + ((gx * 2 + 1) * w) / (grid * 2)).coerceIn(0, width - 1)
            val rgb = getPixel(x, y)
            pixels[gy * grid + gx] = rgb
            if ((rgb ushr 24 and 0xFF) >= 128 &&
                maxOf((rgb shr 16 and 255), (rgb shr 8 and 255), (rgb and 255)) < 20
            ) blackByRow[gy]++
        }
    }

    // Full-width black bands are often baked into 16:9 YouTube thumbnails.
    // Ignore them only when the centre has real picture information.
    var nonBlackMiddle = 0
    for (gy in 12 until 36) nonBlackMiddle += grid - blackByRow[gy]
    val trimLetterbox = nonBlackMiddle > grid * 24 / 5
    var top = 0
    var bottom = grid
    if (trimLetterbox) {
        while (top < 12 && blackByRow[top] >= 45) top++
        while (bottom > grid - 12 && blackByRow[bottom - 1] >= 45) bottom--
    }

    val votes = IntArray(512)
    val counts = IntArray(512)
    val redSums = LongArray(512)
    val greenSums = LongArray(512)
    val blueSums = LongArray(512)
    var total = 0
    for (gy in top until bottom) {
        for (gx in 0 until grid) {
            val argb = pixels[gy * grid + gx]
            if ((argb ushr 24 and 255) < 128) continue
            val red = argb shr 16 and 255
            val green = argb shr 8 and 255
            val blue = argb and 255
            val cell = ((red shr 5) shl 6) or ((green shr 5) shl 3) or (blue shr 5)
            val distanceToSide = minOf(gx, grid - 1 - gx, gy - top, bottom - 1 - gy)
            val weight = when {
                distanceToSide < 8 -> 4
                distanceToSide < 15 -> 2
                else -> 1
            }
            votes[cell] += weight
            counts[cell] += weight
            redSums[cell] += (red * weight).toLong()
            greenSums[cell] += (green * weight).toLong()
            blueSums[cell] += (blue * weight).toLong()
            total += weight
        }
    }
    if (total == 0) return IMMERSIVE_NEUTRAL_COLOR

    // A 5-bit bucket has 32 levels of tolerance per component. Consider
    // direct neighbours (not distant hues) for textured backdrops.
    fun neighbours(cell: Int, action: (Int) -> Unit) {
        val r = (cell shr 6) and 7
        val g = (cell shr 3) and 7
        val b = cell and 7
        action(cell)
        if (r > 0) action(cell - 64)
        if (r < 7) action(cell + 64)
        if (g > 0) action(cell - 8)
        if (g < 7) action(cell + 8)
        if (b > 0) action(cell - 1)
        if (b < 7) action(cell + 1)
    }

    var best = 0
    var bestScore = -1
    for (cell in votes.indices) {
        if (votes[cell] == 0) continue
        var score = votes[cell] * 2
        neighbours(cell) { candidate -> score += votes[candidate] }
        if (score > bestScore) {
            best = cell
            bestScore = score
        }
    }
    var matched = 0
    var red = 0L
    var green = 0L
    var blue = 0L
    neighbours(best) { cell ->
        matched += counts[cell]
        red += redSums[cell]
        green += greenSums[cell]
        blue += blueSums[cell]
    }
    if (matched == 0) return IMMERSIVE_NEUTRAL_COLOR
    return Color(
        red = (red / matched).toInt(),
        green = (green / matched).toInt(),
        blue = (blue / matched).toInt(),
    )
}

/**
 * Cheap difficulty score for the lower visible crop.
 *
 * This does NOT choose or modify colour. It only measures local luminance changes so dense
 * line-art, text, checker patterns and hard black/white edges can receive a longer dissolve.
 * The bitmap is already the same small 256px sample used for Immersive colour extraction.
 */
internal fun Bitmap.immersiveBottomTexture(artworkAspect: Float): Float {
    if (width <= 1 || height <= 1) return 0f

    val targetAspect = artworkAspect.coerceIn(0.55f, 2.2f)
    val naturalAspect = width.toFloat() / height
    val visibleWidth =
        if (naturalAspect > targetAspect) {
            (height * targetAspect).toInt()
        } else {
            width
        }
    val visibleHeight =
        if (naturalAspect < targetAspect) {
            (width / targetAspect).toInt()
        } else {
            height
        }

    val x0 = ((width - visibleWidth) / 2).coerceAtLeast(0)
    val y0 = ((height - visibleHeight) / 2).coerceAtLeast(0)
    val w = visibleWidth.coerceAtLeast(1)
    val h = visibleHeight.coerceAtLeast(1)

    val top = (y0 + h * 0.68f).toInt().coerceIn(0, height - 1)
    val bottom = (y0 + h * 0.96f).toInt().coerceIn(top + 1, height)
    val stepX = (w / 96).coerceAtLeast(1)
    val stepY = ((bottom - top) / 42).coerceAtLeast(1)

    var totalDifference = 0f
    var comparisons = 0
    var previousRow = FloatArray(0)
    var rowIndex = 0

    for (y in top until bottom step stepY) {
        val row = ArrayList<Float>()
        var previous: Float? = null

        for (x in x0 until (x0 + w).coerceAtMost(width) step stepX) {
            val argb = getPixel(x, y)
            val r = (argb shr 16 and 0xFF).toFloat()
            val g = (argb shr 8 and 0xFF).toFloat()
            val b = (argb and 0xFF).toFloat()
            val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b

            previous?.let {
                totalDifference += abs(luminance - it)
                comparisons += 1
            }

            val column = row.size
            if (rowIndex > 0 && column < previousRow.size) {
                totalDifference += abs(luminance - previousRow[column])
                comparisons += 1
            }

            row += luminance
            previous = luminance
        }

        previousRow = row.toFloatArray()
        rowIndex += 1
    }

    if (comparisons == 0) return 0f

    return ((totalDifference / comparisons) / 72f)
        .coerceIn(0f, 1f)
}

