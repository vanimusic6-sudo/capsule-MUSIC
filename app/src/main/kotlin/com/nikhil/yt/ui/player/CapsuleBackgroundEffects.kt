/*
 * Capsule MUSIC
 * Calm procedural backgrounds shared by the player, mini-player and dock.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal enum class CapsuleBackgroundEffect {
    MATTE_GRADIENT,
    TONAL_WASH,
    AMBIENT_GLOW,
    COLOR_FLOW,
    CAPSULE_STAR,
    NEBULA,
}

private const val BACKGROUND_CYCLE_MS = 36_000
private const val COMPACT_BACKGROUND_FPS = 15
private const val FULL_BACKGROUND_FPS = 24
private const val STATIC_BACKGROUND_PHASE = 0.18f

/**
 * Drive the slow effects at their actual target frame rate and stop the clock
 * while the Activity is not visible. An InfiniteTransition still wakes up at
 * the display refresh rate even when its derived value changes less often.
 */
@Composable
private fun rememberCapsuleAnimationPhase(compact: Boolean): State<Float> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val phase = remember { mutableFloatStateOf(STATIC_BACKGROUND_PHASE) }
    var isVisible by
        remember(lifecycleOwner) {
            mutableStateOf(
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            )
        }
    val framesPerSecond =
        if (compact) COMPACT_BACKGROUND_FPS else FULL_BACKGROUND_FPS
    val frameDelayMs = 1_000L / framesPerSecond

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, _ ->
                isVisible =
                    lifecycleOwner.lifecycle.currentState
                        .isAtLeast(Lifecycle.State.STARTED)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(compact, isVisible) {
        if (!isVisible) return@LaunchedEffect

        while (isActive) {
            phase.floatValue =
                (SystemClock.elapsedRealtime() % BACKGROUND_CYCLE_MS).toFloat() /
                    BACKGROUND_CYCLE_MS.toFloat()
            delay(frameDelayMs)
        }
    }

    return phase
}

@Composable
internal fun CapsuleProceduralBackground(
    effect: CapsuleBackgroundEffect,
    modifier: Modifier = Modifier,
    colors: List<Color> = emptyList(),
    compact: Boolean = false,
    animated: Boolean = true,
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
    val phase =
        if (animated) {
            rememberCapsuleAnimationPhase(compact = compact)
        } else {
            null
        }

    Canvas(modifier = modifier) {
        val phaseValue = phase?.value ?: STATIC_BACKGROUND_PHASE
        when (effect) {
            CapsuleBackgroundEffect.MATTE_GRADIENT ->
                drawMatteGradient(palette)

            CapsuleBackgroundEffect.TONAL_WASH ->
                drawTonalWash(palette)

            CapsuleBackgroundEffect.AMBIENT_GLOW ->
                drawAmbientGlow(palette)

            CapsuleBackgroundEffect.COLOR_FLOW ->
                drawSoftColorFlow(
                    palette = palette,
                    phase = phaseValue,
                    compact = compact,
                )

            CapsuleBackgroundEffect.CAPSULE_STAR ->
                drawCapsuleStarField(
                    palette = palette,
                    phase = phaseValue,
                    compact = compact,
                )

            CapsuleBackgroundEffect.NEBULA ->
                drawArtworkNebula(
                    palette = palette,
                    phase = phaseValue,
                    compact = compact,
                )
        }
    }
}

/** Dedicated neutral translucent option. Every other compact style stays opaque. */
@Composable
internal fun CapsuleGlassSurface(
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    // Keep the public signature shared by the player/dock, but deliberately do
    // not use artwork colours here. GLASS must remain the same neutral panel for
    // every song instead of changing tint whenever the cover changes.
    @Suppress("UNUSED_VARIABLE")
    val ignoredArtworkColors = colors

    Canvas(modifier = modifier) {
        drawRect(Color(0xB80A0B10))
        drawRect(
            brush =
                Brush.linearGradient(
                    colors =
                        listOf(
                            Color.White.copy(alpha = 0.055f),
                            Color(0xFF15161D).copy(alpha = 0.28f),
                            Color.Black.copy(alpha = 0.2f),
                        ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
        )
        drawRect(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            Color.White.copy(alpha = 0.095f),
                            Color.White.copy(alpha = 0.025f),
                            Color.Transparent,
                        ),
                    center = Offset(size.width * 0.16f, 0f),
                    radius = max(size.width, size.height) * 0.8f,
                ),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.17f),
            start = Offset(size.width * 0.08f, 0.7f * density),
            end = Offset(size.width * 0.92f, 0.7f * density),
            strokeWidth = 0.7f * density,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.24f),
            start = Offset(size.width * 0.1f, size.height - 0.7f * density),
            end = Offset(size.width * 0.9f, size.height - 0.7f * density),
            strokeWidth = 0.7f * density,
            cap = StrokeCap.Round,
        )
    }
}

