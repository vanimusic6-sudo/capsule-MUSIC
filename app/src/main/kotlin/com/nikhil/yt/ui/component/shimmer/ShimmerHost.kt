/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.component.shimmer

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.nikhil.yt.ui.player.appIsOnScreen
import androidx.compose.material3.MaterialTheme

/** One opacity for the entire loading block; never a moving spatial highlight. */
@Composable
internal fun rememberLoadingPlaceholderOpacity(active: Boolean): State<Float> {
    if (!active) return remember { mutableFloatStateOf(0.25f) }
    return rememberInfiniteTransition(label = "loading-block").animateFloat(
        initialValue = 0.22f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loading-block-opacity",
    )
}

@Composable
fun ShimmerHost(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    fadeToSurface: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Keep the existing static fade at the bottom. Only the whole block's
    // paint opacity moves; no sweep, spatial phase offset or per-row clock.
    val surface = MaterialTheme.colorScheme.surface
    val opacity = rememberLoadingPlaceholderOpacity(active = appIsOnScreen())

    Column(
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement,
        modifier =
        modifier
            .then(
                if (fadeToSurface) Modifier.drawWithCache {
                    val fade = Brush.verticalGradient(listOf(Color.Transparent, surface))
                    onDrawWithContent {
                        drawContent()
                        drawRect(brush = fade)
                    }
                } else Modifier,
            )
            .graphicsLayer {
                alpha = opacity.value
                // Modulate only the separated shapes. The static fade is outside
                // this layer, so its opaque bottom cannot become translucent.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        content = content,
    )
}
