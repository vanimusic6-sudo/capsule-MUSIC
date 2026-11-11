package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A true 2D source MUST respond to both X and Y. The retired seven-band
 * system could never pass the vertical-difference assertions below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CapsuleColorFieldTest {
    @Test fun decodedArtworkPreservesTopBottomAndLeftRightSpatialDifferences() {
        val bitmap = Bitmap.createBitmap(120, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 120) {
            val pixel = when {
                x >= 60 -> AndroidColor.rgb(18, 48, 205)
                y < 50 -> AndroidColor.rgb(215, 50, 27)
                else -> AndroidColor.rgb(45, 200, 62)
            }
            bitmap.setPixel(x, y, pixel)
        }
        val grid = bitmap.capsuleColorField(1.2f)
        assertEquals(12, grid.width)
        assertEquals(10, grid.height)
        assertTrue(grid.cell(1, 1).red > grid.cell(1, 1).green)
        assertTrue(grid.cell(1, 8).green > grid.cell(1, 8).red)
        assertTrue(grid.cell(10, 5).blue > grid.cell(10, 5).red)
        assertTrue(capsuleFieldChanged(null, grid))
        assertFalse(capsuleFieldChanged(grid, grid))
        bitmap.recycle()
    }

    @Test fun interruptedFieldTransitionStartsFromCurrentVisibleColours() {
        val red = CapsuleColorField(2, 2, List(4) { androidx.compose.ui.graphics.Color.Red })
        val blue = CapsuleColorField(2, 2, List(4) { androidx.compose.ui.graphics.Color.Blue })
        val current = capsuleFieldAt(red, blue, 0.4f)
        assertNotNull(current)
        val resumed = capsuleFieldAt(current, blue, 0f)
        assertEquals(current, resumed)
        assertEquals(red, capsuleFieldAt(red, blue, 0f))
        assertEquals(blue, capsuleFieldAt(red, blue, 1f))
    }
}