/** Reduce saturation and cap brightness before a cover colour reaches UI. */
internal fun capsuleMutedArtworkColor(color: Color): Color {
    val luminance =
        color.red * 0.2126f +
            color.green * 0.7152f +
            color.blue * 0.0722f
    val gray = Color(luminance, luminance, luminance, color.alpha)
    val desaturated = lerp(color, gray, 0.3f)
    val softened = lerp(desaturated, Color(0xFFE5E0E8), 0.08f)
    val maximum = max(softened.red, max(softened.green, softened.blue))
    if (maximum <= 0.74f) return softened

    val scale = 0.74f / maximum
    return Color(
        red = softened.red * scale,
        green = softened.green * scale,
        blue = softened.blue * scale,
        alpha = softened.alpha,
    )
}

internal fun capsuleSurfaceOutline(
    colors: List<Color>,
    glass: Boolean = false,
): Color {
    if (glass) {
        return Color.White.copy(alpha = 0.24f)
    }

    val accent =
        capsuleMutedArtworkColor(
            colors.firstOrNull() ?: Color(0xFF6F7180),
        )
    return lerp(Color(0xFF353640), deepColor(accent, 0.48f), 0.24f)
}

internal fun capsuleDockIndicatorColor(
    colors: List<Color>,
    glass: Boolean = false,
): Color {
    if (glass) {
        return Color(0xFFE4E2E8).copy(alpha = 0.88f)
    }

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

private fun DrawScope.drawMatteGradient(palette: List<Color>) {
    val top = deepColor(lerp(palette[0], palette[1], 0.16f), 0.48f)
    val middle = deepColor(lerp(palette[0], palette[2], 0.46f), 0.6f)
    val bottom = deepColor(lerp(palette[1], palette[2], 0.58f), 0.76f)

    drawRect(
        brush =
            Brush.verticalGradient(
                0f to top,
                0.48f to middle,
                1f to bottom,
            ),
    )
    drawRect(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        palette[0].copy(alpha = 0.2f),
                        Color.Transparent,
                    ),
                center = Offset(size.width * 0.12f, size.height * 0.08f),
                radius = max(size.width, size.height) * 0.82f,
            ),
    )
    drawRect(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        palette[2].copy(alpha = 0.11f),
                        Color.Transparent,
                    ),
                center = Offset(size.width * 0.9f, size.height * 0.86f),
                radius = max(size.width, size.height) * 0.7f,
            ),
    )
    drawVignette(alpha = 0.24f)
}

private fun DrawScope.drawTonalWash(palette: List<Color>) {
    val base = deepColor(lerp(palette[0], palette[1], 0.24f), 0.54f)
    val highlight = deepColor(lerp(palette[0], Color.White, 0.08f), 0.48f)
    val shadow = deepColor(lerp(palette[0], palette[2], 0.5f), 0.76f)

    drawRect(base)
    drawRect(
        brush =
            Brush.linearGradient(
                colors = listOf(highlight, base, shadow),
                start = Offset(size.width * 0.08f, 0f),
                end = Offset(size.width * 0.9f, size.height),
            ),
    )
    drawRect(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        palette[1].copy(alpha = 0.12f),
                        Color.Transparent,
                    ),
                center = Offset(size.width * 0.74f, size.height * 0.24f),
                radius = max(size.width, size.height) * 0.64f,
            ),
    )
    drawVignette(alpha = 0.2f)
}

private fun DrawScope.drawAmbientGlow(palette: List<Color>) {
    drawRect(Color(0xFF05060A))
    val centers =
        listOf(
            Offset(size.width * 0.08f, size.height * 0.18f),
            Offset(size.width * 0.92f, size.height * 0.74f),
            Offset(size.width * 0.58f, size.height * 0.04f),
        )
    val radius = max(size.width, size.height) * 0.78f

    centers.forEachIndexed { index, center ->
        val glow = deepColor(palette[index], 0.2f)
        drawRect(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            glow.copy(alpha = 0.52f),
                            glow.copy(alpha = 0.2f),
                            Color.Transparent,
                        ),
                    center = center,
                    radius = radius * (0.86f + index * 0.08f),
                ),
        )
    }
    drawVignette(alpha = 0.3f)
}

