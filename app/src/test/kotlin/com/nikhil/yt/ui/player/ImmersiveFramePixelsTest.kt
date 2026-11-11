package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ImmersiveFramePixelsTest {
    private fun cover(width: Int = 200, height: Int = 200, pixel: (Int, Int) -> Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) setPixel(x, y, pixel(x, y))
        }

    private fun frame(image: Bitmap, aspect: Float = 1f) = prepareImmersiveFrame(
        "selected-track", ImmersiveArtworkTone(preview = image.asImageBitmap(), ready = true),
        aspect, 0.5f, 0.04f,
    )

    @Test fun narrowBlackBorderDissolvesBeforeTheFloorWithoutDirtyingWhite() {
        val result = frame(cover { x, _ -> if (x < 20) AndroidColor.BLACK else AndroidColor.WHITE })
        assertTrue(result.tone.edge.red > 0.98f)
        assertEquals(result.tone.edge.red, result.tone.edge.blue, 0.001f)
        val page = result.page.asAndroidBitmap()
        for (y in (page.height * 0.62f).toInt() until page.height) {
            val left = page.getPixel(2, y)
            val right = page.getPixel(page.width - 3, y)
            assertTrue(kotlin.math.abs(AndroidColor.red(left) - AndroidColor.red(right)) <= 2)
        }
        val floor = Color(page.getPixel(page.width / 2, page.height - 1))
        assertTrue("Pale artwork must not collapse to an almost-black floor", floor.red > 0.40f)
        assertEquals(floor.red, floor.blue, 0.01f)
    }

    @Test fun texturedBrightMajorityBeatsAUniformDarkMinority() {
        val result = frame(cover { x, y ->
            if (x < 60) AndroidColor.BLACK
            else AndroidColor.rgb(180 + (x + y) % 70, 80 + x % 70, 25 + y % 30)
        })
        assertTrue(result.tone.edge.red > 0.6f)
        assertTrue(result.tone.edge.red > result.tone.edge.blue * 3f)
    }

    @Test fun saturatedOrangeRetainsItsHueAndDarkCoverIsNotArtificiallyTinted() {
        val warm = frame(cover { _, _ -> AndroidColor.rgb(240, 90, 15) })
        val lower = Color(warm.page.asAndroidBitmap().getPixel(72, 160))
        assertTrue(lower.red > lower.green * 2.4f)
        assertTrue(lower.green > lower.blue * 4f)
        assertTrue(lower.red > 0.40f)
        val dark = capsuleImmersiveFloorColor(Color(0xFF111111))
        assertEquals(Color(0xFF111111), dark)
    }

    @Test fun fieldUsesActualDisplayedCropAndRetainsTheSameOwnedImage() {
        val source = cover(400, 200) { x, _ ->
            if (x < 100 || x >= 300) AndroidColor.GREEN else AndroidColor.rgb(200, 30, 50)
        }
        val result = frame(source, 0.75f)
        assertTrue(result.tone.edge.red > result.tone.edge.green * 3f)
        assertSame(source, result.tone.preview!!.asAndroidBitmap())
        assertEquals("selected-track", result.key)
    }

    @Test fun restoredColourColumnsRemainVisibleOnBothSidesOfTheJoin() {
        val result = frame(cover { x, _ ->
            if (x < 100) AndroidColor.rgb(220, 40, 45) else AndroidColor.rgb(25, 65, 220)
        })
        val page = result.page.asAndroidBitmap()
        val photo = result.overlay.asAndroidBitmap()
        for (y in listOf(page.height / 2, (page.height * 0.72f).toInt(), page.height - 1)) {
            val left = Color(page.getPixel(4, y))
            val right = Color(page.getPixel(page.width - 5, y))
            assertTrue("Red side must continue as colour, not a flat fill", left.red > right.red + 0.09f)
            assertTrue("Blue side must continue as colour, not a flat fill", right.blue > left.blue + 0.09f)
        }
        for (x in listOf(4, 72, page.width - 5)) {
            val end = photo.getPixel(x, photo.height - 1)
            val join = page.getPixel(x, page.height / 2)
            for (shift in listOf(16, 8, 0)) {
                assertTrue(kotlin.math.abs((end shr shift and 255) - (join shr shift and 255)) < 8)
            }
        }
    }

    @Test fun smallBlueCentreOnDarkArtStillContinuesIntoTheFloor() {
        val result = frame(cover { x, _ ->
            if (x in 65..135) AndroidColor.rgb(25, 80, 155) else AndroidColor.rgb(15, 17, 20)
        })
        val centre = Color(result.page.asAndroidBitmap().getPixel(72, 130))
        assertTrue("Dark art must retain its blue detail", centre.blue > centre.red + 0.06f)
    }

    @Test fun darkForegroundCannotEraseTexturedBlueInTheDissolveRegion() {
        val result = frame(cover { x, y ->
            if (x in 65..135 && y in 105..145)
                AndroidColor.rgb(15 + x % 12, 55 + y % 35, 100 + (x + y) % 110)
            else AndroidColor.rgb(12, 13, 15)
        })
        val centre = Color(result.page.asAndroidBitmap().getPixel(72, 126))
        assertTrue("Foreground black must not erase the blue behind it", centre.blue > centre.red + 0.055f)
    }

    @Test fun photoAndPageAgreeAtJoinAndFeatheringIsGradual() {
        val result = frame(cover { _, _ -> AndroidColor.rgb(195, 98, 35) })
        val photo = result.overlay.asAndroidBitmap()
        val page = result.page.asAndroidBitmap()
        assertEquals(0, AndroidColor.alpha(photo.getPixel(72, (photo.height * 0.55f).toInt())))
        assertTrue(AndroidColor.alpha(photo.getPixel(72, (photo.height * 0.80f).toInt())) in 40..220)
        assertEquals(255, AndroidColor.alpha(photo.getPixel(72, photo.height - 1)))
        val end = photo.getPixel(72, photo.height - 1)
        val join = page.getPixel(72, page.height / 2)
        for (shift in listOf(16, 8, 0)) {
            assertTrue(kotlin.math.abs((end shr shift and 255) - (join shr shift and 255)) < 5)
        }
    }
}
