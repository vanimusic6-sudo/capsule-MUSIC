package com.nikhil.yt.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** The photograph supplies the colour; both headers dissolve into the exact page surface. */
@Composable
internal fun ArtworkSurfaceFade(background: Color, modifier: Modifier = Modifier, portrait: Boolean = false) {
    Box(modifier.drawWithCache {
        val navigationScrim = if (portrait) Brush.verticalGradient(
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
        val surfaceFade = if (portrait) Brush.verticalGradient(
            // The lower photograph is progressively blurred before this surface dissolve.
            0f to background.copy(alpha = 0f),
            0.40f to background.copy(alpha = 0f),
            0.50f to background.copy(alpha = 0.025f),
            0.60f to background.copy(alpha = 0.065f),
            0.70f to background.copy(alpha = 0.14f),
            0.78f to background.copy(alpha = 0.28f),
            0.85f to background.copy(alpha = 0.48f),
            0.90f to background.copy(alpha = 0.68f),
            0.945f to background.copy(alpha = 0.87f),
            0.975f to background.copy(alpha = 0.97f),
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
            drawRect(navigationScrim)
            drawRect(surfaceFade)
        }
    })
}
