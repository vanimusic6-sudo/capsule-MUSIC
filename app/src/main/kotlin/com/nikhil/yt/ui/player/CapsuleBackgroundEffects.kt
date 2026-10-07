/*
 * Capsule MUSIC
 * Calm procedural backgrounds shared by the player, mini-player and dock.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nikhil.yt.ui.component.LocalCapsuleBackgroundMotionEnabled
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

internal fun capsuleBackgroundNeedsClock(effect: CapsuleBackgroundEffect): Boolean =
    when (effect) {
        CapsuleBackgroundEffect.COLOR_FLOW,
        CapsuleBackgroundEffect.CAPSULE_STAR,
        CapsuleBackgroundEffect.NEBULA,
        -> true

        CapsuleBackgroundEffect.MATTE_GRADIENT,
        CapsuleBackgroundEffect.TONAL_WASH,
        CapsuleBackgroundEffect.AMBIENT_GLOW,
        CapsuleBackgroundEffect.CAPSULE_GLOW,
        -> false
    }

/*
 * These are decorative clocks, and a decorative clock's frame rate is a battery setting.
 *
 * The compact one is the expensive one: it drives the mini-player's background, which is on screen
 * on every page of the app for as long as something is playing. At 15fps it was the last thing
 * keeping the frame clock awake during ordinary use — not enough to feel, enough to warm the SoC
 * over an evening. These effects are slow drifts and washes; nothing in them moves fast enough for
 * the eye to tell 15 from 8, or 24 from 14, which is why the frame rate was the thing to cut
 * rather than the effect.
 */
private const val COMPACT_BACKGROUND_FPS = 6
private const val FULL_BACKGROUND_FPS = 12
private const val STATIC_BACKGROUND_TIME_MS = 6_480L

/**
 * Drive the slow effects at their actual target frame rate and stop the clock
 * while the Activity is not visible. An InfiniteTransition still wakes up at
 * the display refresh rate even when its derived value changes less often.
 */
@Composable
internal fun rememberCapsuleAnimationTime(
    compact: Boolean,
    running: Boolean = true,
    framesPerSecondOverride: Int? = null,
): State<Long> {
    /*
     * This is an animation timeline, not wall-clock time.
     *
     * Using SystemClock.elapsedRealtime() directly makes paused backgrounds jump to a different
     * phase when animated=false and jump again on resume. Accumulate only the time spent running
     * instead, so pause means a real freeze of the current frame.
     */
    val time = remember { mutableLongStateOf(STATIC_BACKGROUND_TIME_MS) }
    val isVisible = appIsOnScreen()
    val motionEnabled = LocalCapsuleBackgroundMotionEnabled.current
    val framesPerSecond =
        (
            framesPerSecondOverride
                ?: if (compact) {
                    COMPACT_BACKGROUND_FPS
                } else {
                    FULL_BACKGROUND_FPS
                }
        ).coerceIn(1, 60)
    val frameDelayMs = 1_000L / framesPerSecond

    LaunchedEffect(compact, isVisible, running, framesPerSecond, motionEnabled) {
        if (!isVisible || !running || !motionEnabled) return@LaunchedEffect

        var previousTick = SystemClock.elapsedRealtime()
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            val delta = (now - previousTick).coerceIn(0L, frameDelayMs * 3L)
            time.longValue += delta
            previousTick = now
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
    animationFps: Int? = null,
    sharedAnimationTime: State<Long>? = null,
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
    val motionEnabled = LocalCapsuleBackgroundMotionEnabled.current
    val needsClock = capsuleBackgroundNeedsClock(effect)
    val time =
        if (needsClock) {
            sharedAnimationTime
                ?: rememberCapsuleAnimationTime(
                    compact = compact,
                    running = animated && motionEnabled,
                    framesPerSecondOverride = animationFps,
                )
        } else {
            null
        }

    val starFields = remember(compact) { CapsuleStarFields(if (compact) 22 else 64) }
    Box(modifier = modifier.drawWithCache {
        // These shaders depend on palette/geometry, never on animation time. Reuse them across
        // drift frames instead of allocating identical brushes and shaders at every clock tick.
        val baseBrush = when (effect) {
            CapsuleBackgroundEffect.COLOR_FLOW -> Brush.linearGradient(
                0f to deepColor(lerp(palette[0], palette[1], 0.18f), 0.58f),
                0.52f to deepColor(lerp(palette[1], palette[2], 0.35f), 0.7f),
                1f to deepColor(palette[2], 0.84f),
                start = Offset.Zero, end = Offset(size.width, size.height),
            )
            CapsuleBackgroundEffect.CAPSULE_STAR -> Brush.verticalGradient(
                0f to deepColor(lerp(palette[0], palette[1], 0.18f), 0.72f),
                0.54f to deepColor(lerp(palette[0], palette[2], 0.45f), 0.82f),
                1f to deepColor(lerp(palette[1], palette[2], 0.58f), 0.9f),
            )
            else -> null
        }
        val starSweep = if (effect == CapsuleBackgroundEffect.CAPSULE_STAR) {
            Brush.linearGradient(
                colors = listOf(Color.Transparent,
                    palette[2].copy(alpha = if (compact) 0.07f else 0.1f), Color.Transparent),
                start = Offset(-size.width * 0.12f, size.height * 0.92f),
                end = Offset(size.width * 1.12f, size.height * 0.08f),
            )
        } else null
        val vignette = if (needsClock) {
            val alpha = when (effect) {
                CapsuleBackgroundEffect.COLOR_FLOW -> if (compact) 0.14f else 0.26f
                CapsuleBackgroundEffect.CAPSULE_STAR -> if (compact) 0.16f else 0.28f
                else -> if (compact) 0.18f else 0.3f
            }
            Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = alpha)),
                center = Offset(size.width * 0.5f, size.height * 0.46f),
                radius = max(size.width, size.height) * 0.72f,
            )
        } else null
        onDrawBehind {
            // Read the clock only in drawing; its ticks never invalidate the shader cache.
            val elapsedMs = time?.value ?: STATIC_BACKGROUND_TIME_MS
            when (effect) {
                CapsuleBackgroundEffect.MATTE_GRADIENT -> drawMatteGradient(palette)
                CapsuleBackgroundEffect.TONAL_WASH -> drawTonalWash(palette)
                CapsuleBackgroundEffect.AMBIENT_GLOW -> drawAmbientGlow(palette)
                CapsuleBackgroundEffect.COLOR_FLOW -> drawSoftColorFlow(
                    palette, elapsedMs, compact, requireNotNull(baseBrush), requireNotNull(vignette),
                )
                CapsuleBackgroundEffect.CAPSULE_STAR -> drawCapsuleStarField(
                    palette, elapsedMs, compact, starFields, requireNotNull(baseBrush),
                    requireNotNull(starSweep), requireNotNull(vignette),
                )
                CapsuleBackgroundEffect.CAPSULE_GLOW -> drawCapsuleGlow(palette, compact)
                CapsuleBackgroundEffect.NEBULA -> drawArtworkNebula(
                    palette, elapsedMs, compact, requireNotNull(vignette),
                )
            }
        }
    })
}

