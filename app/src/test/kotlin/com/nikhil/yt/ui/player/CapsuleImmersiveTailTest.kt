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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CapsuleImmersiveTailTest {
    @Test fun overscannedEdgeKeepsRealLeftAndRightArtworkColors() {
        val bitmap = Bitmap.createBitmap(120, 140, Bitmap.Config.ARGB_8888)
        for (y in 0 until 140) for (x in 0 until 120) {
            bitmap.setPixel(x, y, if (x < 60) AndroidColor.rgb(210, 136, 60)
                else AndroidColor.rgb(31, 78, 169))
        }
        val tail = bitmap.capsuleImmersiveVisibleTail(0.85f, landscape = true)
        assertTrue(tail.width >= 48)
        assertTrue(tail.cell(5, tail.height - 1).red >
            tail.cell(5, tail.height - 1).blue)
        assertTrue(tail.cell(58, tail.height - 1).blue >
            tail.cell(58, tail.height - 1).red)
        bitmap.recycle()
    }

    @Test fun photoOverlayOnlyFeathersLastFourPercentNotWholeLowerQuarter() {
        val source = CapsuleColorField(64, 8, List(64 * 8) { Color(0xFFE9B078) })
        val overlay = immersiveSpatialFieldBitmap(
            bands = emptyList(), fallbackFloor = Color(0xFF29251D),
            coverFraction = 0.55f, tailFraction = 0.06f, texture = 0f,
            landscape = true, nearWhite = false, photoOverlay = true,
            edgeMap = source,
        ).asAndroidBitmap()
        assertTrue("Do not paint beige over the lower fifth of the cover",
            AndroidColor.alpha(overlay.getPixel(overlay.width / 2, overlay.height * 87 / 100)) == 0)
        assertTrue("Feather only the last few pixels",
            AndroidColor.alpha(overlay.getPixel(overlay.width / 2, overlay.height - 2)) > 180)
    }

    @Test fun continuationSettlesIntoExactlyTheExistingControlsFloor() {
        val source = CapsuleColorField(64, 8, List(64 * 8) { index ->
            if (index % 64 < 32) Color(0xFFE9B078) else Color(0xFF205ABC)
        })
        val floor = Color(0xFF29251D)
        val page = immersiveSpatialFieldBitmap(
            bands = emptyList(), fallbackFloor = floor,
            coverFraction = 0.55f, tailFraction = 0.06f, texture = 0f,
            landscape = true, nearWhite = false, photoOverlay = false,
            edgeMap = source,
        ).asAndroidBitmap()
        val bottom = page.getPixel(page.width / 2, page.height - 2)
        assertTrue(abs(AndroidColor.red(bottom) - 0x29) < 2)
        assertTrue(abs(AndroidColor.green(bottom) - 0x25) < 2)
        assertTrue(abs(AndroidColor.blue(bottom) - 0x1D) < 2)
        val earlyRow = ((0.55f + 0.007f) * page.height).toInt()
        val left = page.getPixel(page.width / 6, earlyRow)
        val right = page.getPixel(page.width * 5 / 6, earlyRow)
        assertTrue("Warm and cool edges retain their positions near the seam",
            AndroidColor.red(left) > AndroidColor.red(right))
        assertTrue("Blue right-side photo region stays on the right",
            AndroidColor.blue(right) > AndroidColor.blue(left))
    }
}
