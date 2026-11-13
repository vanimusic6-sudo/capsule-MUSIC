package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CapsuleScenePaletteTest {
    @Test fun realRedCoverAccentsAreNotWashedIntoGraphite() {
        val img = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 100) {
            val pixel = when {
                x < 56 -> AndroidColor.rgb(28, 31, 44)
                x < 89 -> AndroidColor.rgb(203, 64, 73)
                else -> AndroidColor.rgb(236, 231, 219)
            }
            img.setPixel(x, y, pixel)
        }
        val scene = capsuleArtworkScenePalette(img)
        assertTrue("Background must remain dark", scene[0].red < 0.23f)
        assertTrue("Real crimson must remain visibly red", scene[1].red > scene[1].blue * 2f)
        assertTrue("Moon white must survive as secondary light", scene[2].red > 0.65f)
        img.recycle()
    }

    @Test fun grayscaleSleeveDoesNotGetMadeUpChromaticHues() {
        val img = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
        for (y in 0 until 60) for (x in 0 until 60) {
            val gray = (20 + (x * 190 / 60))
            img.setPixel(x, y, AndroidColor.rgb(gray, gray, gray))
        }
        val colors = capsuleArtworkScenePalette(img)
        assertTrue(colors.all { kotlin.math.abs(it.red - it.green) < 0.002f &&
            kotlin.math.abs(it.green - it.blue) < 0.002f })
        img.recycle()
    }

    @Test fun videoColourIlluminatesTheCorrectSideInsteadOfPaintingOneFlatTone() {
        val field = CapsuleColorField(20, 16, List(20 * 16) { i ->
            if (i % 20 < 10) Color(0xFFE13F4C) else Color(0xFF2878DC)
        })
        val bitmap = capsuleVideoSpatialBitmap(field, Color(0xFF111014)).asAndroidBitmap()
        val left = bitmap.getPixel(bitmap.width / 6, bitmap.height / 3)
        val right = bitmap.getPixel(bitmap.width * 5 / 6, bitmap.height / 3)
        assertTrue("Warm left region should stay warmer than right",
            AndroidColor.red(left) > AndroidColor.red(right))
        assertTrue("Cool right region should stay bluer than left",
            AndroidColor.blue(right) > AndroidColor.blue(left))
    }
}
