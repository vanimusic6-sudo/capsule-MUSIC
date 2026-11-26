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

    @Test fun photoFeatherIsSmoothAndMonotonicAcrossTheCoverBoundary() {
        assertEquals(1f, immersivePhotoFeatherAlpha(0f), 0.0001f)
        assertEquals(0f, immersivePhotoFeatherAlpha(1f), 0.0001f)
        assertEquals(0f, immersivePhotoFeatherAlpha(Float.NaN), 0.0001f)
        var last = 1f
        for (i in 1..1_000) {
            val alpha = immersivePhotoFeatherAlpha(i / 1_000f)
            assertTrue("Photo must not regain opacity below the join", alpha <= last + 0.00001f)
            assertTrue("No hard horizontal mask edge", last - alpha < 0.008f)
            last = alpha
        }
        // Check continuity of the derivative, rather than forcing the old
        // intermediate stops to have zero slope (which caused opacity shelves).
        for (stop in listOf(0.34f, 0.44f, 0.58f, 0.70f, 0.82f, 0.88f)) {
            val middle = immersivePhotoFeatherAlpha(stop)
            val left = middle - immersivePhotoFeatherAlpha(stop - 0.001f)
            val right = immersivePhotoFeatherAlpha(stop + 0.001f) - middle
            assertTrue("Smooth slope at $stop", abs(left - right) < 0.0002f)
        }
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

    @Test fun pureBlackArtworkKeepsNeutralPaletteWithoutChangingBrightPhoto() {
        val mask = Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLACK)
            val red = Paint().apply { color = 0xFFFF161B.toInt() }
            Canvas(this).drawRect(45f,32f,144f,182f,red)
            Canvas(this).drawRect(180f,32f,272f,182f,red)
        }
        val clean = mask.capsuleImmersiveVisibleField(1f,false)
        val darkest = clean.cells.filter { maxOf(it.red,it.green,it.blue) < 0.12f }
        assertTrue("The dark background must dominate the ambient palette",
            darkest.size > clean.cells.size * 0.55f)
        val fire = Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFEA5710.toInt())
            Canvas(this).drawRect(110f,50f,215f,320f,
                Paint().apply { color = 0xFF241A37.toInt() })
        }
        val fireField = fire.capsuleImmersiveVisibleField(1f,false)
        assertTrue("A photo with a dark person must keep its orange background",
            capsuleImmersiveVisibleMean(fireField).red > 0.45f)
    }

    @Test fun darkCoverWithBrightRedEyesKeepsDarkFloor() {
        val mask = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLACK)
            val red = Paint().apply { color = 0xFFFF161B.toInt() }
            Canvas(this).drawRect(45f, 32f, 144f, 182f, red)
            Canvas(this).drawRect(180f, 32f, 272f, 182f, red)
        }
        val backdrop = capsuleImmersiveImageBackdropBitmap(
            mask.capsuleImmersiveVisibleField(1f, false), 0.60f,
        ).asAndroidBitmap()
        val bottom = backdrop.getPixel(backdrop.width / 2, backdrop.height - 8)
        assertTrue("Black album backgrounds must not gain a purple haze",
            maxOf(bottom ushr 16 and 255, bottom ushr 8 and 255, bottom and 255) < 28)
        val top = backdrop.getPixel(backdrop.width / 4, backdrop.height / 4)
        assertTrue("Red eyes still remain visible", (top ushr 16 and 255) > 40)
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

    @Test fun diffuseFieldCurvesInBothDirectionsWithoutIntroducingSeamLines() {
        val stage = 0.60f
        val x = 0.20f
        val y = 0.80f
        val progress = (y - stage) / (1f - stage)
        val eased = progress * progress * (3f - 2f * progress)
        val oldStraightX = 0.5f + (x - 0.5f) * (1f - (1f - stage) * eased)
        val curvedX = capsuleImmersiveBackdropSourceX(x, y, stage)
        assertTrue("Colour must no longer travel in perfectly vertical strips",
            abs(curvedX - oldStraightX) > 0.045f)
        val curvedCentre = capsuleImmersiveBackdropSourceX(0.5f, y, stage)
        assertTrue("The middle of a two-colour cover needs a real soft bend",
            abs(curvedCentre - 0.5f) > 0.07f)
        val leftY = capsuleImmersiveBackdropSourceY(0.12f, y, stage)
        val rightY = capsuleImmersiveBackdropSourceY(0.88f, y, stage)
        assertTrue("The lower photograph must diffuse in two dimensions",
            abs(leftY - rightY) > 0.008f)
        assertEquals(IMMERSIVE_VISIBLE_SAMPLE_END,
            capsuleImmersiveBackdropSourceY(0.5f, stage, stage), 0.00001f)
        for (xSample in listOf(0f, 0.12f, 0.5f, 0.88f, 1f)) {
            val before = capsuleImmersiveBackdropSourceY(xSample, stage - 0.0001f, stage)
            val after = capsuleImmersiveBackdropSourceY(xSample, stage + 0.0001f, stage)
            assertTrue("No horizontal seam at the album edge", abs(before - after) < 0.001f)
        }
    }

    @Test fun photoToBlurDoesNotJumpAcrossTheArtworkBoundary() {
        for (stage in listOf(0.35f, 0.55f, 0.75f)) {
            for (x in listOf(0f, 0.1f, 0.5f, 0.9f, 1f)) {
                val before = capsuleImmersiveBackdropSourceX(x, stage - 0.001f, stage)
                val at = capsuleImmersiveBackdropSourceX(x, stage, stage)
                val after = capsuleImmersiveBackdropSourceX(x, stage + 0.001f, stage)
                assertEquals("The visible photo keeps the exact same X coordinate at its fade",
                    x, at, 0.00001f)
                assertTrue("No sudden horizontal contraction at the photo/blur seam",
                    abs(before - after) < 0.002f)
                assertEquals("The lower continuation still folds into the wide blur",
                    0.5f + (x - 0.5f) * stage,
                    capsuleImmersiveBackdropSourceX(x, 1f, stage), 0.00001f)
            }
        }
    }

    @Test fun dominantSolidGroundIgnoresForegroundAndPreservesActualHue() {
        val teal = 0xFF117B81.toInt()
        val image = photo().apply {
            eraseColor(teal)
            Canvas(this).drawRect(100f, 40f, 220f, 245f,
                Paint().apply { color = 0xFFFFC044.toInt() })
        }
        val field = image.capsuleImmersiveVisibleField(1f, false)
        val ground = requireNotNull(capsuleImmersiveDominantGround(field))
        assertTrue(ground.green > ground.red + 0.25f)
        assertTrue(ground.blue > ground.red + 0.25f)
        val page = capsuleImmersiveImageBackdropBitmap(field, 0.60f).asAndroidBitmap()
        val lower = androidx.compose.ui.graphics.Color(page.getPixel(page.width / 2, page.height - 8))
        assertTrue(lower.green > lower.red + 0.10f)
        assertTrue(lower.blue > lower.red + 0.10f)
    }

    @Test fun noReflectionRevisitsTheUpperPhotograph() {
        for (stage in listOf(0.35f, 0.60f, 0.75f)) {
            for (i in 0..100) for (x in listOf(0f, 0.5f, 1f)) {
                val y = stage + (1f - stage) * i / 100f
                val source = capsuleImmersiveBackdropSourceY(x, y, stage)
                assertTrue(source >= IMMERSIVE_VISIBLE_SAMPLE_END - 0.066f)
                assertTrue(source <= IMMERSIVE_VISIBLE_SAMPLE_END)
            }
        }
    }

    @Test fun brightChromaticMetadataGetsDarkInk() {
        val title = immersiveMetadataInk(androidx.compose.ui.graphics.Color(0xFFFFE940)).title
        assertTrue(title.red < 0.5f && title.green < 0.5f && title.blue < 0.5f)
    }

    @Test fun largeCentralSubjectDoesNotOverrideTheSurroundingRedBackground() {
        val image = photo().apply {
            eraseColor(0xFFDF262D.toInt())
            Canvas(this).drawRect(27f, 65f, 293f, 292f,
                Paint().apply { color = 0xFF1635A6.toInt() })
        }
        val field = image.capsuleImmersiveVisibleField(1f, false)
        val ground = requireNotNull(capsuleImmersiveDominantGround(field))
        assertTrue(ground.red > ground.blue + 0.40f)
        val background = capsuleImmersiveImageBackdropBitmap(field, 0.60f).asAndroidBitmap()
        val bottom = androidx.compose.ui.graphics.Color(background.getPixel(96, 295))
        assertTrue(bottom.red > bottom.blue + 0.25f)
    }

    @Test fun realPhotoBlurReducesFineDetailAndIsNotAReflection() {
        val artwork = photo().apply {
            val canvas = Canvas(this)
            for (x in 0 until width step 8) {
                canvas.drawRect(x.toFloat(), 0f, (x + 4).toFloat(), height.toFloat(),
                    Paint().apply { color = -1 })
            }
        }
        val blurred = artwork.capsuleImmersivePhotoBlur(1f, false).asAndroidBitmap()
        assertEquals(192, blurred.width)
        assertEquals(192, blurred.height)
        fun variation(y: Int): Int {
            val values = (20 until 172).map { x -> blurred.getPixel(x, y) ushr 16 and 255 }
            return values.maxOrNull()!! - values.minOrNull()!!
        }
        assertTrue(variation(160) <= variation(45))
        assertTrue(variation(160) < 30)
        assertEquals(0f, immersiveBlurredPhotoAlpha(0f), 0f)
        assertTrue(immersiveBlurredPhotoAlpha(0.60f) > 0.60f)
        assertEquals(0f, immersiveBlurredPhotoAlpha(1f), 0f)
        assertEquals(0f, immersiveBlurredPhotoAlpha(Float.NaN), 0f)
    }

    @Test fun sharpAndBlurredLayersShareOneMonotonicOpticalEnvelope() {
        var previous = 1f
        for (i in 0..1000) {
            val y = i / 1000f
            val sharp = immersivePhotoFeatherAlpha(y)
            val blur = immersiveBlurredPhotoAlpha(y)
            val combined = sharp + (1f - sharp) * blur
            assertEquals(immersivePhotoCombinedAlpha(y), combined, 0.00001f)
            assertTrue("No opacity rebound at $y", combined <= previous + 0.00001f)
            assertTrue("No optical step at $y", previous - combined < 0.004f)
            previous = combined
        }
        assertEquals(0f, previous, 0.00001f)
    }

    private fun preview(bitmap: Bitmap, name: String) {
        val path = File("build/video-morph-preview/$name.png")
        path.parentFile.mkdirs()
        path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
