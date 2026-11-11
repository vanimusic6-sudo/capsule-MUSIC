package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CapsuleVideoAmbientTest {
    @Test fun liveFrameKeepsBlueAndRedSidesSeparate() {
        val bitmap = Bitmap.createBitmap(48, 28, Bitmap.Config.ARGB_8888)
        for (y in 0 until 28) for (x in 0 until 48) {
            bitmap.setPixel(x, y, if (x < 24) AndroidColor.rgb(219, 55, 65)
                else AndroidColor.rgb(35, 74, 214))
        }
        val sampled = capsuleVideoAmbientFrameFromBitmap(bitmap, "song")
        assertNotNull(sampled)
        val bands = requireNotNull(sampled).bands
        assertTrue(bands.first().red > bands.first().blue)
        assertTrue(bands.last().blue > bands.last().red)
        assertTrue(capsuleVideoAmbientChanged(null, bands))
        assertFalse(capsuleVideoAmbientChanged(bands, bands))
        bitmap.recycle()
    }

    @Test fun blackOrInvalidVideoFramesDoNotReplaceExistingPalette() {
        val black = Bitmap.createBitmap(48, 28, Bitmap.Config.ARGB_8888)
        black.eraseColor(AndroidColor.BLACK)
        assertNull(capsuleVideoAmbientFrameFromBitmap(black, "song"))
        assertNull(capsuleVideoAmbientFrameFromBitmap(black, ""))
        black.recycle()
    }
}
