package com.nikhil.yt.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** The photograph supplies the colour; both headers dissolve into the exact page surface. */
@Composable
internal fun ArtworkSurfaceFade(
    background: Color,
    modifier: Modifier = Modifier,
    portrait: Boolean = false,
    topScrim: Boolean = true,
    softPortrait: Boolean = false,
) {
    Box(modifier.drawWithCache {
        val navigationScrim = if (!topScrim) null else if (portrait) Brush.verticalGradient(
            0f to background,
            0.035f to background.copy(alpha = 0.94f),
            0.085f to background.copy(alpha = 0.58f),
            0.14f to Color.Transparent,
            1f to Color.Transparent,
        ) else Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.48f),
            0.25f to Color.Transparent,
            1f to Color.Transparent,
        )
        // Albums gently surrender colour over most of the portrait instead
        // of holding the image at full contrast until its last third.
        // This is opt-in: artists retain the common, longer portrait gradient.
        val surfaceFade = if (portrait && softPortrait) Brush.verticalGradient(
            0f to Color.Transparent,
            0.14f to Color.Transparent,
            0.25f to background.copy(alpha = 0.04f),
            0.35f to background.copy(alpha = 0.11f),
            0.45f to background.copy(alpha = 0.22f),
            0.55f to background.copy(alpha = 0.38f),
            0.65f to background.copy(alpha = 0.56f),
            0.75f to background.copy(alpha = 0.74f),
            0.85f to background.copy(alpha = 0.89f),
            0.93f to background.copy(alpha = 0.97f),
            1f to background,
        ) else if (portrait) Brush.verticalGradient(
            // Shared artist, album and playlist matte. Compared with the
            // original 40%-start portrait: a slightly longer, gentler falloff
            // that keeps photo colour for longer instead of darkening most of
            // the image. Only the very last pixels become fully opaque.
            0f to Color.Transparent,
            0.37f to Color.Transparent,
            0.48f to background.copy(alpha = 0.02f),
            0.58f to background.copy(alpha = 0.065f),
            0.66f to background.copy(alpha = 0.14f),
            0.74f to background.copy(alpha = 0.28f),
            0.81f to background.copy(alpha = 0.46f),
            0.88f to background.copy(alpha = 0.67f),
            0.94f to background.copy(alpha = 0.87f),
            0.985f to background.copy(alpha = 0.985f),
            1f to background,
        ) else Brush.verticalGradient(
            0f to background.copy(alpha = 0f),
            0.28f to background.copy(alpha = 0f),
            0.44f to background.copy(alpha = 0.26f),
            0.60f to background.copy(alpha = 0.72f),
            0.78f to background.copy(alpha = 0.94f),
            1f to background,
        )
        onDrawBehind {
            navigationScrim?.let { drawRect(it) }
            drawRect(surfaceFade)
        }
    })
}
