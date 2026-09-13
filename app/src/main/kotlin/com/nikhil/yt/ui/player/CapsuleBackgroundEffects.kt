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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    CAPSULE_GLOW,
}

private const val COMPACT_BACKGROUND_FPS = 15
private const val FULL_BACKGROUND_FPS = 24
private const val STATIC_BACKGROUND_TIME_MS = 6_480L

/**
 * Drive the slow effects at their actual target frame rate and stop the clock
 * while the Activity is not visible. An InfiniteTransition still wakes up at
 * the display refresh rate even when its derived value changes less often.
 */
@Composable
private fun rememberCapsuleAnimationTime(compact: Boolean): State<Long> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val time = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
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
            time.longValue = SystemClock.elapsedRealtime()
            delay(frameDelayMs)
        }
    }

    return time
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
    val time =
        if (animated) {
            rememberCapsuleAnimationTime(compact = compact)
        } else {
            null
        }

    val starFields = remember(compact) { CapsuleStarFields(if (compact) 22 else 64) }
    Canvas(modifier = modifier) {
        val elapsedMs = time?.value ?: STATIC_BACKGROUND_TIME_MS
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
                    elapsedMs = elapsedMs,
                    compact = compact,
                )

            CapsuleBackgroundEffect.CAPSULE_STAR ->
                drawCapsuleStarField(
                    palette = palette,
                    elapsedMs = elapsedMs,
                    compact = compact,
                    fields = starFields,
                )

            CapsuleBackgroundEffect.CAPSULE_GLOW ->
                drawCapsuleGlow(palette, compact)

            CapsuleBackgroundEffect.NEBULA ->
                drawArtworkNebula(
                    palette = palette,
                    elapsedMs = elapsedMs,
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
                            Color.White.copy(alpha = 0.045f),
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
                            Color.White.copy(alpha = 0.065f),
                            Color.White.copy(alpha = 0.025f),
                            Color.Transparent,
                        ),
                    center = Offset(size.width * 0.16f, 0f),
                    radius = max(size.width, size.height) * 0.8f,
                ),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.1f),
            start = Offset(size.width * 0.08f, 0.7f * density),
            end = Offset(size.width * 0.92f, 0.7f * density),
            strokeWidth = 0.7f * density,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.18f),
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

/** Diffuse elliptical light; no blur bitmap, texture uploads or per-frame palette extraction. */
private fun DrawScope.drawSoftLight(
    color: Color,
    center: Offset,
    radius: Float,
    opacity: Float,
    verticalScale: Float = 1f,
) {
    val safeRadius = radius.coerceAtLeast(1f)
    scale(scaleX = 1f, scaleY = verticalScale, pivot = center) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = opacity),
                0.34f to color.copy(alpha = opacity * 0.6f),
                0.7f to color.copy(alpha = opacity * 0.16f),
                1f to Color.Transparent,
                center = center, radius = safeRadius,
            ),
            radius = safeRadius, center = center,
        )
    }
}

private fun DrawScope.drawMatteGradient(palette: List<Color>) {
    val extent = max(size.width, size.height)
    val compact = size.width > size.height * 2f
    drawRect(Brush.verticalGradient(
        0f to deepColor(lerp(palette[0], palette[1], 0.12f), 0.5f),
        0.36f to deepColor(lerp(palette[0], palette[2], 0.28f), 0.62f),
        0.74f to deepColor(lerp(palette[1], palette[2], 0.4f), 0.78f),
        1f to Color(0xFF09090D),
    ))
    drawSoftLight(palette[0], Offset(size.width * 0.18f, size.height * 0.04f),
        extent * 0.82f, 0.22f, if (compact) 0.32f else 0.9f)
    drawSoftLight(palette[2], Offset(size.width * 0.96f, size.height * 0.46f),
        extent * 0.62f, 0.12f, if (compact) 0.35f else 1.1f)
    drawVignette(if (compact) 0.12f else 0.24f)
}

private fun DrawScope.drawTonalWash(palette: List<Color>) {
    val compact = size.width > size.height * 2f
    val extent = max(size.width, size.height)
    drawRect(Brush.linearGradient(
        0f to deepColor(palette[0], 0.52f),
        0.46f to deepColor(lerp(palette[0], palette[1], 0.22f), 0.66f),
        1f to deepColor(lerp(palette[0], palette[2], 0.38f), 0.84f),
        start = Offset(size.width * 0.08f, 0f), end = Offset(size.width, size.height),
    ))
    drawSoftLight(lerp(palette[0], Color.White, 0.12f),
        Offset(size.width * 0.1f, -size.height * 0.12f), extent * 0.94f, 0.18f,
        if (compact) 0.34f else 1f)
    drawSoftLight(palette[1], Offset(size.width * 0.88f, size.height * 0.55f),
        extent * 0.54f, 0.1f, if (compact) 0.3f else 1.1f)
    drawVignette(if (compact) 0.1f else 0.22f)
}

