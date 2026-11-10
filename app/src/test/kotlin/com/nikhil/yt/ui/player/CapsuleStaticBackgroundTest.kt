package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsuleStaticBackgroundTest {
    private fun render(size: Size, draw: DrawScope.() -> Unit): IntArray {
        val w = size.width.toInt(); val h = size.height.toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), size) {
            drawRect(Color.Black); draw()
        }
        return IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h); bitmap.recycle() }
    }

    @Test fun untouchedStaticEffectsPreserveTheirOriginalPixelsAndCachedResources() {
        val effects = listOf(CapsuleBackgroundEffect.MATTE_GRADIENT, CapsuleBackgroundEffect.TONAL_WASH)
        val palettes = listOf(listOf(Color(0xFFA27CCE), Color(0xFF4CB9AE), Color(0xFFB46B75)),
            listOf(Color.White, Color(0xFF777777), Color.Black))
        for (effect in effects) for (size in listOf(Size(80f, 100f), Size(160f, 40f))) {
            for (compact in listOf(false, true)) for (palette in palettes) {
                val before = render(size) {
                    when (effect) {
                        CapsuleBackgroundEffect.MATTE_GRADIENT -> drawMatteGradient(palette)
                        CapsuleBackgroundEffect.TONAL_WASH -> drawTonalWash(palette)
                        CapsuleBackgroundEffect.AMBIENT_GLOW -> drawAmbientGlow(palette)
                        else -> drawCapsuleGlow(palette, compact)
                    }
                }
                val cached = CapsuleStaticBackground(effect, palette, size, compact)
                repeat(3) {
                    val after = render(size) { cached.draw(this) }
                    var maximum = 0
                    for (i in before.indices) for (shift in listOf(0, 8, 16, 24)) {
                        maximum = maxOf(maximum, abs(((before[i] ushr shift) and 255) - ((after[i] ushr shift) and 255)))
                    }
                    assertTrue("$effect size=$size compact=$compact changed pixels by $maximum", maximum <= 2)
                }
            }
        }
    }

    private fun deepColor(color: Color, amount: Float) = lerp(color, Color(0xFF03040A), amount.coerceIn(0f, 1f))

    // Frozen pre-optimization renderer from 48d9889. Keep independent reference math.
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
}
