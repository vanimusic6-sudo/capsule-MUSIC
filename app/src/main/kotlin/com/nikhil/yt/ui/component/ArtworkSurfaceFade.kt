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
        val navigationScrim = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.48f),
            0.25f to Color.Transparent,
            1f to Color.Transparent,
        )
        val surfaceFade = if (portrait) Brush.verticalGradient(
            0f to background.copy(alpha = 0f),
            0.48f to background.copy(alpha = 0f),
            0.62f to background.copy(alpha = 0.18f),
            0.76f to background.copy(alpha = 0.65f),
            0.9f to background.copy(alpha = 0.96f),
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
