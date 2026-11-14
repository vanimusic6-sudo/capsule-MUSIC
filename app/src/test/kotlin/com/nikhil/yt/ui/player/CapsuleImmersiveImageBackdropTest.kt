package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsuleImmersiveImageBackdropTest {
    private fun photo() = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply {
        eraseColor(0xFF553625.toInt())
    }

    @Test fun hiddenBottomLabelCannotChangeAnyBackdropPixel() {
        val plain = photo()
        val labelled = photo().apply {
            Canvas(this).drawRect(220f, 272f, 320f, 320f, Paint().apply { color = -1 })
        }
        val a = plain.capsuleImmersiveVisibleField(1f, false)
        val b = labelled.capsuleImmersiveVisibleField(1f, false)
        assertEquals(a, b)
        val first = capsuleImmersiveImageBackdropBitmap(a, 0.60f).asAndroidBitmap()
        val second = capsuleImmersiveImageBackdropBitmap(b, 0.60f).asAndroidBitmap()
        assertTrue(first.sameAs(second))
    }

    @Test fun sourceOutsideTheDisplayedCentreCropDoesNotEnterThePalette() {
        val plain = photo()
        val marked = photo().apply {
            Canvas(this).drawRect(0f, 0f, 48f, 320f, Paint().apply { color = -1 })
            Canvas(this).drawRect(272f, 0f, 320f, 320f, Paint().apply { color = -1 })
        }
        assertEquals(plain.capsuleImmersiveVisibleField(0.6f, false),
            marked.capsuleImmersiveVisibleField(0.6f, false))
    }

    @Test fun tinyVisibleWhiteLabelHasBoundedInfluenceButWhitePaperStaysWhite() {
        val plain = photo()
        val labelled = photo().apply {
            Canvas(this).drawRect(270f, 220f, 302f, 242f, Paint().apply { color = -1 })
        }
        val a = capsuleImmersiveImageBackdropBitmap(plain.capsuleImmersiveVisibleField(1f, false), 0.60f)
            .asAndroidBitmap()
        val b = capsuleImmersiveImageBackdropBitmap(labelled.capsuleImmersiveVisibleField(1f, false), 0.60f)
            .asAndroidBitmap()
        var largestChange = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            for (shift in listOf(0, 8, 16)) largestChange = maxOf(largestChange,
                abs((p ushr shift and 255) - (q ushr shift and 255)))
        }
        assertTrue("Small label must not become a large bright lamp: $largestChange", largestChange <= 8)
        val paper = photo().apply { eraseColor(-1) }.capsuleImmersiveVisibleField(1f, false)
        assertEquals(1f, capsuleImmersiveVisibleMean(paper).red, 0.0001f)
        val backdrop = capsuleImmersiveImageBackdropBitmap(paper, 0.60f).asAndroidBitmap()
        assertTrue("Near-white metadata must keep its grey ink", immersiveMetadataInk(
            androidx.compose.ui.graphics.Color(backdrop.getPixel(60, 168))).title.red < 0.5f)
    }

    @Test fun colourLayoutComesFromVisibleImageInsteadOfOneWinningBottomRow() {
        val image = photo().apply {
            Canvas(this).drawRect(0f, 0f, 160f, 320f, Paint().apply { color = 0xFFCC3020.toInt() })
            Canvas(this).drawRect(160f, 0f, 320f, 320f, Paint().apply { color = 0xFF2040CC.toInt() })
        }
        val backdrop = capsuleImmersiveImageBackdropBitmap(image.capsuleImmersiveVisibleField(1f, false), 0.60f)
            .asAndroidBitmap()
        val left = backdrop.getPixel(24, 120); val right = backdrop.getPixel(168, 120)
        assertTrue((left ushr 16 and 255) > (left and 255) + 50)
        assertTrue((right and 255) > (right ushr 16 and 255) + 50)
        val bottom = backdrop.getPixel(96, 300)
        assertTrue("Continuation must retain image colour, not end at a separate black floor",
            (bottom ushr 16 and 255) + (bottom and 255) > 80)
    }

    @Test fun greenLeftAndPinkRightKeepTheirSidesAllTheWayDown() {
        val image = photo().apply {
            Canvas(this).drawRect(0f, 0f, 160f, 320f, Paint().apply { color = 0xFF29945A.toInt() })
            Canvas(this).drawRect(160f, 0f, 320f, 320f, Paint().apply { color = 0xFF9E386D.toInt() })
        }
        val backdrop = capsuleImmersiveImageBackdropBitmap(image.capsuleImmersiveVisibleField(1f, false), 0.60f)
            .asAndroidBitmap()
        preview(backdrop, "continuation-green-left-pink-right")
        for (fraction in listOf(0.70f, 0.85f, 0.98f)) {
            val y = (backdrop.height * fraction).toInt()
            val left = backdrop.getPixel(backdrop.width / 8, y)
            val right = backdrop.getPixel(backdrop.width * 7 / 8, y)
            assertTrue("Green must stay left at $fraction",
                (left ushr 8 and 255) > (left ushr 16 and 255) + 25)
            assertTrue("Pink must stay right at $fraction",
                (right ushr 16 and 255) > (right ushr 8 and 255) + 25)
        }
    }

    private fun preview(bitmap: Bitmap, name: String) {
        val path = File("build/video-morph-preview/$name.png")
        path.parentFile.mkdirs()
        path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
