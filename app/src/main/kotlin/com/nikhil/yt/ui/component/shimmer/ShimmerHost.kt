/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.component.shimmer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.valentinilk.shimmer.defaultShimmerTheme
import com.valentinilk.shimmer.shimmer
import androidx.compose.material3.MaterialTheme

@Composable
fun ShimmerHost(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    /*
     * The placeholders fade out towards the bottom, and how that fade is produced matters.
     *
     * It used to be a mask: `alpha = 0.99f` to force the column into an offscreen buffer, then a
     * black-to-transparent gradient blended over it with DstIn to eat the alpha. That is a
     * full-size offscreen render and a blend pass, on every frame, for as long as the shimmer runs
     * -- and the shimmer runs continuously while anything is loading, which is exactly when the
     * screen is also busy laying content out. It is the most expensive thing on the loading path
     * and none of it is visible: what the user sees is placeholders that get fainter downwards.
     *
     * Drawing the destination's own colour over them, opaque at the bottom and transparent at the
     * top, gives the identical result on Capsule's screens, because every destination sits on an
     * opaque canvas of exactly this colour. Ordinary source-over painting: no offscreen buffer, no
     * blend mode, no layer.
     */
    val surface = MaterialTheme.colorScheme.surface

    Column(
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement,
        modifier =
        modifier
            .shimmer()
            .drawWithContent {
                drawContent()
                drawRect(
                    brush =
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, surface),
                        ),
                )
            },
        content = content,
    )
}

val ShimmerTheme =
    defaultShimmerTheme.copy(
        animationSpec =
        infiniteRepeatable(
            animation =
            tween(
                durationMillis = 800,
                easing = LinearEasing,
                delayMillis = 250,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        shaderColors =
        listOf(
            Color.Unspecified.copy(alpha = 0.25f),
            Color.Unspecified.copy(alpha = 0.50f),
            Color.Unspecified.copy(alpha = 0.25f),
        ),
    )
