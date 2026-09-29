/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.utils

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp


fun Modifier.fadingEdge(
    left: Dp? = null,
    top: Dp? = null,
    right: Dp? = null,
    bottom: Dp? = null,
) = graphicsLayer(alpha = 0.99f)
    .drawWithContent {
        drawContent()
        if (top != null) {
            drawRect(
                brush =
                Brush.verticalGradient(
                    colors =
                    listOf(
                        Color.Transparent,
                        Color.Black,
                    ),
                    startY = 0f,
                    endY = top.toPx(),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (bottom != null) {
            drawRect(
                brush =
                Brush.verticalGradient(
                    colors =
                    listOf(
                        Color.Black,
                        Color.Transparent,
                    ),
                    startY = size.height - bottom.toPx(),
                    endY = size.height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (left != null) {
            drawRect(
                brush =
                Brush.horizontalGradient(
                    colors =
                    listOf(
                        Color.Black,
                        Color.Transparent,
                    ),
                    startX = 0f,
                    endX = left.toPx(),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (right != null) {
            drawRect(
                brush =
                Brush.horizontalGradient(
                    colors =
                    listOf(
                        Color.Transparent,
                        Color.Black,
                    ),
                    startX = size.width - right.toPx(),
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

fun Modifier.fadingEdge(
    horizontal: Dp? = null,
    vertical: Dp? = null,
) = fadingEdge(
    left = horizontal,
    right = horizontal,
    top = vertical,
    bottom = vertical,
)

fun Modifier.smoothFadingEdge(
    top: Dp? = null,
    bottom: Dp? = null,
) = graphicsLayer {
        // DstIn needs one offscreen buffer. Keep it explicit and opaque instead of the old
        // alpha=0.99f trick, which created another alpha blend over the whole lyrics list.
        compositingStrategy = CompositingStrategy.Offscreen
    }
    .drawWithCache {
        // Cache the brushes instead of rebuilding their stop arrays on every scroll frame.
        val topBrush =
            top?.let {
                Brush.verticalGradient(
                    colorStops =
                        arrayOf(
                            0.00f to Color.Transparent,
                            0.18f to Color.Black.copy(alpha = 0.05f),
                            0.34f to Color.Black.copy(alpha = 0.18f),
                            0.55f to Color.Black.copy(alpha = 0.46f),
                            0.72f to Color.Black.copy(alpha = 0.74f),
                            0.86f to Color.Black.copy(alpha = 0.93f),
                            1.00f to Color.Black,
                        ),
                    startY = 0f,
                    endY = it.toPx(),
                )
            }

        val bottomBrush =
            bottom?.let {
                Brush.verticalGradient(
                    colorStops =
                        arrayOf(
                            0.00f to Color.Black,
                            0.14f to Color.Black.copy(alpha = 0.93f),
                            0.30f to Color.Black.copy(alpha = 0.74f),
                            0.48f to Color.Black.copy(alpha = 0.46f),
                            0.68f to Color.Black.copy(alpha = 0.18f),
                            0.84f to Color.Black.copy(alpha = 0.05f),
                            1.00f to Color.Transparent,
                        ),
                    startY = size.height - it.toPx(),
                    endY = size.height,
                )
            }

        onDrawWithContent {
            drawContent()
            topBrush?.let { brush ->
                drawRect(
                    brush = brush,
                    blendMode = BlendMode.DstIn,
                )
            }
            bottomBrush?.let { brush ->
                drawRect(
                    brush = brush,
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }

fun Modifier.smoothFadingEdge(
    vertical: Dp,
) = smoothFadingEdge(
    top = vertical,
    bottom = vertical,
)
