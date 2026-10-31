package com.nikhil.yt.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * Static matte for artist/album/playlist portraits.
 *
 * The old 37%-start hand-tuned stops kept almost all of the photograph until
 * its last quarter, then rapidly dropped it into the page colour. Bright
 * clothing showed a visible dark shelf even though the last pixels matched.
 *
 * Start the transition gently at 24%; apply ONE continuous eased curve from
 * there to the precise existing 98.5%-full-colour endpoint. It preserves the
 * readable upper portrait, distributes the fade through the middle, and has
 * zero slope at both ends (no kink or hard final band).
 *
 * Called only in drawWithCache: 24 gradient intervals are allocated on cache
 * rebuild, never during scrolling or at each frame.
 */
internal const val CapsulePortraitFadeStart = 0.24f
internal const val CapsulePortraitFadeEnd = 0.985f

internal fun capsulePortraitMatteAlpha(heightFraction: Float): Float {
    if (!heightFraction.isFinite()) return 0f
    val t = ((heightFraction - CapsulePortraitFadeStart) /
        (CapsulePortraitFadeEnd - CapsulePortraitFadeStart)).coerceIn(0f, 1f)
    val ease = t * t * (3f - 2f * t)
    return ease.pow(1.95f)
}

internal fun capsulePortraitMatteStops(background: Color): Array<Pair<Float, Color>> {
    val steps = 24
    return Array(steps + 3) { index ->
        when (index) {
            0 -> 0f to Color.Transparent
            steps + 2 -> 1f to background
            else -> {
                val y = CapsulePortraitFadeStart +
                    (CapsulePortraitFadeEnd - CapsulePortraitFadeStart) *
                    ((index - 1).toFloat() / steps)
                y to background.copy(alpha = capsulePortraitMatteAlpha(y))
            }
        }
    }
}

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
            *capsulePortraitMatteStops(background),
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