private fun DrawScope.drawAmbientGlow(palette: List<Color>) {
    val compact = size.width > size.height * 2f
    val extent = max(size.width, size.height)
    drawRect(Color(0xFF07080C))
    drawSoftLight(palette[0], Offset(size.width * 0.12f, size.height * 0.02f),
        extent * 0.82f, 0.58f, if (compact) 0.34f else 1.05f)
    drawSoftLight(palette[1], Offset(size.width * 0.94f, size.height * 0.48f),
        extent * 0.64f, 0.3f, if (compact) 0.38f else 1.15f)
    drawSoftLight(palette[2], Offset(size.width * 0.3f, size.height * 0.72f),
        extent * 0.58f, 0.12f, if (compact) 0.3f else 0.9f)
    drawRect(Brush.verticalGradient(
        0f to Color.Transparent, 0.42f to Color.Transparent,
        1f to Color.Black.copy(alpha = if (compact) 0.24f else 0.65f),
    ))
    drawVignette(if (compact) 0.12f else 0.22f)
}

private fun DrawScope.drawSoftColorFlow(
    palette: List<Color>,
    elapsedMs: Long,
    compact: Boolean,
) {
    val angle = capsuleBackgroundAngle(elapsedMs) * 0.36
    val extent = max(size.width, size.height)
    drawRect(Brush.linearGradient(
        0f to deepColor(lerp(palette[0], palette[1], 0.18f), 0.58f),
        0.52f to deepColor(lerp(palette[1], palette[2], 0.35f), 0.7f),
        1f to deepColor(palette[2], 0.84f),
        start = Offset.Zero, end = Offset(size.width, size.height),
    ))
    val centers = listOf(
        Offset(size.width * (0.12f + 0.13f * waveSin(angle)),
            size.height * (0.12f + 0.09f * waveCos(angle * 0.72))),
        Offset(size.width * (0.88f + 0.11f * waveCos(angle * 0.62)),
            size.height * (0.52f + 0.12f * waveSin(angle * 0.66))),
        Offset(size.width * (0.48f + 0.15f * waveSin(angle * 0.48 + 2.2)),
            size.height * (0.82f + 0.09f * waveCos(angle * 0.52 + 1.4))),
    )
    palette.forEachIndexed { index, color ->
        drawSoftLight(
            color, centers[index], extent * (0.78f - index * 0.08f),
            if (index == 0) 0.35f else 0.2f,
            if (compact) 0.34f else 1.1f,
        )
    }
    drawVignette(if (compact) 0.14f else 0.26f)
}

