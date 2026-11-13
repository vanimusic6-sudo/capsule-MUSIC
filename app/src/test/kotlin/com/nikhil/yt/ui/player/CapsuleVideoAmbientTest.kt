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
        assertTrue(sampled.field.width == CAPSULE_FIELD_COLUMNS)
        assertTrue(sampled.field.height == CAPSULE_FIELD_ROWS)
        assertTrue(sampled.field.cell(0, 0).red > sampled.field.cell(0, 0).blue)
        assertTrue(bands.last().blue > bands.last().red)
        assertTrue(capsuleVideoAmbientChanged(null, bands))
        assertFalse(capsuleVideoAmbientChanged(bands, bands))
        bitmap.recycle()
    }

    @Test fun interruptedVideoAmbientTransitionContinuesFromTheVisibleColour() {
        val warm = listOf(androidx.compose.ui.graphics.Color.Red, androidx.compose.ui.graphics.Color.Yellow)
        val cool = listOf(androidx.compose.ui.graphics.Color.Blue, androidx.compose.ui.graphics.Color.Cyan)
        val partlyMixed = capsuleAmbientPaletteAt(warm, cool, 0.25f)
        val interrupted = capsuleAmbientPaletteAt(partlyMixed, cool, 0f)
        assertTrue(kotlin.math.abs(partlyMixed[0].red - interrupted[0].red) < 0.0001f)
        assertTrue(partlyMixed[0].red > cool[0].red)
        assertTrue(VIDEO_AMBIENT_CAPTURE_INTERVAL_MS in 550L..850L)
        assertTrue(VIDEO_AMBIENT_BLEND_DURATION_MS > VIDEO_AMBIENT_CAPTURE_INTERVAL_MS)
    }

    @Test fun blackOrInvalidVideoFramesDoNotReplaceExistingPalette() {
        val black = Bitmap.createBitmap(48, 28, Bitmap.Config.ARGB_8888)
        black.eraseColor(AndroidColor.BLACK)
        assertNull(capsuleVideoAmbientFrameFromBitmap(black, "song"))
        assertNull(capsuleVideoAmbientFrameFromBitmap(black, ""))
        black.recycle()
    }
}
