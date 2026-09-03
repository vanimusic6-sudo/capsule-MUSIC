/*
 * Capsule MUSIC
 * Calm procedural backgrounds shared by the player, mini-player and dock.
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
    allowTransparency: Boolean = false,
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
            ).map(::capsuleMutedArtworkColor)
        }
    val animation = rememberInfiniteTransition(label = "capsuleBackground")
    val phase =
        animation.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(28_000, easing = LinearEasing),
                ),
            label = "capsuleBackgroundPhase",
        )

    Canvas(modifier = modifier) {
        when (effect) {
            CapsuleBackgroundEffect.COLOR_FLOW ->
                drawSoftColorFlow(
                    palette = palette,
                    phase = phase.value,
                    compact = compact,
                    allowTransparency = allowTransparency,
                )

            CapsuleBackgroundEffect.CAPSULE_STAR ->
                drawCapsuleStarField(
                    palette = palette,
                    phase = phase.value,
                    compact = compact,
                    allowTransparency = allowTransparency,
                )

            CapsuleBackgroundEffect.AURORA ->
                drawSoftAurora(
                    palette = palette,
                    phase = phase.value,
                    compact = compact,
                    allowTransparency = allowTransparency,
                )

            CapsuleBackgroundEffect.NEBULA ->
                drawArtworkNebula(
                    palette = palette,
                    phase = phase.value,
                    compact = compact,
                    allowTransparency = allowTransparency,
                )
        }
    }
}

/** Reduce saturation and cap brightness before a cover colour reaches UI. */
internal fun capsuleMutedArtworkColor(color: Color): Color {
    val luminance =
        color.red * 0.2126f +
            color.green * 0.7152f +
            color.blue * 0.0722f
    val gray = Color(luminance, luminance, luminance, color.alpha)
    val desaturated = lerp(color, gray, 0.28f)
    val softened = lerp(desaturated, Color(0xFFE6E1EA), 0.1f)
    val maximum = max(softened.red, max(softened.green, softened.blue))
    if (maximum <= 0.76f) return softened

    val scale = 0.76f / maximum
    return Color(
        red = softened.red * scale,
        green = softened.green * scale,
        blue = softened.blue * scale,
        alpha = softened.alpha,
    )
}

/** Shared edge and selected-tab colours keep the mini-player and dock related. */
internal fun capsuleSurfaceOutline(colors: List<Color>): Color {
    val accent =
        capsuleMutedArtworkColor(
            colors.firstOrNull() ?: Color(0xFF6F7180),
        )
    return lerp(Color(0xFF353640), deepColor(accent, 0.48f), 0.24f)
}

internal fun capsuleDockIndicatorColor(colors: List<Color>): Color {
    val accent =
        capsuleMutedArtworkColor(
            colors.firstOrNull() ?: Color(0xFFAAA7B3),
        )
    return lerp(Color(0xFFE4E2E8), accent, 0.18f)
}

private fun deepColor(
    color: Color,
    amount: Float,
): Color = lerp(color, Color(0xFF03040A), amount.coerceIn(0f, 1f))

private fun DrawScope.drawSoftColorFlow(
    palette: List<Color>,
    phase: Float,
    compact: Boolean,
    allowTransparency: Boolean,
) {
    val baseAlpha = if (compact && allowTransparency) 0.84f else 1f
    val base = deepColor(lerp(palette[0], palette[1], 0.34f), 0.54f)
    drawRect(base.copy(alpha = baseAlpha))
    drawRect(
        brush =
            Brush.linearGradient(
                colors =
                    listOf(
                        deepColor(palette[0], 0.42f).copy(alpha = 0.62f),
                        deepColor(palette[1], 0.5f).copy(alpha = 0.48f),
                        deepColor(palette[2], 0.58f).copy(alpha = 0.56f),
                    ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
    )

    val angle = phase * 2f * PI.toFloat()
    val radius = max(size.width, size.height) * if (compact) 1.15f else 0.78f
    val centers =
        listOf(
            Offset(
                size.width * (0.18f + 0.15f * sin(angle)),
                size.height * (0.32f + 0.12f * cos(angle * 0.72f)),
            ),
            Offset(
                size.width * (0.82f + 0.13f * cos(angle * 0.62f)),
                size.height * (0.66f + 0.14f * sin(angle * 0.66f)),
            ),
            Offset(
                size.width * (0.5f + 0.2f * sin(angle * 0.48f + 2.2f)),
                size.height * (0.48f + 0.15f * cos(angle * 0.52f + 1.4f)),
            ),
        )

    palette.forEachIndexed { index, color ->
        val pastel = lerp(color, Color(0xFFE4DFE8), 0.2f)
        drawRect(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            pastel.copy(alpha = if (compact) 0.2f else 0.3f),
                            pastel.copy(alpha = if (compact) 0.09f else 0.13f),
                            Color.Transparent,
                        ),
                    center = centers[index],
                    radius = radius,
                ),
        )
    }

    /* A quiet veil, not a glass/blur layer. */
    drawRect(Color(0xFF070810).copy(alpha = if (compact) 0.08f else 0.14f))
}

