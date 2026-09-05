/*
 * Capsule MUSIC
 * Licensed under GPL-3.0
 */

package com.nikhil.yt.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Capsule comet loader.
 *
 * The public name stays compatible with existing call sites, but the former
 * Velune "V" is gone. The animation mirrors Capsule Player's orbit button and
 * uses only two cheap scalar animations.
 */
@Composable
fun VeluneLoader(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    color: Color? = null,
) {
    val accentColor = color ?: MaterialTheme.colorScheme.primary

    val infiniteTransition = rememberInfiniteTransition(label = "capsule_comet_loader")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_150, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "comet_rotation",
    )

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "comet_pulse",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size),
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val width = this.size.width
            val height = this.size.height
            val center = Offset(width / 2f, height / 2f)
            val orbitRadiusX = width * 0.36f
            val orbitRadiusY = height * 0.15f
            val orbitBounds =
                Size(
                    width = orbitRadiusX * 2f,
                    height = orbitRadiusY * 2f,
                )

            rotate(-24f, pivot = center) {
                drawOval(
                    color = accentColor.copy(alpha = 0.32f * pulse),
                    topLeft =
                        Offset(
                            x = center.x - orbitRadiusX,
                            y = center.y - orbitRadiusY,
                        ),
                    size = orbitBounds,
                    style = Stroke(width = width * 0.045f),
                )

                val angle = Math.toRadians(rotation.toDouble())
                val comet =
                    Offset(
                        x = center.x + orbitRadiusX * cos(angle).toFloat(),
                        y = center.y + orbitRadiusY * sin(angle).toFloat(),
                    )

                for (step in 1..4) {
                    val tailAngle =
                        Math.toRadians((rotation - step * 9f).toDouble())
                    val tail =
                        Offset(
                            x = center.x + orbitRadiusX * cos(tailAngle).toFloat(),
                            y = center.y + orbitRadiusY * sin(tailAngle).toFloat(),
                        )
                    drawLine(
                        color = accentColor.copy(alpha = (0.34f / step) * pulse),
                        start = tail,
                        end = comet,
                        strokeWidth = width * (0.052f - step * 0.006f),
                        cap = StrokeCap.Round,
                    )
                }

                drawCircle(
                    color = accentColor.copy(alpha = 0.18f * pulse),
                    radius = width * 0.15f,
                    center = comet,
                )
                drawCircle(
                    color = accentColor.copy(alpha = pulse),
                    radius = width * 0.072f,
                    center = comet,
                )
            }

            drawCircle(
                color = accentColor.copy(alpha = 0.2f * pulse),
                radius = width * 0.16f,
                center = center,
            )
            drawCircle(
                color = accentColor.copy(alpha = pulse),
                radius = width * 0.085f,
                center = center,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.72f * pulse),
                radius = width * 0.027f,
                center = center,
            )
        }
    }
}
