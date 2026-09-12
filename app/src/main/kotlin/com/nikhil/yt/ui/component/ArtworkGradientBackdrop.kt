package com.nikhil.yt.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.nikhil.yt.ui.theme.PlayerColorExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts the same restrained artwork palette used by Capsule surfaces.
 * Coil's cache is shared with the visible artwork, so this does not require a
 * second network download once the image is warm.
 */
@Composable
fun rememberArtworkGradientColors(
    thumbnailUrl: String?,
    fallbackColor: Color,
): List<Color> {
    val context = LocalContext.current
    var colors by remember(thumbnailUrl) { mutableStateOf<List<Color>>(emptyList()) }

    LaunchedEffect(thumbnailUrl, fallbackColor) {
        if (thumbnailUrl.isNullOrBlank()) {
            colors = emptyList()
            return@LaunchedEffect
        }

        val request =
            ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .size(Size(PlayerColorExtractor.Config.IMAGE_SIZE, PlayerColorExtractor.Config.IMAGE_SIZE))
                .allowHardware(false)
                .build()

        val bitmap =
            runCatching { context.imageLoader.execute(request).image?.toBitmap() }
                .getOrNull()

        colors =
            if (bitmap == null) {
                emptyList()
            } else {
                val palette =
                    withContext(Dispatchers.Default) {
                        Palette.from(bitmap)
                            .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                            .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                            .generate()
                    }
                PlayerColorExtractor.extractGradientColors(
                    palette = palette,
                    fallbackColor = fallbackColor.toArgb(),
                )
            }
    }

    return colors
}

/**
 * Capsule artwork-to-surface fade used by artist and album screens.
 * It deliberately stays dark and low-contrast: artwork supplies the colour,
 * while the app chrome remains neutral instead of becoming Spotify-like.
 */
@Composable
fun ArtworkGradientBackdrop(
    colors: List<Color>,
    surfaceColor: Color,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    val primary = colors.getOrNull(0)
    val secondary = colors.getOrNull(1) ?: primary
    val tertiary = colors.getOrNull(2) ?: secondary

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .drawBehind {
                    if (primary != null) {
                        drawRect(
                            brush =
                                Brush.radialGradient(
                                    colors =
                                        listOf(
                                            primary.copy(alpha = alpha * 0.52f),
                                            primary.copy(alpha = alpha * 0.20f),
                                            Color.Transparent,
                                        ),
                                    center = Offset(size.width * 0.50f, size.height * 0.10f),
                                    radius = size.width * 0.92f,
                                ),
                        )
                    }
                    if (secondary != null) {
                        drawRect(
                            brush =
                                Brush.radialGradient(
                                    colors =
                                        listOf(
                                            secondary.copy(alpha = alpha * 0.26f),
                                            Color.Transparent,
                                        ),
                                    center = Offset(size.width * 0.08f, size.height * 0.26f),
                                    radius = size.width * 0.72f,
                                ),
                        )
                    }
                    if (tertiary != null) {
                        drawRect(
                            brush =
                                Brush.radialGradient(
                                    colors =
                                        listOf(
                                            tertiary.copy(alpha = alpha * 0.20f),
                                            Color.Transparent,
                                        ),
                                    center = Offset(size.width * 0.92f, size.height * 0.30f),
                                    radius = size.width * 0.70f,
                                ),
                        )
                    }

                    drawRect(
                        brush =
                            Brush.verticalGradient(
                                colorStops =
                                    arrayOf(
                                        0.00f to Color.Transparent,
                                        0.34f to Color.Transparent,
                                        0.64f to surfaceColor.copy(alpha = alpha * 0.38f),
                                        0.82f to surfaceColor.copy(alpha = alpha * 0.82f),
                                        1.00f to surfaceColor,
                                    ),
                            ),
                    )
                },
    )
}