private fun DrawScope.drawCapsuleStarField(
    palette: List<Color>,
    phase: Float,
    compact: Boolean,
    allowTransparency: Boolean,
) {
    val baseAlpha = if (compact && allowTransparency) 0.88f else 1f
    val top = deepColor(lerp(palette[0], palette[1], 0.22f), 0.62f)
    val bottom = deepColor(lerp(palette[1], palette[2], 0.44f), 0.78f)
    drawRect(
        brush =
            Brush.verticalGradient(
                listOf(
                    top.copy(alpha = baseAlpha),
                    deepColor(palette[0], 0.74f).copy(alpha = baseAlpha),
                    bottom.copy(alpha = baseAlpha),
                ),
            ),
    )

    val angle = phase * 2f * PI.toFloat()
    drawRect(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        palette[0].copy(alpha = if (compact) 0.2f else 0.28f),
                        palette[1].copy(alpha = if (compact) 0.07f else 0.11f),
                        Color.Transparent,
                    ),
                center =
                    Offset(
                        size.width * (0.48f + 0.12f * sin(angle * 0.42f)),
                        size.height * (0.42f + 0.1f * cos(angle * 0.38f)),
                    ),
                radius = max(size.width, size.height) * if (compact) 1.1f else 0.68f,
            ),
    )

    val starCount = if (compact) 26 else 76
    repeat(starCount) { index ->
        val depth = 0.42f + deterministicFraction(index * 37 + 9) * 0.58f
        val drift = (depth - 0.4f) * if (compact) 1.2f * density else 2.6f * density
        val x =
            deterministicFraction(index * 17 + 3) * size.width +
                sin(angle * (0.22f + depth * 0.15f) + index) * drift
        val y =
            deterministicFraction(index * 31 + 11) * size.height +
                cos(angle * (0.18f + depth * 0.12f) + index * 0.7f) * drift
        val twinkle =
            0.18f +
                depth * 0.34f +
                0.2f *
                ((sin(angle * (0.65f + depth) + index * 0.73f) + 1f) / 2f)
        val radius =
            (if (index % 13 == 0) 1.55f else 0.72f + depth * 0.38f) * density

        if (index % 13 == 0) {
            drawCircle(
                color = palette[index % palette.size].copy(alpha = twinkle * 0.2f),
                radius = radius * 3.2f,
                center = Offset(x, y),
            )
        }
        drawCircle(
            color = Color.White.copy(alpha = twinkle.coerceAtMost(0.78f)),
            radius = radius,
            center = Offset(x, y),
        )
    }

    repeat(if (compact) 1 else 3) { index ->
        drawFallingComet(
            palette = palette,
            phase = phase,
            index = index,
            compact = compact,
        )
    }

    drawRect(Color.Black.copy(alpha = if (compact) 0.05f else 0.1f))
}

private fun DrawScope.drawFallingComet(
    palette: List<Color>,
    phase: Float,
    index: Int,
    compact: Boolean,
) {
    val offset = deterministicFraction(index * 71 + 19)
    val progress = (phase * (1.12f + index * 0.19f) + offset) % 1f
    val fade = sin(progress * PI).toFloat().coerceAtLeast(0f)
    if (fade < 0.06f) return

    val startX = size.width * (0.42f + deterministicFraction(index * 43 + 7) * 0.72f)
    val head =
        Offset(
            x = startX - progress * size.width * (if (compact) 0.76f else 0.92f),
            y = -size.height * 0.2f + progress * size.height * 1.4f,
        )
    val trailLength =
        minOf(size.width, size.height) * if (compact) 0.48f else 0.16f
    val direction = Offset(0.74f, -0.67f)
    val segments = if (compact) 7 else 11
    val cometColor = lerp(palette[index % palette.size], Color.White, 0.56f)

    repeat(segments) { segment ->
        val fromFraction = segment.toFloat() / segments
        val toFraction = (segment + 1f) / segments
        val alpha = fade * (1f - fromFraction) * (if (compact) 0.34f else 0.42f)
        drawLine(
            color = cometColor.copy(alpha = alpha),
            start = head + direction * (trailLength * toFraction),
            end = head + direction * (trailLength * fromFraction),
            strokeWidth =
                (if (segment == 0) 1.8f else 1.15f) * density,
            cap = StrokeCap.Round,
        )
    }

    drawCircle(
        color = cometColor.copy(alpha = fade * 0.16f),
        radius = if (compact) 5f * density else 7f * density,
        center = head,
    )
    drawCircle(
        color = Color.White.copy(alpha = fade * 0.86f),
        radius = if (compact) 1.45f * density else 2f * density,
        center = head,
    )
}

