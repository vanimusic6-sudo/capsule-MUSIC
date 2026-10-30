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
    artistPortrait: Boolean = false,
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
        // This is opt-in: artist heroes keep their existing gradient.
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
        ) else if (portrait && artistPortrait) Brush.verticalGradient(
            // The action row can be 62dp lower on living artists. Keep real
            // colour in the extended photo almost to the button instead of
            // turning the lower third into a wide flat grey/black strip.
            0f to Color.Transparent,
            0.66f to Color.Transparent,
            0.74f to background.copy(alpha = 0.015f),
            0.80f to background.copy(alpha = 0.05f),
            0.86f to background.copy(alpha = 0.12f),
            0.90f to background.copy(alpha = 0.23f),
            0.94f to background.copy(alpha = 0.43f),
            0.97f to background.copy(alpha = 0.72f),
            0.99f to background.copy(alpha = 0.92f),
            1f to background,
        ) else if (portrait) Brush.verticalGradient(
            // A matte veil in the page colour, like the upper scrim: retain photo detail
            // while progressively replacing it with the surface, without filtering pixels.
            0f to background.copy(alpha = 0f),
            0.40f to background.copy(alpha = 0f),
            0.48f to background.copy(alpha = 0.025f),
            0.56f to background.copy(alpha = 0.075f),
            0.64f to background.copy(alpha = 0.16f),
            0.72f to background.copy(alpha = 0.30f),
            0.80f to background.copy(alpha = 0.49f),
            0.87f to background.copy(alpha = 0.70f),
            0.93f to background.copy(alpha = 0.88f),
            0.97f to background.copy(alpha = 0.97f),
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
