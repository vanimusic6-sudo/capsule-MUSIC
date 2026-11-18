/*
 * Capsule MUSIC
 * Shared, bounded artwork palette for player surfaces.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.utils.reportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.nikhil.yt.ui.motion.CapsuleStandardEasing

// Keep the colour adjustment almost imperceptible; a long RGB interpolation
// causes a second visible "gradient blink" after the new song is already playing.
private const val ARTWORK_PALETTE_TRANSITION_MS = 460

internal data class CapsuleArtworkScene(
    val colors: List<Color>,
    val field: CapsuleColorField? = null,
    /** A synthetic pending palette must not masquerade as a decoded cover. */
    val ready: Boolean = true,
)

internal class ArtworkPaletteCache(private val maxEntries: Int = 32) {
    // Bounded stripes also keep waiters on the same lock after a failed decode.
    private val extractionMutexes = Array(8) { Mutex() }
    private val values =
        object : LinkedHashMap<String, CapsuleArtworkScene>(
            maxEntries,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CapsuleArtworkScene>?,
            ): Boolean = size > maxEntries
        }

    @Synchronized
    fun get(key: String): List<Color>? = values[key]?.colors

    @Synchronized
    fun getScene(key: String): CapsuleArtworkScene? = values[key]

    @Synchronized
    fun putScene(key: String, scene: CapsuleArtworkScene) { values[key] = scene }

    @Synchronized
    fun put(
        key: String,
        colors: List<Color>,
    ) {
        values[key] = CapsuleArtworkScene(colors)
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

    suspend fun getOrExtractScene(key: String, extract: suspend () -> CapsuleArtworkScene?): CapsuleArtworkScene? {
        getScene(key)?.let { return it }
        val lock = extractionMutexes[(key.hashCode() and Int.MAX_VALUE) % extractionMutexes.size]
        return lock.withLock { getScene(key) ?: extract()?.also { putScene(key, it) } }
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
    refreshOnOpen: Boolean = false,
): List<Color> =
    rememberArtworkGradientColors(
        cacheKey =
            mediaMetadata?.let {
                capsuleArtworkPaletteKey(it.id)
            },
        thumbnailUrl = mediaMetadata?.thumbnailUrl,
        enabled = enabled,
        refreshOnOpen = refreshOnOpen,
    )

/** Shared artwork palette for non-player surfaces such as playlist cards. */
@Composable
internal fun rememberArtworkGradientColors(
    cacheKey: String?, thumbnailUrl: String?, enabled: Boolean = true,
    paletteCache: ArtworkPaletteCache = CapsuleArtworkPaletteCache,
    refreshOnOpen: Boolean = false,
): List<Color> = rememberArtworkScene(cacheKey, thumbnailUrl, enabled, paletteCache, refreshOnOpen).colors

@Composable
internal fun rememberCapsuleArtworkScene(
    mediaMetadata: MediaMetadata?, enabled: Boolean = true,
    refreshOnOpen: Boolean = false, animatePalette: Boolean = true,
    retainWhenDisabled: Boolean = false,
): CapsuleArtworkScene = rememberArtworkScene(
    mediaMetadata?.let { capsuleArtworkPaletteKey(it.id) }, mediaMetadata?.thumbnailUrl,
    enabled, CapsuleArtworkPaletteCache, refreshOnOpen, animatePalette, retainWhenDisabled,
)

@Composable
internal fun rememberArtworkScene(
    cacheKey: String?, thumbnailUrl: String?, enabled: Boolean,
    paletteCache: ArtworkPaletteCache, refreshOnOpen: Boolean,
    animatePalette: Boolean = true,
    retainWhenDisabled: Boolean = false,
): CapsuleArtworkScene {
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
     * Treat a media-ID change as an atomic palette boundary. Previous code
     * kept the last track's colours while the new bitmap decoded and then
     * interpolated *three* channels independently into the new track.
     * This was a visible stale-gradient ghost / double flash during skips.
     *
     * A cached scene is presented immediately. For an uncached track, a
     * neutral dark scene is used until its OWN extracted colour field is ready.
     * Never blend colours from another track's frame into this one.
     */
    val initialPalette = remember(cacheKey) { cacheKey?.let(paletteCache::getScene) }
    val pendingPalette = remember(cacheKey, fallback) {
        if (cacheKey == null) CapsuleArtworkScene(fallback)
        else CapsuleArtworkScene(List(3) { Color(0xFF1A1B21) }, ready = false)
    }
    var targetScene by remember(cacheKey) { mutableStateOf(initialPalette ?: pendingPalette) }
    var hasArtworkPalette by remember(cacheKey) { mutableStateOf(initialPalette != null) }
    var resolvedKey by remember(cacheKey) {
        mutableStateOf(cacheKey.takeIf { initialPalette != null })
    }
    // Transient image failures (and rapid skip cancellation) must recover *while still
    // on this song*. The old effect only retried after a sheet reopen, producing
    // a permanent neutral gradient until navigation destroyed the composition.
    var retryCount by remember(cacheKey, thumbnailUrl) { mutableIntStateOf(0) }

    LaunchedEffect(
        cacheKey,
        thumbnailUrl,
        enabled,
        retryCount,
        refreshOnOpen,
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
                targetScene = pendingPalette
            }
            return@LaunchedEffect
        }

        // Pin the visible song even if browsing many albums evicts its cache entry.
        // Reopening the sheet reattempts ONLY a previously failed extraction:
        // successful palettes exit here without any extra network or bitmap work.
        if (cacheKey == resolvedKey) return@LaunchedEffect

        paletteCache.getScene(cacheKey)?.let {
            targetScene = it
            hasArtworkPalette = true
            resolvedKey = cacheKey
            return@LaunchedEffect
        }

        if (thumbnailUrl.isNullOrBlank()) {
            // Keep the neutral current-track surface, never the old song's field.
            return@LaunchedEffect
        }

        val extracted =
            paletteCache.getOrExtractScene(cacheKey) {
                // Palette analysis must use the SAME high-quality source/crop
                // as the visible cover. The former 200px request for the raw
                // thumbnail URL raced with a 960px full-player request and
                // could even sample a different YouTube frame.
                val choices = capsuleFullPlayerArtworkCandidates(thumbnailUrl)
                try {
                    val artworkBitmap = withContext(Dispatchers.IO) {
                        var selected: android.graphics.Bitmap? = null
                        for (url in choices) {
                            val bitmap = try {
                                context.imageLoader.execute(
                                    ImageRequest.Builder(context)
                                        .data(url)
                                        .size(960, 960)
                                        .allowHardware(false)
                                        .build()
                                ).image?.toBitmap()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                            if (bitmap != null && capsulePlayerPosterLargeEnough(
                                    url, bitmap.width, bitmap.height
                                )) {
                                selected = bitmap
                                break
                            }
                        }
                        selected
                    } ?: return@getOrExtractScene null
                    withContext(Dispatchers.Default) {
                        // Full-res download is shared with the player, but
                        // palette math needs only a compact in-memory sample.
                        // Never make a second low-resolution HTTP request.
                        val longest = maxOf(artworkBitmap.width, artworkBitmap.height)
                        if (longest <= 384) {
                            capsuleArtworkScene(artworkBitmap)
                        } else {
                            val sample = android.graphics.Bitmap.createScaledBitmap(
                                artworkBitmap,
                                (artworkBitmap.width * 384f / longest).toInt().coerceAtLeast(1),
                                (artworkBitmap.height * 384f / longest).toInt().coerceAtLeast(1),
                                true,
                            )
                            try { capsuleArtworkScene(sample) }
                            finally { sample.recycle() }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reportException(failure)
                    null
                }
            }
        if (extracted == null) {
            // Only the newest mediaId's effect survives: no out-of-order result,
            // no forever-running polling and no cache entry for an error placeholder.
            if (retryCount < 2) {
                delay(if (retryCount == 0) 650L else 1_250L)
                retryCount++
            }
            return@LaunchedEffect
        }

        targetScene = extracted
        hasArtworkPalette = true
        resolvedKey = cacheKey
    }

    // Remove hidden palette animations from the frame clock altogether. Keep the decoded
    // target above so the same colors are immediately available when the surface returns.
    if (!enabled) return if (retainWhenDisabled) targetScene else CapsuleArtworkScene(emptyList())
    if (!animatePalette) return targetScene

    // Reset the animator at each media boundary. Within one song, an
    // uncached palette may settle once after decode, but rapid A -> B -> C
    // never queues A/B colours or draws a mismatched palette/field pair.
    return key(cacheKey) {
        val first by
            animateColorAsState(
                targetValue = targetScene.colors.getOrElse(0) { fallback[0] },
                animationSpec =
                    tween(
                        durationMillis = ARTWORK_PALETTE_TRANSITION_MS,
                        easing = CapsuleStandardEasing,
                    ),
                label = "capsuleArtworkPrimary",
            )
        val second by
            animateColorAsState(
                targetValue = targetScene.colors.getOrElse(1) { fallback[1] },
                animationSpec =
                    tween(
                        durationMillis = ARTWORK_PALETTE_TRANSITION_MS,
                        easing = CapsuleStandardEasing,
                    ),
                label = "capsuleArtworkSecondary",
            )
        val third by
            animateColorAsState(
                targetValue = targetScene.colors.getOrElse(2) { fallback[2] },
                animationSpec =
                    tween(
                        durationMillis = ARTWORK_PALETTE_TRANSITION_MS,
                        easing = CapsuleStandardEasing,
                    ),
                label = "capsuleArtworkTertiary",
            )
    
        remember(first, second, third, targetScene.field) {
            CapsuleArtworkScene(listOf(first, second, third), targetScene.field, targetScene.ready)
        }
    }
}