private fun DrawScope.drawSoftColorFlow(
    palette: List<Color>,
    phase: Float,
    compact: Boolean,
) {
    val base = deepColor(lerp(palette[0], palette[1], 0.34f), 0.56f)
    drawRect(base)
    drawRect(
        brush =
            Brush.linearGradient(
                colors =
                    listOf(
                        deepColor(palette[0], 0.44f),
                        deepColor(palette[1], 0.56f),
                        deepColor(palette[2], 0.64f),
                    ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
    )

    val angle = phase * 2f * PI.toFloat()
    val radius = max(size.width, size.height) * if (compact) 1.12f else 0.76f
    val centers =
        listOf(
            Offset(
                size.width * (0.16f + 0.14f * sin(angle)),
                size.height * (0.28f + 0.1f * cos(angle * 0.72f)),
            ),
            Offset(
                size.width * (0.84f + 0.11f * cos(angle * 0.62f)),
                size.height * (0.7f + 0.12f * sin(angle * 0.66f)),
            ),
            Offset(
                size.width * (0.5f + 0.18f * sin(angle * 0.48f + 2.2f)),
                size.height * (0.46f + 0.13f * cos(angle * 0.52f + 1.4f)),
            ),
        )

    palette.forEachIndexed { index, color ->
        val pastel = lerp(color, Color(0xFFE2DDE6), 0.16f)
        drawRect(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            pastel.copy(alpha = if (compact) 0.2f else 0.28f),
                            pastel.copy(alpha = if (compact) 0.07f else 0.12f),
                            Color.Transparent,
                        ),
                    center = centers[index],
                    radius = radius,
                ),
        )
    }

    drawVignette(alpha = if (compact) 0.12f else 0.2f)
}

