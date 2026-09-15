/*
 * Capsule MUSIC
 * Shared, bounded artwork palette for player surfaces.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
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
import coil3.request.allowHardware
import coil3.toBitmap
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.ui.theme.PlayerColorExtractor
import com.nikhil.yt.utils.reportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val ARTWORK_PALETTE_TRANSITION_MS = 1_400

internal class ArtworkPaletteCache(private val maxEntries: Int = 32) {
    // Bounded stripes also keep waiters on the same lock after a failed decode.
    private val extractionMutexes = Array(8) { Mutex() }
    private val values =
        object : LinkedHashMap<String, List<Color>>(
            maxEntries,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<Color>>?,
            ): Boolean = size > maxEntries
        }

    @Synchronized
    fun get(key: String): List<Color>? = values[key]

    @Synchronized
    fun put(
        key: String,
        colors: List<Color>,
    ) {
        values[key] = colors
    }

    suspend fun getOrExtract(
        key: String,
        extract: suspend () -> List<Color>?,
    ): List<Color>? {
        get(key)?.let { return it }

        val extractionMutex = extractionMutexes[(key.hashCode() and Int.MAX_VALUE) % extractionMutexes.size]
        return extractionMutex.withLock {
            get(key) ?: extract()?.also { put(key, it) }
        }
    }
}

private val CapsuleArtworkPaletteCache = ArtworkPaletteCache()

// Thumbnail resolution and URL signatures can change while the same song plays.
// Keep its first successful palette for the lifetime of the shared cache entry.
internal fun capsuleArtworkPaletteKey(mediaId: String): String = "track:$mediaId"

/**
 * Player, mini-player and Capsule Dock all use this exact palette. Besides
 * avoiding repeated bitmap decoding, this prevents three adjacent surfaces
 * from choosing slightly different fallback colours for the same track.
 */
@Composable
internal fun rememberCapsuleArtworkColors(
    mediaMetadata: MediaMetadata?,
    enabled: Boolean = true,
): List<Color> =
    rememberArtworkGradientColors(
        cacheKey =
            mediaMetadata?.let {
                capsuleArtworkPaletteKey(it.id)
            },
        thumbnailUrl = mediaMetadata?.thumbnailUrl,
        enabled = enabled,
    )

/** Shared artwork palette for non-player surfaces such as playlist cards. */
@Composable
internal fun rememberArtworkGradientColors(
    cacheKey: String?,
    thumbnailUrl: String?,
    enabled: Boolean = true,
    paletteCache: ArtworkPaletteCache = CapsuleArtworkPaletteCache,
): List<Color> {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.surface
    val fallback =
        remember(primary, secondary, tertiary) {
            listOf(primary, secondary, tertiary)
        }
    /*
     * Keep the last visible palette while the next cover is being decoded.
     * Keying this state by cacheKey used to recreate it for every track and
     * briefly expose the theme fallback before the new artwork was ready.
     */
    val initialPalette = remember { cacheKey?.let(paletteCache::get) }
    var targetColors by remember { mutableStateOf(initialPalette ?: fallback) }
    var hasArtworkPalette by remember { mutableStateOf(initialPalette != null) }
    var resolvedKey by remember { mutableStateOf(cacheKey.takeIf { initialPalette != null }) }

    LaunchedEffect(
        cacheKey,
        thumbnailUrl,
        enabled,
        primary,
        secondary,
        tertiary,
        surface,
    ) {
        if (!enabled) {
            return@LaunchedEffect
        }

        if (cacheKey == null) {
            if (!hasArtworkPalette) {
                targetColors = fallback
            }
            return@LaunchedEffect
        }

        // Pin the visible song even if browsing many albums evicts its cache entry.
        if (cacheKey == resolvedKey) return@LaunchedEffect

        paletteCache.get(cacheKey)?.let {
            targetColors = it
            hasArtworkPalette = true
            resolvedKey = cacheKey
            return@LaunchedEffect
        }

        if (thumbnailUrl.isNullOrBlank()) {
            // Metadata can arrive before its thumbnail. Retain the previous
            // palette instead of starting an old -> theme -> artwork flash.
            return@LaunchedEffect
        }

        val extracted =
            paletteCache.getOrExtract(cacheKey) {
                val request =
                    ImageRequest.Builder(context)
                        .data(thumbnailUrl)
                        .size(
                            PlayerColorExtractor.Config.IMAGE_SIZE,
                            PlayerColorExtractor.Config.IMAGE_SIZE,
                        )
                        .allowHardware(false)
                        .build()
                try {
                    val artworkBitmap = withContext(Dispatchers.IO) {
                        context.imageLoader.execute(request)
                    }.image?.toBitmap() ?: return@getOrExtract null
                    val palette =
                        withContext(Dispatchers.Default) {
                            Palette.from(artworkBitmap)
                                .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                                .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                                .generate()
                        }
                    PlayerColorExtractor.extractGradientColors(
                        palette = palette,
                        fallbackColor = surface.toArgb(),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reportException(failure)
                    null
                }
            }
                ?: return@LaunchedEffect

        targetColors = extracted
        hasArtworkPalette = true
        resolvedKey = cacheKey
    }

    val first by
        animateColorAsState(
            targetValue = targetColors.getOrElse(0) { fallback[0] },
            animationSpec =
                tween(
                    durationMillis = ARTWORK_PALETTE_TRANSITION_MS,
                    easing = FastOutSlowInEasing,
                ),
            label = "capsuleArtworkPrimary",
        )
    val second by
        animateColorAsState(
            targetValue = targetColors.getOrElse(1) { fallback[1] },
            animationSpec =
                tween(
                    durationMillis = ARTWORK_PALETTE_TRANSITION_MS,
                    easing = FastOutSlowInEasing,
                ),
            label = "capsuleArtworkSecondary",
        )
    val third by
        animateColorAsState(
            targetValue = targetColors.getOrElse(2) { fallback[2] },
            animationSpec =
                tween(
                    durationMillis = ARTWORK_PALETTE_TRANSITION_MS,
                    easing = FastOutSlowInEasing,
                ),
            label = "capsuleArtworkTertiary",
        )

    return if (enabled) listOf(first, second, third) else emptyList()
}
