package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CapsuleVideoAmbientResumeTest {
    @Test fun lastVideoColourReturnsEvenAfterThePlayerCompositionIsDestroyed() {
        val context = RuntimeEnvironment.getApplication()
        val source = Bitmap.createBitmap(48, 28, Bitmap.Config.ARGB_8888)
        for (y in 0 until 28) for (x in 0 until 48) {
            source.setPixel(x, y,
                if (x < 24) AndroidColor.rgb(211, 72, 49)
                else AndroidColor.rgb(23, 86, 226))
        }
        val original = requireNotNull(capsuleVideoAmbientFrameFromBitmap(source, "paused-track"))
        CapsuleVideoAmbientResume.put(context, original)
        CapsuleVideoAmbientResume.clearMemory()
        val restored = requireNotNull(CapsuleVideoAmbientResume.get(context, "paused-track"))
        assertEquals("paused-track", restored.mediaId)
        assertEquals(original.field.width, restored.field.width)
        assertEquals(original.field.height, restored.field.height)
        assertTrue(restored.field.cell(1, 1).red > restored.field.cell(1, 1).blue)
        assertTrue(restored.field.cell(18, 1).blue > restored.field.cell(18, 1).red)
        assertNull("Old colours must never leak into the next song",
            CapsuleVideoAmbientResume.get(context, "another-track"))
        source.recycle()
    }

    @Test fun uniformFrameIsDirectionalWashNotACircularSpotlight() {
        val greyBlue = Color(0xFF8AB1D6)
        val source = CapsuleColorField(20, 16, List(20 * 16) { greyBlue })
        val bitmap = capsuleVideoSpatialBitmap(source, Color(0xFF111116)).asAndroidBitmap()
        for (y in listOf(bitmap.height / 5, bitmap.height / 2, bitmap.height * 3 / 4)) {
            val left = bitmap.getPixel(bitmap.width / 8, y)
            val centre = bitmap.getPixel(bitmap.width / 2, y)
            val right = bitmap.getPixel(bitmap.width * 7 / 8, y)
            assertTrue("No bright circular patch in uniformly lit video",
                abs(AndroidColor.red(left) - AndroidColor.red(centre)) <= 3)
            assertTrue("No radial ring near the edge",
                abs(AndroidColor.blue(right) - AndroidColor.blue(centre)) <= 3)
        }
        bitmap.recycle()
    }

    @Test fun sampledVideoStillRetainsOppositeColoursAtTheSides() {
        val source = CapsuleColorField(20, 16, List(20 * 16) { i ->
            if (i % 20 < 10) Color(0xFFF05A2D) else Color(0xFF2468C5)
        })
        val bitmap = capsuleVideoSpatialBitmap(source, Color(0xFF111116)).asAndroidBitmap()
        val left = bitmap.getPixel(bitmap.width / 6, bitmap.height / 3)
        val right = bitmap.getPixel(bitmap.width * 5 / 6, bitmap.height / 3)
        assertTrue(AndroidColor.red(left) > AndroidColor.red(right))
        assertTrue(AndroidColor.blue(right) > AndroidColor.blue(left))
        bitmap.recycle()
    }
}
