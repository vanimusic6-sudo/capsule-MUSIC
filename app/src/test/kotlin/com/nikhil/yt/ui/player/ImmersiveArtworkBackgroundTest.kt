package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * These synthetic covers reproduce the failure modes shown in the screenshots:
 * green lettering over white paper must not turn the page green, and a subject
 * in the centre must not override the colour of the lower visible background.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ImmersiveArtworkBackgroundTest {
    @Test fun whiteLowerBackgroundBeatsSmallGreenForegroundAndKeepsNeutralSaturation() {
        val image = Bitmap.createBitmap(180, 100, Bitmap.Config.ARGB_8888)
        image.eraseColor(AndroidColor.WHITE)
        // Simulate a vivid foreground illustration surrounded by white background.
        for (y in 50 until 90) for (x in 75 until 103) {
            image.setPixel(x, y, AndroidColor.GREEN)
        }
        for (y in 60 until 90 step 6) for (x in 48 until 132 step 3) {
            image.setPixel(x, y, AndroidColor.BLACK)
        }
        val result = image.immersiveBottomBackground(0.95f).comfortableImmersiveColor()
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(result.toArgb(), hsv)
        assertTrue("A white paper background must stay grey, not green: saturation=${hsv[1]}", hsv[1] < 0.10f)
        assertTrue(hsv[2] in 0.16f..0.45f)
        image.recycle()
    }

    @Test fun redLowerBackgroundBeatsUnrelatedBlueAndGreenSubject() {
        val image = Bitmap.createBitmap(180, 100, Bitmap.Config.ARGB_8888)
        image.eraseColor(AndroidColor.rgb(163, 26, 51))
        for (y in 50 until 88) for (x in 77 until 107) {
            image.setPixel(x, y, if (y % 2 == 0) AndroidColor.BLUE else AndroidColor.GREEN)
        }
        val result = image.immersiveBottomBackground(0.95f).comfortableImmersiveColor()
        assertTrue("The actual red background should determine the fade", result.red > result.green * 1.8f)
        assertTrue(result.red > result.blue * 1.3f)
        image.recycle()
    }

    @Test fun paletteOfPixelsOutsideVisibleCentreCropCannotRecolourTheFade() {
        val image = Bitmap.createBitmap(180, 100, Bitmap.Config.ARGB_8888)
        image.eraseColor(AndroidColor.GREEN)
        for (y in 0 until 100) for (x in 40 until 140) {
            image.setPixel(x, y, AndroidColor.WHITE)
        }
        val result = image.immersiveBottomBackground(0.85f).comfortableImmersiveColor()
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(result.toArgb(), hsv)
        assertTrue("Off-screen green bars must not tint a white visible cover", hsv[1] < 0.10f)
        image.recycle()
    }
}
