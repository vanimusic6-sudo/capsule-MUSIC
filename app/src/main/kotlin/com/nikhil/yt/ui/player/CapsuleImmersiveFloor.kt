/*
 * Capsule MUSIC — artwork-driven colours for the Immersion player only.
 * The controls and all other player designs do not consume this state.
 * GPL-3.0
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.nikhil.yt.innertube.toHighResThumbnail
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.ui.motion.CapsuleStandardEasing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val IMMERSIVE_NEUTRAL_COLOR = Color(0xFF262626)
private const val ARTWORK_TRANSITION_MS = 1_400
private const val ARTWORK_SAMPLE_SIZE = 256
private val videoIdPattern = Regex("^[a-zA-Z0-9_-]{11}$")

internal data class ImmersiveArtworkTone(
    val edge: Color = IMMERSIVE_NEUTRAL_COLOR,
    val landscape: Boolean = false,
    val accent: Color = IMMERSIVE_NEUTRAL_COLOR,
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
                // The colour must belong to the background at the BOTTOM of the *visible,
                // centre-cropped* frame. A whole-image vibrant Palette picked the green
                // artwork accent in a black-and-white cover, even though no green background
                // exists at the point where the picture meets the controls.
                val background = image.immersiveBottomBackground(artworkAspect)
                    .comfortableImmersiveColor()
                ImmersiveArtworkTone(
                    edge = background,
                    // Keep one hue from the sampled background, rather than blending in a
                    // second subject colour (which created dirty green/brown gradients).
                    accent = lerp(background, Color.Black, 0.18f),
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
    val edge by animateColorAsState(
        targetValue = target.edge,
        animationSpec = tween(ARTWORK_TRANSITION_MS, easing = CapsuleStandardEasing),
        label = "immersiveArtworkPrimary",
    )
    val accent by animateColorAsState(
        targetValue = target.accent,
        animationSpec = tween(ARTWORK_TRANSITION_MS, easing = CapsuleStandardEasing),
        label = "immersiveArtworkSecondary",
    )
    // Do NOT expose the unsampled original URL during a new selection: AsyncImage used
    // to draw it immediately with the default square zoom before the real 16:9 dimensions
    // and matching background had been calculated.
    val readyForCurrentTrack = target.ready && (cached != null || resolvedKey == key)
    return target.copy(
        edge = edge,
        accent = accent,
        displayUrl = if (readyForCurrentTrack) target.displayUrl else null,
        ready = readyForCurrentTrack,
    )
}

/**
 * Determine the backdrop directly behind the fade, not the picture's vibrant foreground.
 *
 * This samples the same centre crop shown by ContentScale.Crop at the provided viewport
 * aspect. Only the bottom portion before the fade is considered. The modal colour block
 * (not an average of unrelated objects) is used so sparse green lettering on white paper
 * cannot recolour the entire player green. Edge pixels get a small weight: they tend to
 * describe the image's background rather than its foreground subject.
 *
 * Internal for small synthetic-bitmap regression tests.
 */
internal fun Bitmap.immersiveBottomBackground(artworkAspect: Float): Color {
    if (width <= 0 || height <= 0) return IMMERSIVE_NEUTRAL_COLOR
    val targetAspect = artworkAspect.coerceIn(0.55f, 2.2f)
    val naturalAspect = width.toFloat() / height
    val visibleWidth = if (naturalAspect > targetAspect) (height * targetAspect).toInt() else width
    val visibleHeight = if (naturalAspect < targetAspect) (width / targetAspect).toInt() else height
    val x0 = ((width - visibleWidth) / 2).coerceAtLeast(0)
    val y0 = ((height - visibleHeight) / 2).coerceAtLeast(0)
    val w = visibleWidth.coerceAtLeast(1)
    val h = visibleHeight.coerceAtLeast(1)
    // Analyse the lower part of the *image* before our overlay hides it. The very last
    // rows of a YouTube thumbnail may be baked-in black letterboxing or a logo.
    val top = (y0 + h * 0.62f).toInt().coerceIn(0, height - 1)
    val bottom = (y0 + h * 0.88f).toInt().coerceIn(top + 1, height)
    val stepX = (w / 112).coerceAtLeast(1)
    val stepY = ((bottom - top) / 45).coerceAtLeast(1)
    // Small quantised bins group a textured background without combining distinct hues.
    val counts = IntArray(512)
    val rSums = LongArray(512)
    val gSums = LongArray(512)
    val bSums = LongArray(512)
    var samples = 0
    for (y in top until bottom step stepY) {
        for (x in x0 until (x0 + w).coerceAtMost(width) step stepX) {
            val argb = getPixel(x, y)
            if ((argb ushr 24 and 0xFF) < 128) continue
            val r = argb shr 16 and 0xFF
            val g = argb shr 8 and 0xFF
            val b = argb and 0xFF
            // Ignore letterbox and fine black linework unless the entire artwork is dark.
            if (maxOf(r, g, b) < 24) continue
            val bucket = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
            val relativeX = (x - x0).toFloat() / w
            val weight = if (relativeX < 0.22f || relativeX > 0.78f) 2 else 1
            counts[bucket] += weight
            rSums[bucket] += (r * weight).toLong()
            gSums[bucket] += (g * weight).toLong()
            bSums[bucket] += (b * weight).toLong()
            samples += weight
        }
    }
    if (samples == 0) return Color(0xFF1D1D1D)
    val dominant = counts.indices.maxByOrNull { counts[it] } ?: return IMMERSIVE_NEUTRAL_COLOR
    val n = counts[dominant].coerceAtLeast(1)
    return Color(
        red = (rSums[dominant] / n).toInt(),
        green = (gSums[dominant] / n).toInt(),
        blue = (bSums[dominant] / n).toInt(),
    )
}

/** Keep greys grey: increasing the saturation of a neutral background tinted white art. */
internal fun Color.comfortableImmersiveColor(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    // Preserve background hue AND saturation; only limit luminance for white controls.
    // In particular, do not manufacture 32% saturation for greyscale artwork.
    hsv[2] = hsv[2].coerceIn(0.17f, 0.44f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