private fun DrawScope.drawCapsuleStarField(
    palette: List<Color>,
    phase: Float,
    compact: Boolean,
) {
    val top = deepColor(lerp(palette[0], palette[1], 0.18f), 0.66f)
    val center = deepColor(lerp(palette[0], palette[2], 0.45f), 0.76f)
    val bottom = deepColor(lerp(palette[1], palette[2], 0.58f), 0.86f)
    drawRect(
        brush =
            Brush.verticalGradient(
                0f to top,
                0.54f to center,
                1f to bottom,
            ),
    )

    val angle = phase * 2f * PI.toFloat()
    drawRect(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        palette[0].copy(alpha = if (compact) 0.16f else 0.23f),
                        palette[1].copy(alpha = if (compact) 0.05f else 0.09f),
                        Color.Transparent,
                    ),
                center =
                    Offset(
                        size.width * (0.44f + 0.1f * sin(angle * 0.34f)),
                        size.height * (0.38f + 0.08f * cos(angle * 0.3f)),
                    ),
                radius = max(size.width, size.height) * if (compact) 1.14f else 0.7f,
            ),
    )
    drawRect(
        brush =
            Brush.linearGradient(
                colors =
                    listOf(
                        Color.Transparent,
                        palette[2].copy(alpha = if (compact) 0.07f else 0.1f),
                        Color.Transparent,
                    ),
                start = Offset(-size.width * 0.12f, size.height * 0.92f),
                end = Offset(size.width * 1.12f, size.height * 0.08f),
            ),
    )

    val starCount = if (compact) 30 else 82
    repeat(starCount) { index ->
        val depth = 0.36f + deterministicFraction(index * 37 + 9) * 0.64f
        val drift = (depth - 0.32f) * if (compact) 1.1f * density else 2.4f * density
        val rawX =
            deterministicFraction(index * 17 + 3) * size.width +
                sin(angle * (0.18f + depth * 0.14f) + index) * drift
        val rawY =
            deterministicFraction(index * 31 + 11) * size.height +
                cos(angle * (0.15f + depth * 0.11f) + index * 0.7f) * drift
        val x = wrapCoordinate(rawX, size.width)
        val y = wrapCoordinate(rawY, size.height)
        val pulse =
            (sin(angle * (0.48f + depth * 0.74f) + index * 0.73f) + 1f) / 2f
        val alpha = (0.2f + depth * 0.32f + pulse * 0.16f).coerceAtMost(0.76f)
        val radius =
            (if (index % 17 == 0) 1.42f else 0.56f + depth * 0.42f) * density
        val starColor = lerp(Color.White, palette[index % palette.size], 0.15f)

        if (index % 17 == 0) {
            drawCircle(
                color = palette[index % palette.size].copy(alpha = alpha * 0.13f),
                radius = radius * 4.4f,
                center = Offset(x, y),
            )
            drawLine(
                color = starColor.copy(alpha = alpha * 0.38f),
                start = Offset(x - radius * 2.2f, y),
                end = Offset(x + radius * 2.2f, y),
                strokeWidth = 0.45f * density,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = starColor.copy(alpha = alpha * 0.3f),
                start = Offset(x, y - radius * 2.2f),
                end = Offset(x, y + radius * 2.2f),
                strokeWidth = 0.45f * density,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(
            color = starColor.copy(alpha = alpha),
            radius = radius,
            center = Offset(x, y),
        )
    }

    drawVignette(alpha = if (compact) 0.16f else 0.28f)
}

private fun DrawScope.drawArtworkNebula(
    palette: List<Color>,
    phase: Float,
    compact: Boolean,
) {
    val base = deepColor(lerp(palette[0], palette[1], 0.28f), 0.8f)
    drawRect(base)

    val angle = phase * 2f * PI.toFloat()
    val radius = max(size.width, size.height) * if (compact) 1.06f else 0.66f
    val centers =
        listOf(
            Offset(
                size.width * (0.22f + 0.06f * cos(angle * 0.36f)),
                size.height * (0.32f + 0.07f * sin(angle * 0.32f)),
            ),
            Offset(
                size.width * (0.78f + 0.07f * sin(angle * 0.3f + 1.8f)),
                size.height * (0.66f + 0.06f * cos(angle * 0.28f + 1.2f)),
            ),
            Offset(
                size.width * (0.5f + 0.1f * cos(angle * 0.24f + 3.1f)),
                size.height * (0.46f + 0.08f * sin(angle * 0.26f + 2.4f)),
            ),
        )

    centers.forEachIndexed { index, cloudCenter ->
        val cloud = deepColor(palette[index], if (compact) 0.32f else 0.24f)
        drawRect(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            cloud.copy(alpha = if (compact) 0.3f else 0.42f),
                            cloud.copy(alpha = if (compact) 0.12f else 0.18f),
                            Color.Transparent,
                        ),
                    center = cloudCenter,
                    radius = radius * (0.86f + index * 0.08f),
                ),
        )
    }

    drawRect(
        brush =
            Brush.linearGradient(
                colors =
                    listOf(
                        Color.Transparent,
                        palette[1].copy(alpha = if (compact) 0.08f else 0.13f),
                        Color.Transparent,
                    ),
                start = Offset(-size.width * 0.08f, size.height * 0.86f),
                end = Offset(size.width * 1.08f, size.height * 0.16f),
            ),
    )

    repeat(if (compact) 14 else 38) { index ->
        val twinkle =
            0.1f +
                0.16f *
                ((sin(angle * 0.44f + index * 0.91f) + 1f) / 2f)
        drawCircle(
            color = lerp(Color.White, palette[index % palette.size], 0.12f).copy(alpha = twinkle),
            radius = (0.48f + index % 4 * 0.18f) * density,
            center =
                Offset(
                    deterministicFraction(index * 23 + 5) * size.width,
                    deterministicFraction(index * 41 + 7) * size.height,
                ),
        )
    }
    drawVignette(alpha = if (compact) 0.2f else 0.32f)
}

private fun DrawScope.drawVignette(alpha: Float) {
    drawRect(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        Color.Transparent,
                        Color.Transparent,
                        Color.Black.copy(alpha = alpha),
                    ),
                center = Offset(size.width * 0.5f, size.height * 0.46f),
                radius = max(size.width, size.height) * 0.72f,
            ),
    )
}

private fun wrapCoordinate(
    value: Float,
    maximum: Float,
): Float {
    if (maximum <= 0f) return 0f
    val wrapped = value % maximum
    return if (wrapped < 0f) wrapped + maximum else wrapped
}

private fun deterministicFraction(seed: Int): Float =
    (abs(sin(seed * 12.9898)) * 43_758.5453).toFloat() % 1f
