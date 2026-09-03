/*
 * Capsule MUSIC
 * Shared, bounded artwork palette for player surfaces.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object CapsuleArtworkPaletteCache {
    private const val MAX_ENTRIES = 32
    private val values =
        object : LinkedHashMap<String, List<Color>>(
            MAX_ENTRIES,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<Color>>?,
            ): Boolean = size > MAX_ENTRIES
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
}

/**
 * Player, mini-player and Capsule Dock all use this exact palette. Besides
 * avoiding repeated bitmap decoding, this prevents three adjacent surfaces
 * from choosing slightly different fallback colours for the same track.
 */
@Composable
internal fun rememberCapsuleArtworkColors(
    mediaMetadata: MediaMetadata?,
    enabled: Boolean = true,
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
    val cacheKey =
        mediaMetadata?.let {
            "${it.id}|${it.thumbnailUrl.orEmpty()}"
        }
    var colors by
        remember(cacheKey, enabled) {
            mutableStateOf(
                if (enabled && cacheKey != null) {
                    CapsuleArtworkPaletteCache.get(cacheKey).orEmpty()
                } else {
                    emptyList()
                },
            )
        }

    LaunchedEffect(
        cacheKey,
        enabled,
        primary,
        secondary,
        tertiary,
        surface,
    ) {
        if (!enabled || cacheKey == null) {
            colors = emptyList()
            return@LaunchedEffect
        }

        CapsuleArtworkPaletteCache.get(cacheKey)?.let {
            colors = it
            return@LaunchedEffect
        }

        val thumbnailUrl = mediaMetadata?.thumbnailUrl
        if (thumbnailUrl.isNullOrBlank()) {
            colors = fallback
            return@LaunchedEffect
        }

        val request =
            ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .size(
                    PlayerColorExtractor.Config.IMAGE_SIZE,
                    PlayerColorExtractor.Config.IMAGE_SIZE,
                )
                .allowHardware(false)
                .build()
        val bitmap =
            runCatching {
                withContext(Dispatchers.IO) {
                    context.imageLoader.execute(request)
                }.image?.toBitmap()
            }.getOrNull()
        if (bitmap == null) {
            colors = fallback
            return@LaunchedEffect
        }

        val extracted =
            runCatching {
                val palette =
                    withContext(Dispatchers.Default) {
                        Palette.from(bitmap)
                            .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                            .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                            .generate()
                    }
                PlayerColorExtractor.extractGradientColors(
                    palette = palette,
                    fallbackColor = surface.toArgb(),
                )
            }.getOrElse {
                colors = fallback
                return@LaunchedEffect
            }

        CapsuleArtworkPaletteCache.put(cacheKey, extracted)
        colors = extracted
    }

    return if (colors.isEmpty() && enabled) fallback else colors
}
