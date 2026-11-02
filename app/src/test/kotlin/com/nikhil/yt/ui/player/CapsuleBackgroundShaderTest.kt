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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsuleBackgroundShaderTest {
    private fun render(draw: DrawScope.() -> Unit): IntArray {
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), Size(80f, 80f)) {
            drawRect(Color.Black)
            draw()
        }
        return IntArray(6400).also { bitmap.getPixels(it, 0, 80, 0, 0, 80, 80); bitmap.recycle() }
    }

    @Test fun cachedTranslatedLightKeepsTheOriginalPixelsAcrossFrames() {
        val color = Color(0xFFA27CCE)
        for (verticalScale in listOf(0.34f, 1.1f)) {
            val cached = CachedSoftLight(color, 55f, 0.35f, verticalScale)
            for (center in listOf(Offset(12f, 8f), Offset(39f, 46f), Offset(77f, 63f))) {
                val before = render {
                    scale(1f, verticalScale, center) {
                        drawCircle(Brush.radialGradient(
                            0f to color.copy(alpha = 0.35f), 0.34f to color.copy(alpha = 0.35f * 0.6f),
                            0.7f to color.copy(alpha = 0.35f * 0.16f), 1f to Color.Transparent,
                            center = center, radius = 55f), radius = 55f, center = center)
                    }
                }
                val after = render { cached.draw(this, center) }
                var maxDifference = 0
                for (i in before.indices) for (shift in listOf(0, 8, 16, 24)) {
                    maxDifference = maxOf(maxDifference, abs(((before[i] ushr shift) and 255) - ((after[i] ushr shift) and 255)))
                }
                assertTrue("Shader reuse changed pixels: max channel difference=$maxDifference", maxDifference <= 2)
            }
        }
    }
}
