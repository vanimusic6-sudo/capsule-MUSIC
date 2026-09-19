package com.nikhil.yt.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A soft bloom where artwork meets the page, as if the cover were lighting the surface below it.
 *
 * Drawn, not blurred, and that is the whole design. A `RenderEffect` would force an offscreen
 * buffer for the region on every frame — the thing that made the player stall the first time it was
 * opened — whereas a radial gradient costs one rect in the draw phase and is cached by
 * `drawWithCache` until the size or the colour changes. It also never animates: it is a static
 * quality of the screen, not an effect that runs.
 *
 * The centre sits low on purpose. A bloom centred on the artwork would read as a spotlight on the
 * image; sitting near its lower edge, it reads as light spilling out of the image and onto the
 * interface, which is what makes the seam between the two disappear.
 */
@Composable
internal fun ArtworkGlow(
    tint: Color,
    modifier: Modifier = Modifier,
    intensity: Float = 0.10f,
) {
    Box(
        modifier.drawWithCache {
            val glow =
                Brush.radialGradient(
                    0f to tint.copy(alpha = intensity.coerceIn(0f, 1f)),
                    0.45f to tint.copy(alpha = intensity.coerceIn(0f, 1f) * 0.45f),
                    1f to Color.Transparent,
                    center = Offset(size.width / 2f, size.height * GLOW_CENTRE_HEIGHT),
                    radius = (size.width * GLOW_RADIUS).coerceAtLeast(1f),
                )
            onDrawBehind { drawRect(glow) }
        },
    )
}

private const val GLOW_CENTRE_HEIGHT = 0.80f
private const val GLOW_RADIUS = 0.78f