/** Dedicated neutral liquid-glass option. Every other compact style stays opaque. */
@Composable
internal fun CapsuleGlassSurface(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    shape: Shape = RoundedCornerShape(24.dp),
) {
    // Keep the public signature shared by the player/dock, but deliberately do not tint the
    // material from artwork. The content behind the glass already supplies colour naturally.
    @Suppress("UNUSED_VARIABLE")
    val ignoredArtworkColors = colors

    /*
     * The liquid read comes from real backdrop refraction, not painted shine lines:
     * - less blur keeps the source legible enough to bend instead of turning into frosted glass;
     * - a narrow edge lens gives the material depth without folding recognisable artwork;
     * - static corner/edge light fields describe material thickness without any animation clock.
     *
     * Chromatic aberration and depthEffect stay disabled deliberately. They cost extra GPU work
     * and are not needed to make the mini-player feel liquid on a surface that can remain visible
     * for hours.
     */
    if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val localDensity = LocalDensity.current
        val blurRadiusPx =
            remember(localDensity) {
                with(localDensity) { 3.75.dp.toPx() }
            }
        val maxRefractionHeightPx =
            remember(localDensity) {
                with(localDensity) { 8.dp.toPx() }
            }
        val maxRefractionAmountPx =
            remember(localDensity) {
                with(localDensity) { 13.dp.toPx() }
            }

        val glassShape =
            remember(shape) {
                { shape }
            }
        val glassHighlight =
            remember {
                {
                    // Kyant's directional rim follows the rounded outline instead of painting a
                    // straight fake shine. Keep it restrained: it should appear when the material
                    // catches light, not read as a white border.
                    Highlight.Default.copy(
                        width = 0.6.dp,
                        blurRadius = 0.35.dp,
                        alpha = 0.62f,
                    )
                }
            }
        val glassEffects: BackdropEffectScope.() -> Unit =
            remember(
                blurRadiusPx,
                maxRefractionHeightPx,
                maxRefractionAmountPx,
            ) {
                {
                    /*
                     * SimpMusic's successful glass stack is lens-first: preserve enough source
                     * detail for refraction to read, enrich it a little, then bend the perimeter.
                     * A single colour-control pass is cheaper than stacking multiple colour filters.
                     */
                    colorControls(
                        brightness = 0.015f,
                        contrast = 1.04f,
                        saturation = 1.42f,
                    )
                    blur(blurRadiusPx)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val shortSide = size.minDimension

                        /*
                         * Keep refraction in a narrow perimeter band. The previous 23% / 47%
                         * geometry was visually impressive on abstract backgrounds, but it bent
                         * recognisable artwork too far: circular artist cards became oval slices
                         * and square covers looked folded across the middle.
                         *
                         * Roughly 78% of the centre is now optically stable. Hard dp caps also stop
                         * the effect becoming disproportionately strong on taller layouts/tablets.
                         */
                        lens(
                            refractionHeight =
                                minOf(shortSide * 0.11f, maxRefractionHeightPx),
                            refractionAmount =
                                minOf(shortSide * 0.20f, maxRefractionAmountPx),
                            depthEffect = false,
                            chromaticAberration = false,
                        )
                    }
                }
            }
        val glassTint: DrawScope.() -> Unit =
            remember {
                {
                    // Less milk, more lens. The page underneath supplies the colour and movement.
                    drawRect(Color(0x2C101116))
                }
            }

        Box(
            modifier =
                modifier
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = glassShape,
                        effects = glassEffects,
                        highlight = glassHighlight,
                        // Backdrop's default shadow allocates a separate offscreen layer and applies
                        // a 24dp BlurMaskFilter every draw. The mini-player already has depth from
                        // refraction + cached edge shading, so paying for that shadow is wasted GPU.
                        shadow = null,
                        onDrawSurface = glassTint,
                    )
                    .drawWithCache {
                        /*
                         * The library now owns the actual rim. These broad cached fields only give
                         * the material a little volume; there are deliberately no straight shine
                         * lines and no animation clock.
                         */
                        val upperLeftBloom =
                            Brush.radialGradient(
                                colors =
                                    listOf(
                                        Color.White.copy(alpha = 0.055f),
                                        Color.White.copy(alpha = 0.015f),
                                        Color.Transparent,
                                    ),
                                center = Offset(size.width * 0.07f, size.height * 0.02f),
                                radius = max(size.width, size.height) * 0.64f,
                            )
                        val edgeDepth =
                            Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.018f),
                                0.2f to Color.Transparent,
                                0.7f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.085f),
                            )

                        onDrawWithContent {
                            drawContent()
                            // Two cached fields are enough now that the library owns the real rim.
                            // This removes two full-surface blend passes from every glass redraw.
                            drawRect(edgeDepth)
                            drawRect(upperLeftBloom)
                        }
                    },
        )
        return
    }

    /*
     * Compatibility/fallback path keeps the same hierarchy without pretending to refract a layer.
     * There are intentionally no painted highlight lines here either.
     */
    Box(
        modifier =
            modifier.drawWithCache {
                val body =
                    Brush.linearGradient(
                        colors =
                            listOf(
                                Color(0xE61A1B20),
                                Color(0xD9121318),
                                Color(0xE00B0C10),
                            ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    )
                val upperLeftBloom =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                Color.White.copy(alpha = 0.085f),
                                Color.White.copy(alpha = 0.022f),
                                Color.Transparent,
                            ),
                        center = Offset(size.width * 0.1f, size.height * 0.03f),
                        radius = max(size.width, size.height) * 0.65f,
                    )
                val edgeDepth =
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.025f),
                        0.2f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.12f),
                    )

                onDrawBehind {
                    drawRect(body)
                    drawRect(edgeDepth)
                    drawRect(upperLeftBloom)
                }
            },
    )
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
        return Color.White.copy(alpha = 0.14f)
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
    baseBrush: Brush,
    vignette: Brush,
) {
    val angle = capsuleBackgroundAngle(elapsedMs) * 0.36
    val extent = max(size.width, size.height)
    drawRect(baseBrush)
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
    drawRect(vignette)
}

private fun DrawScope.drawCapsuleStarField(
    palette: List<Color>,
    elapsedMs: Long,
    compact: Boolean,
    fields: CapsuleStarFields,
    baseBrush: Brush,
    sweepBrush: Brush,
    vignette: Brush,
) {
    drawRect(baseBrush)

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
    drawRect(sweepBrush)

    val blend = constellationBlend(elapsedMs)
    fields.update(blend.generation)
    drawConstellation(fields.current, palette, elapsedMs, compact, 1f - blend.nextAlpha)
    if (blend.nextAlpha > 0f) {
        drawConstellation(fields.next, palette, elapsedMs, compact, blend.nextAlpha)
    }
    drawRect(vignette)
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
    vignette: Brush,
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
    drawRect(vignette)
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