private fun DrawScope.drawCapsuleStarField(
    palette: List<Color>,
    elapsedMs: Long,
    compact: Boolean,
    fields: CapsuleStarFields,
) {
    val top = deepColor(lerp(palette[0], palette[1], 0.18f), 0.72f)
    val center = deepColor(lerp(palette[0], palette[2], 0.45f), 0.82f)
    val bottom = deepColor(lerp(palette[1], palette[2], 0.58f), 0.9f)
    drawRect(
        brush =
            Brush.verticalGradient(
                0f to top,
                0.54f to center,
                1f to bottom,
            ),
    )

    val angle = capsuleBackgroundAngle(elapsedMs)
    drawRect(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        palette[0].copy(alpha = if (compact) 0.14f else 0.2f),
                        palette[1].copy(alpha = if (compact) 0.05f else 0.09f),
                        Color.Transparent,
                    ),
                center =
                    Offset(
                        size.width * (0.44f + 0.1f * waveSin(angle * 0.34f)),
                        size.height * (0.38f + 0.08f * waveCos(angle * 0.3f)),
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

    val blend = constellationBlend(elapsedMs)
    fields.update(blend.generation)
    drawConstellation(fields.current, palette, elapsedMs, compact, 1f - blend.nextAlpha)
    if (blend.nextAlpha > 0f) {
        drawConstellation(fields.next, palette, elapsedMs, compact, blend.nextAlpha)
    }
    drawVignette(alpha = if (compact) 0.16f else 0.28f)
}

private class CapsuleStarFields(private val count: Int) {
    private var generation = Long.MIN_VALUE
    var current: List<CapsuleStar> = emptyList()
        private set
    var next: List<CapsuleStar> = emptyList()
        private set

    fun update(value: Long) {
        if (value == generation) return
        current = if (value == generation + 1L && next.isNotEmpty()) next else capsuleConstellation(value, count)
        next = capsuleConstellation(value + 1L, count)
        generation = value
    }
}

private fun DrawScope.drawConstellation(
    stars: List<CapsuleStar>,
    palette: List<Color>,
    elapsedMs: Long,
    compact: Boolean,
    opacity: Float,
) {
    if (opacity <= 0f) return
    val angle = capsuleBackgroundAngle(elapsedMs)
    stars.forEachIndexed { index, star ->
        val depth = star.depth
        val drift = (depth - 0.32f) * if (compact) 1.1f * density else 3.2f * density
        val (dx, dy) = capsuleStarDrift(star, elapsedMs)
        val inset = 8f * density
        val x = inset + star.x * (size.width - 2f * inset).coerceAtLeast(1f) + dx * drift
        val y = inset + star.y * (size.height - 2f * inset).coerceAtLeast(1f) + dy * drift
        val pulse = (waveSin(angle * (0.18f + depth * 0.24f) + star.phase) + 1f) / 2f
        val alpha = (0.14f + depth * 0.24f + pulse * 0.09f).coerceAtMost(0.6f) * opacity
        val radius =
            (if (index % 17 == 0) 1.15f else 0.42f + depth * 0.32f) * density
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

}

private fun DrawScope.drawArtworkNebula(
    palette: List<Color>,
    elapsedMs: Long,
    compact: Boolean,
) {
    val angle = capsuleBackgroundAngle(elapsedMs) * 0.42
    val extent = max(size.width, size.height)
    drawRect(deepColor(lerp(palette[0], palette[1], 0.28f), 0.88f))
    val centers = listOf(
        Offset(size.width * (0.16f + 0.07f * waveCos(angle * 0.36)),
            size.height * (0.24f + 0.07f * waveSin(angle * 0.32))),
        Offset(size.width * (0.84f + 0.08f * waveSin(angle * 0.3 + 1.8)),
            size.height * (0.5f + 0.09f * waveCos(angle * 0.28 + 1.2))),
        Offset(size.width * (0.48f + 0.08f * waveCos(angle * 0.24 + 3.1)),
            size.height * (0.68f + 0.06f * waveSin(angle * 0.26 + 2.4))),
    )
    centers.forEachIndexed { index, center ->
        drawSoftLight(palette[index], center, extent * (0.66f + index * 0.07f),
            if (compact) 0.24f else 0.34f, if (compact) 0.36f else 0.72f)
    }
    repeat(if (compact) 10 else 24) { index ->
        val twinkle = 0.08f + 0.09f * ((waveSin(angle * 0.44 + index * 0.91) + 1f) / 2f)
        drawCircle(
            color = lerp(Color.White, palette[index % palette.size], 0.18f).copy(alpha = twinkle),
            radius = (0.38f + index % 4 * 0.14f) * density,
            center = Offset(deterministicFraction(index * 23 + 5) * size.width,
                deterministicFraction(index * 41 + 7) * size.height),
        )
    }
    drawVignette(if (compact) 0.18f else 0.3f)
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

// Evaluate long-running waves in double precision, then convert only coordinates.
private fun waveSin(value: Double): Float = sin(value).toFloat()
private fun waveCos(value: Double): Float = cos(value).toFloat()

/** Artwork light above a dark base, with a softer rim glow around the cover. */
private fun DrawScope.drawCapsuleGlow(palette: List<Color>, compact: Boolean) {
    drawRect(Color(0xFF050506))
    val extent = max(size.width, size.height)
    val core = Offset(size.width * if (compact) 0.18f else 0.48f, size.height * if (compact) 0.15f else 0.08f)
    drawRect(Brush.radialGradient(
        0f to palette[0].copy(alpha = 0.72f),
        0.42f to palette[0].copy(alpha = 0.34f),
        1f to Color.Transparent,
        center = core,
        radius = extent * if (compact) 0.94f else 0.78f,
    ))
    drawRect(Brush.radialGradient(
        colors = listOf(palette[1].copy(alpha = 0.34f), Color.Transparent),
        center = Offset(size.width * 0.96f, size.height * if (compact) 0.8f else 0.38f),
        radius = extent * if (compact) 0.7f else 0.48f,
    ))
    drawRect(Brush.verticalGradient(
        0f to Color.Transparent,
        0.46f to Color.Black.copy(alpha = if (compact) 0.08f else 0.14f),
        1f to Color.Black.copy(alpha = if (compact) 0.4f else 0.94f),
    ))
}