private fun DrawScope.drawSoftAurora(
    palette: List<Color>,
    phase: Float,
    compact: Boolean,
    allowTransparency: Boolean,
) {
    val baseAlpha = if (compact && allowTransparency) 0.86f else 1f
    drawRect(deepColor(palette[0], 0.78f).copy(alpha = baseAlpha))
    val wave = phase * 2f * PI.toFloat()
    val bands = if (compact) 2 else 3

    repeat(bands) { index ->
        val baseline = size.height * (0.24f + index * 0.22f)
        val amplitude = size.height * (if (compact) 0.16f else 0.11f)
        val path =
            Path().apply {
                moveTo(-size.width * 0.12f, baseline)
                cubicTo(
                    size.width * 0.2f,
                    baseline + amplitude * sin(wave * 0.55f + index),
                    size.width * 0.68f,
                    baseline - amplitude * cos(wave * 0.42f + index),
                    size.width * 1.12f,
                    baseline + amplitude * sin(wave * 0.36f + index * 1.4f),
                )
            }
        drawPath(
            path = path,
            brush =
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        palette[index].copy(alpha = if (compact) 0.22f else 0.28f),
                        palette[(index + 1) % palette.size].copy(alpha = 0.18f),
                        Color.Transparent,
                    ),
                ),
            style =
                Stroke(
                    width = size.height * if (compact) 0.3f else 0.17f,
                ),
        )
    }
    drawRect(Color.Black.copy(alpha = 0.16f))
}

private fun DrawScope.drawArtworkNebula(
    palette: List<Color>,
    phase: Float,
    compact: Boolean,
    allowTransparency: Boolean,
) {
    val baseAlpha = if (compact && allowTransparency) 0.88f else 1f
    val base = deepColor(lerp(palette[0], palette[1], 0.28f), 0.76f)
    drawRect(base.copy(alpha = baseAlpha))

    val angle = phase * 2f * PI.toFloat()
    val radius = max(size.width, size.height) * if (compact) 1.08f else 0.68f
    val centers =
        listOf(
            Offset(
                size.width * (0.24f + 0.07f * cos(angle * 0.42f)),
                size.height * (0.34f + 0.08f * sin(angle * 0.38f)),
            ),
            Offset(
                size.width * (0.76f + 0.08f * sin(angle * 0.34f + 1.8f)),
                size.height * (0.62f + 0.07f * cos(angle * 0.31f + 1.2f)),
            ),
            Offset(
                size.width * (0.52f + 0.12f * cos(angle * 0.27f + 3.1f)),
                size.height * (0.46f + 0.09f * sin(angle * 0.29f + 2.4f)),
            ),
        )

    centers.forEachIndexed { index, center ->
        val cloud = deepColor(palette[index], if (compact) 0.25f else 0.18f)
        drawRect(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            cloud.copy(alpha = if (compact) 0.34f else 0.46f),
                            cloud.copy(alpha = if (compact) 0.15f else 0.2f),
                            Color.Transparent,
                        ),
                    center = center,
                    radius = radius * (0.88f + index * 0.08f),
                ),
        )
    }

    /* Dark negative space keeps the nebula deep instead of neon. */
    drawRect(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        Color.Transparent,
                        Color(0xFF010207).copy(alpha = 0.28f),
                    ),
                center = Offset(size.width * 0.5f, size.height * 0.48f),
                radius = minOf(size.width, size.height) * if (compact) 1.2f else 0.72f,
            ),
    )

    repeat(if (compact) 16 else 42) { index ->
        val twinkle =
            0.12f +
                0.2f *
                ((sin(angle * 0.52f + index * 0.91f) + 1f) / 2f)
        drawCircle(
            color = Color.White.copy(alpha = twinkle),
            radius = (0.55f + index % 4 * 0.2f) * density,
            center =
                Offset(
                    deterministicFraction(index * 23 + 5) * size.width,
                    deterministicFraction(index * 41 + 7) * size.height,
                ),
        )
    }
    drawRect(Color.Black.copy(alpha = if (compact) 0.08f else 0.13f))
}

private fun deterministicFraction(seed: Int): Float =
    (abs(sin(seed * 12.9898)) * 43_758.5453).toFloat() % 1f
