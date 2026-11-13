package com.nikhil.yt.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import kotlin.math.max

/** Palette/geometry resources for a static background. Drawing reuses every shader;
 * no bitmap, extra compositing layer, reduced resolution or changed light profile. */
internal class CapsuleStaticBackground(
    effect: CapsuleBackgroundEffect, palette: List<Color>, size: Size, compact: Boolean,
) {
    private val commands: List<DrawScope.() -> Unit> = buildList {
        val extent = max(size.width, size.height)
        val wide = size.width > size.height * 2f
        fun deep(color: Color, amount: Float) = lerp(color, Color(0xFF03040A), amount)
        fun solid(color: Color) { add { drawRect(color) } }
        fun rect(brush: Brush) { add { drawRect(brush) } }
        fun light(color: Color, x: Float, y: Float, radius: Float, alpha: Float, scale: Float) {
            val cached = CachedSoftLight(color, extent * radius, alpha, scale)
            val center = Offset(size.width * x, size.height * y)
            add { cached.draw(this, center) }
        }
        fun vignette(alpha: Float) = rect(Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = alpha)),
            center = Offset(size.width * 0.5f, size.height * 0.46f), radius = extent * 0.72f))
        when (effect) {
            CapsuleBackgroundEffect.MATTE_GRADIENT -> {
                rect(Brush.verticalGradient(
                    0f to deep(lerp(palette[0], palette[1], 0.12f), 0.5f),
                    0.36f to deep(lerp(palette[0], palette[2], 0.28f), 0.62f),
                    0.74f to deep(lerp(palette[1], palette[2], 0.4f), 0.78f), 1f to Color(0xFF09090D)))
                light(palette[0], 0.18f, 0.04f, 0.82f, 0.22f, if (wide) 0.32f else 0.9f)
                light(palette[2], 0.96f, 0.46f, 0.62f, 0.12f, if (wide) 0.35f else 1.1f)
                vignette(if (wide) 0.12f else 0.24f)
            }
            CapsuleBackgroundEffect.TONAL_WASH -> {
                rect(Brush.linearGradient(
                    0f to deep(palette[0], 0.52f),
                    0.46f to deep(lerp(palette[0], palette[1], 0.22f), 0.66f),
                    1f to deep(lerp(palette[0], palette[2], 0.38f), 0.84f),
                    start = Offset(size.width * 0.08f, 0f), end = Offset(size.width, size.height)))
                light(lerp(palette[0], Color.White, 0.12f), 0.1f, -0.12f, 0.94f, 0.18f, if (wide) 0.34f else 1f)
                light(palette[1], 0.88f, 0.55f, 0.54f, 0.1f, if (wide) 0.3f else 1.1f)
                vignette(if (wide) 0.1f else 0.22f)
            }
            CapsuleBackgroundEffect.AMBIENT_GLOW -> {
                // Sunlit halos remain soft and limited; no framebuffer blur.
                solid(Color(0xFF0B0A0B))
                val sunlight = lerp(palette[0], Color(0xFFFFE6B9), 0.27f)
                light(sunlight, 0.14f, 0.01f, 0.86f, 0.68f, if (wide) 0.35f else 1.05f)
                light(lerp(palette[1], Color.White, 0.20f), 0.94f, 0.44f, 0.68f, 0.36f,
                    if (wide) 0.38f else 1.14f)
                light(palette[2], 0.32f, 0.78f, 0.66f, 0.18f,
                    if (wide) 0.32f else 0.94f)
                rect(Brush.verticalGradient(0f to Color.Transparent,
                    0.58f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (wide) 0.22f else 0.58f)))
                vignette(if (wide) 0.09f else 0.17f)
            }
            CapsuleBackgroundEffect.CAPSULE_GLOW -> {
                // Premium, spacious album-colour wash (inspired by streaming UI).
                // Layered diffused colours, a deep lower vignette, no hard-edged circles.
                rect(Brush.verticalGradient(
                    0f to deep(lerp(palette[0], palette[1], 0.16f), 0.56f),
                    0.45f to deep(lerp(palette[1], palette[2], 0.25f), 0.81f),
                    1f to Color(0xFF09080D)))
                light(lerp(palette[0], Color.White, 0.13f), if (compact) 0.10f else 0.34f, 0.0f,
                    1.04f, if (compact) 0.48f else 0.67f, if (wide) 0.34f else 0.90f)
                light(lerp(palette[1], Color.White, 0.10f), 0.96f, 0.30f,
                    0.86f, if (compact) 0.32f else 0.46f, if (wide) 0.36f else 1.02f)
                light(palette[2], 0.26f, 0.76f,
                    0.72f, if (compact) 0.18f else 0.27f, if (wide) 0.32f else 0.80f)
                rect(Brush.verticalGradient(0f to Color.Transparent,
                    0.25f to Color.Transparent,
                    0.70f to Color.Black.copy(alpha = if (compact) 0.19f else 0.33f),
                    1f to Color.Black.copy(alpha = if (compact) 0.54f else 0.76f)))
                vignette(if (wide) 0.08f else 0.15f)
            }
            else -> error("Only static effects have a static background cache")
        }
    }

    fun draw(scope: DrawScope) { for (command in commands) command(scope) }
}
