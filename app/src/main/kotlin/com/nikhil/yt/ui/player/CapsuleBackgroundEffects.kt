/*
 * Capsule MUSIC
 * Lightweight procedural backgrounds for the full and mini players.
 * Licensed under GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal enum class CapsuleBackgroundEffect {
    COLOR_FLOW,
    CAPSULE_STAR,
    AURORA,
    NEBULA,
}

@Composable
internal fun CapsuleProceduralBackground(
    effect: CapsuleBackgroundEffect,
    modifier: Modifier = Modifier,
    colors: List<Color> = emptyList(),
    compact: Boolean = false,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val palette =
        remember(colors, primary, secondary, tertiary) {
            listOf(
                colors.getOrElse(0) { primary },
                colors.getOrElse(1) { secondary },
                colors.getOrElse(2) { tertiary },
            ).map(::comfortableEffectColor)
        }
    val animation = rememberInfiniteTransition(label = "capsule_background")
    val phase =
        animation.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(24_000, easing = LinearEasing),
                ),
            label = "capsule_background_phase",
        )

    Canvas(modifier = modifier) {
        when (effect) {
            CapsuleBackgroundEffect.COLOR_FLOW ->
                drawColorFlow(palette, phase.value, compact)

            CapsuleBackgroundEffect.CAPSULE_STAR ->
                drawCapsuleStar(palette, phase.value, compact)

            CapsuleBackgroundEffect.AURORA ->
                drawAurora(palette, phase.value, compact)

            CapsuleBackgroundEffect.NEBULA ->
                drawNebula(palette, phase.value, compact)
        }
    }
}

private fun comfortableEffectColor(color: Color): Color {
    val maximum = max(color.red, max(color.green, color.blue))
    if (maximum <= 0.82f) return color
    val scale = 0.82f / maximum
    return Color(
        red = color.red * scale,
        green = color.green * scale,
        blue = color.blue * scale,
        alpha = color.alpha,
    )
}

private fun DrawScope.drawColorFlow(
    palette: List<Color>,
    phase: Float,
    compact: Boolean,
) {
    drawRect(Color(0xFF06070B))
    val angle = phase * 2f * PI.toFloat()
    val radius = max(size.width, size.height) * if (compact) 1.25f else 0.9f
    val centers =
        listOf(
            Offset(
                x = size.width * (0.22f + 0.18f * sin(angle)),
                y = size.height * (0.28f + 0.16f * cos(angle)),
            ),
            Offset(
                x = size.width * (0.78f + 0.16f * cos(angle * 0.8f)),
                y = size.height * (0.72f + 0.18f * sin(angle * 0.8f)),
            ),
            Offset(
                x = size.width * (0.5f + 0.22f * sin(angle + 2.1f)),
                y = size.height * (0.48f + 0.2f * cos(angle + 2.1f)),
            ),
        )

    palette.forEachIndexed { index, color ->
        drawRect(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            color.copy(alpha = if (compact) 0.62f else 0.74f),
                            color.copy(alpha = 0.22f),
                            Color.Transparent,
                        ),
                    center = centers[index],
                    radius = radius,
                ),
        )
    }
    drawRect(Color.Black.copy(alpha = if (compact) 0.12f else 0.2f))
}

private fun DrawScope.drawCapsuleStar(
    palette: List<Color>,
    phase: Float,
    compact: Boolean,
) {
    drawRect(
        brush =
            Brush.verticalGradient(
                listOf(Color(0xFF10152A), Color(0xFF03040A)),
            ),
    )
    drawRect(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        palette[0].copy(alpha = 0.42f),
                        palette[1].copy(alpha = 0.12f),
                        Color.Transparent,
                    ),
                center = Offset(size.width * 0.5f, size.height * 0.48f),
                radius = max(size.width, size.height) * 0.72f,
            ),
    )

    val starCount = if (compact) 18 else 52
    repeat(starCount) { index ->
        val x = deterministicFraction(index * 17 + 3) * size.width
        val y = deterministicFraction(index * 31 + 11) * size.height
        val twinkle =
            0.25f +
                0.65f *
                ((sin((phase * 2f * PI + index * 0.73).toFloat()) + 1f) / 2f)
        drawCircle(
            color = Color.White.copy(alpha = twinkle),
            radius =
                (if (index % 9 == 0) 1.9f else 1.05f) *
                    density,
            center = Offset(x, y),
        )
    }

    val center = Offset(size.width * 0.5f, size.height * 0.5f)
    val orbitWidth = size.width * if (compact) 0.64f else 0.42f
    val orbitHeight = size.height * if (compact) 0.52f else 0.18f
    rotate(phase * 360f, pivot = center) {
        drawOval(
            color = palette[1].copy(alpha = 0.38f),
            topLeft = Offset(center.x - orbitWidth / 2f, center.y - orbitHeight / 2f),
            size = Size(orbitWidth, orbitHeight),
            style = Stroke(width = if (compact) 1.15f * density else 1.7f * density),
        )
        drawCircle(
            color = palette[2].copy(alpha = 0.3f),
            radius = if (compact) 5f * density else 8f * density,
            center = Offset(center.x + orbitWidth / 2f, center.y),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = if (compact) 2.2f * density else 3.5f * density,
            center = Offset(center.x + orbitWidth / 2f, center.y),
        )
    }
    drawFourPointStar(
        center = center,
        outerRadius = minOf(size.width, size.height) * if (compact) 0.2f else 0.055f,
        color = Color.White.copy(alpha = 0.92f),
    )
}

private fun DrawScope.drawAurora(
    palette: List<Color>,
    phase: Float,
    compact: Boolean,
) {
    drawRect(
        brush =
            Brush.verticalGradient(
                listOf(Color(0xFF050A14), Color(0xFF07111C), Color(0xFF020407)),
            ),
    )
    val wave = phase * 2f * PI.toFloat()
    val bands = if (compact) 2 else 3
    repeat(bands) { index ->
        val baseline = size.height * (0.24f + index * 0.2f)
        val amplitude = size.height * (if (compact) 0.2f else 0.13f)
        val path =
            Path().apply {
                moveTo(-size.width * 0.1f, baseline)
                cubicTo(
                    size.width * 0.2f,
                    baseline + amplitude * sin(wave + index),
                    size.width * 0.68f,
                    baseline - amplitude * cos(wave * 0.7f + index),
                    size.width * 1.1f,
                    baseline + amplitude * sin(wave * 0.55f + index * 1.4f),
                )
            }
        drawPath(
            path = path,
            brush =
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        palette[index].copy(alpha = if (compact) 0.52f else 0.42f),
                        palette[(index + 1) % palette.size].copy(alpha = 0.34f),
                        Color.Transparent,
                    ),
                ),
            style =
                Stroke(
                    width = size.height * if (compact) 0.34f else 0.19f,
                ),
        )
    }
    drawRect(Color.Black.copy(alpha = 0.14f))
}

private fun DrawScope.drawNebula(
    palette: List<Color>,
    phase: Float,
    compact: Boolean,
) {
    drawRect(Color(0xFF05040A))
    val angle = phase * 2f * PI.toFloat()
    val radius = max(size.width, size.height) * if (compact) 1.1f else 0.74f
    val first =
        Offset(
            size.width * (0.3f + 0.08f * cos(angle)),
            size.height * (0.38f + 0.09f * sin(angle)),
        )
    val second =
        Offset(
            size.width * (0.72f + 0.07f * sin(angle)),
            size.height * (0.62f + 0.08f * cos(angle)),
        )
    drawRect(
        brush =
            Brush.radialGradient(
                listOf(palette[0].copy(alpha = 0.6f), Color.Transparent),
                center = first,
                radius = radius,
            ),
    )
    drawRect(
        brush =
            Brush.radialGradient(
                listOf(palette[2].copy(alpha = 0.5f), Color.Transparent),
                center = second,
                radius = radius,
            ),
    )
    repeat(if (compact) 9 else 24) { index ->
        drawCircle(
            color = Color.White.copy(alpha = 0.18f + (index % 4) * 0.08f),
            radius = (0.7f + index % 3 * 0.35f) * density,
            center =
                Offset(
                    deterministicFraction(index * 23 + 5) * size.width,
                    deterministicFraction(index * 41 + 7) * size.height,
                ),
        )
    }
    drawRect(Color.Black.copy(alpha = 0.18f))
}

private fun DrawScope.drawFourPointStar(
    center: Offset,
    outerRadius: Float,
    color: Color,
) {
    val innerRadius = outerRadius * 0.22f
    val path =
        Path().apply {
            moveTo(center.x, center.y - outerRadius)
            lineTo(center.x + innerRadius, center.y - innerRadius)
            lineTo(center.x + outerRadius, center.y)
            lineTo(center.x + innerRadius, center.y + innerRadius)
            lineTo(center.x, center.y + outerRadius)
            lineTo(center.x - innerRadius, center.y + innerRadius)
            lineTo(center.x - outerRadius, center.y)
            lineTo(center.x - innerRadius, center.y - innerRadius)
            close()
        }
    drawPath(path = path, color = color)
}

private fun deterministicFraction(seed: Int): Float =
    (abs(sin(seed * 12.9898)) * 43_758.5453).toFloat() % 1f
