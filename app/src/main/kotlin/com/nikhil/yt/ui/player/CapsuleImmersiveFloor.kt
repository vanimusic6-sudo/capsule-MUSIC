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
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.nikhil.yt.innertube.toHighResThumbnail
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.ui.motion.CapsuleStandardEasing
import com.nikhil.yt.ui.theme.PlayerColorExtractor
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
)

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
        !original.contains("ytimg.com/vi/", ignoreCase = true)
    ) return listOf(base)

    return listOf(
        "https://i.ytimg.com/vi/$mediaId/maxresdefault.jpg",
        "https://i.ytimg.com/vi/$mediaId/hq720.jpg",
        "https://i.ytimg.com/vi/$mediaId/hqdefault.jpg",
        base,
    ).distinct()
}

@Composable
internal fun rememberImmersiveEdgeColor(mediaMetadata: MediaMetadata?): ImmersiveArtworkTone {
    val context = LocalContext.current
    val sourceUrl = mediaMetadata?.thumbnailUrl
    val key = mediaMetadata?.id?.let { "$it|$sourceUrl" }
    var resolved by remember { mutableStateOf<ImmersiveArtworkTone?>(null) }
    var resolvedKey by remember { mutableStateOf<String?>(null) }
    val cached = key?.let { synchronized(immersiveArtworkCache) { immersiveArtworkCache[it] } }

    LaunchedEffect(key, sourceUrl) {
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
        if (resolvedKey == key) return@LaunchedEffect

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
                val palette = image.centralArtworkPalette()
                val extracted = palette?.let {
                    PlayerColorExtractor.extractGradientColors(
                        palette = it,
                        fallbackColor = IMMERSIVE_NEUTRAL_COLOR.toArgb(),
                    )
                }.orEmpty()
                // Prefer *real* swatches; generated palette complements are only fallbacks.
                // The middle of the image excludes a baked-in black letterbox at the bottom.
                val dominant = palette?.vibrantSwatch?.rgb ?: palette?.mutedSwatch?.rgb
                    ?: palette?.dominantSwatch?.rgb
                val secondary = palette?.darkVibrantSwatch?.rgb ?: palette?.darkMutedSwatch?.rgb
                    ?: palette?.dominantSwatch?.rgb
                val primaryColor = dominant?.let { Color(it) } ?: extracted.firstOrNull()
                    ?: IMMERSIVE_NEUTRAL_COLOR
                val secondaryColor = secondary?.let { Color(it) } ?: extracted.getOrNull(1)
                    ?: primaryColor
                ImmersiveArtworkTone(
                    edge = primaryColor.comfortableImmersiveColor(),
                    accent = secondaryColor.comfortableImmersiveColor(),
                    landscape = landscape,
                    displayUrl = url,
                )
            }
            break
        }
        val finalTone = resultTone ?: ImmersiveArtworkTone(displayUrl = sourceUrl.toHighResThumbnail())
        synchronized(immersiveArtworkCache) { immersiveArtworkCache[key] = finalTone }
        resolved = finalTone
        resolvedKey = key
    }

    val target = when {
        cached != null -> cached
        resolvedKey == key -> resolved ?: ImmersiveArtworkTone()
        else -> resolved ?: ImmersiveArtworkTone()
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
    // Never render a previously selected song's URL while new metadata is being decoded.
    val visibleUrl = if (cached != null || resolvedKey == key) target.displayUrl
        else sourceUrl?.toHighResThumbnail()
    return target.copy(edge = edge, accent = accent, displayUrl = visibleUrl)
}

private fun Bitmap.centralArtworkPalette(): Palette? {
    if (width <= 0 || height <= 0) return null
    val y = (height * 0.18f).toInt().coerceIn(0, height - 1)
    val h = (height * 0.64f).toInt().coerceIn(1, height - y)
    val cropped = Bitmap.createBitmap(this, 0, y, width, h)
    return try {
        Palette.from(cropped)
            .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
            .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
            .generate()
    } finally {
        // createBitmap may return the input for a full-sized crop.
        if (cropped !== this) cropped.recycle()
    }
}

/** ArchiveTune-like HSV limits keep white labels readable without crushing hues to black. */
internal fun Color.comfortableImmersiveColor(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.32f)
    hsv[2] = hsv[2].coerceIn(0.18f, 0.50f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
