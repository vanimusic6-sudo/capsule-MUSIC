package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
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
        assertEquals(20, grid.width)
        assertEquals(16, grid.height)
        assertTrue(grid.cell(1, 1).red > grid.cell(1, 1).green)
        assertTrue(grid.cell(1, 8).green > grid.cell(1, 8).red)
        assertTrue(grid.cell(10, 5).blue > grid.cell(10, 5).red)
        assertTrue(capsuleFieldChanged(null, grid))
        assertFalse(capsuleFieldChanged(grid, grid))
        bitmap.recycle()
    }

    @Test fun actualSeamMatchesAndNarrowDarkStripeDoesNotExtendToControls() {
        val pale = Color(0xFFF6F5F2)
        val narrowBlack = Color(0xFF070707)
        val field = CapsuleColorField(20, 16,
            List(20 * 16) { index ->
                if (index % 20 < 2) narrowBlack else pale
            })
        val floor = Color(0xFF19151C)
        fun render(overlay: Boolean) = immersiveSpatialFieldBitmap(
            bands = emptyList(), fallbackFloor = floor,
            coverFraction = 0.5f, tailFraction = 0.055f, texture = 0f,
            landscape = false, nearWhite = true, photoOverlay = overlay,
            field = field,
        ).asAndroidBitmap()
        val page = render(false)
        val photo = render(true)
        fun rgb(pixel: Int, shift: Int): Int = pixel shr shift and 255
        val edgeX = page.width / 2
        val bottomPhoto = photo.getPixel(edgeX, photo.height - 1)
        val seamPage = page.getPixel(edgeX, page.height / 2)
        for (shift in listOf(16, 8, 0)) {
            assertTrue("Cover/page join must match without a thin gap",
                kotlin.math.abs(rgb(bottomPhoto, shift) - rgb(seamPage, shift)) < 26)
        }
        val upperY = (page.height * 0.17f).toInt()
        for (shift in listOf(16, 8, 0)) {
            assertTrue("Hidden upper page must not paint stripes beside video",
                kotlin.math.abs(
                    rgb(page.getPixel(2, upperY), shift) -
                        rgb(page.getPixel(page.width - 3, upperY), shift),
                ) <= 3)
        }
        val lowerY = (page.height * 0.90f).toInt()
        val left = page.getPixel(2, lowerY)
        val right = page.getPixel(page.width - 3, lowerY)
        for (shift in listOf(16, 8, 0)) {
            assertTrue("Isolated dark strip may not cast a long ray",
                kotlin.math.abs(rgb(left, shift) - rgb(right, shift)) <= 4)
        }
        assertTrue(capsuleDominantEdgeColor(field).red > 0.89f)
        page.recycle()
        photo.recycle()
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
