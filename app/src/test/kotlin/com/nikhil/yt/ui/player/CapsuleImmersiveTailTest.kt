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

    @Test fun photoNeverGetsATintedOverlayOrHardBoundaryStripe() {
        val source = CapsuleColorField(64, 8, List(64 * 8) { Color(0xFFE9B078) })
        val overlay = immersiveSpatialFieldBitmap(
            bands = emptyList(), fallbackFloor = Color(0xFF29251D),
            coverFraction = 0.55f, tailFraction = 0.06f, texture = 0f,
            landscape = true, nearWhite = false, photoOverlay = true,
            edgeMap = source,
        ).asAndroidBitmap()
        assertTrue("Do not paint beige over the lower fifth of the cover",
            AndroidColor.alpha(overlay.getPixel(overlay.width / 2, overlay.height * 87 / 100)) == 0)
        assertTrue("The lower edge must not be repainted or washed out",
            AndroidColor.alpha(overlay.getPixel(overlay.width / 2, overlay.height - 2)) == 0)
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

    @Test fun floorUsesLastVisibleArtworkRowsNotHigherSky() {
        val tail = CapsuleColorField(64, 8, List(64 * 8) { index ->
            // Distant cool sky above the near-bottom warm desert.
            if (index / 64 < 5) Color(0xFF68A4C0) else Color(0xFFC99B65)
        })
        val floor = capsuleCleanImmersiveFloor(tail)
        assertTrue("Visible lower sand, not blue sky, must determine floor",
            floor.red > floor.blue && floor.red > floor.green)
        assertTrue("Controls must retain contrast", floor.red < 0.34f)
    }

    @Test fun nearlyGrayscaleLowerCoverCannotTurnBrownOrBlue() {
        val tail = CapsuleColorField(64, 8, List(64 * 8) { Color(0xFFBDBDBD) })
        val floor = capsuleCleanImmersiveFloor(tail)
        assertTrue(abs(floor.red - floor.green) < 0.005f)
        assertTrue(abs(floor.green - floor.blue) < 0.005f)
    }

    @Test fun blackSleeveWithWhiteLogoKeepsTrueNeutralDarkFloor() {
        val source = CapsuleColorField(64, 12, List(64 * 12) { index ->
            if (index % 64 in 20..40 && index / 64 in 8..9) Color.White
            else Color(0xFF080809)
        })
        val floor = capsuleCleanImmersiveFloor(source)
        assertTrue("White text cannot turn a black cover into grey fog", floor.red < 0.15f)
        assertTrue(abs(floor.red - floor.green) < 0.004f)
    }

    @Test fun fireRedNearBottomKeepsItsColourInsteadOfMixingToMud() {
        val source = CapsuleColorField(64, 12, List(64 * 12) { index ->
            if (index / 64 >= 8 && index % 64 < 52) Color(0xFFE52411)
            else Color(0xFF060607)
        })
        val floor = capsuleCleanImmersiveFloor(source)
        assertTrue("Real fire must produce a dark red light, not muddy grey",
            floor.red > floor.green * 1.6f && floor.red > floor.blue * 1.3f)
        assertTrue(floor.red < 0.32f)
    }

    @Test fun continuationRemainsVisibleWellBelowTheTitleInsteadOfSnappingToFlatPaint() {
        val bright = Color(0xFFE35B24)
        val source = CapsuleColorField(64, 12, List(64 * 12) { bright })
        val floor = Color(0xFF17171A)
        val image = immersiveSpatialFieldBitmap(
            bands = emptyList(), fallbackFloor = floor,
            coverFraction = 0.55f, tailFraction = 0.06f, texture = 0f,
            landscape = false, nearWhite = false, photoOverlay = false,
            edgeMap = source,
        ).asAndroidBitmap()
        val middle = image.getPixel(image.width / 2, (image.height * 0.76f).toInt())
        val deep = image.getPixel(image.width / 2, (image.height * 0.98f).toInt())
        assertTrue("Real light must still be present below the old 14%-screen fade",
            AndroidColor.red(middle) > AndroidColor.red(deep) + 18)
        assertTrue("The final page must settle into the exact controls floor",
            abs(AndroidColor.red(deep) - 0x17) < 2)
    }

    @Test fun continuationAlreadyExistsUnderThePhotoAlphaFeather() {
        val bright = Color(0xFFD8AA61)
        val source = CapsuleColorField(64, 12, List(64 * 12) { bright })
        val floor = Color(0xFF15151A)
        val image = immersiveSpatialFieldBitmap(
            bands = emptyList(), fallbackFloor = floor,
            coverFraction = 0.55f, tailFraction = 0.06f, texture = 0f,
            landscape = false, nearWhite = false, photoOverlay = false,
            edgeMap = source,
        ).asAndroidBitmap()
        val beforeJoin = image.getPixel(image.width / 2, (image.height * 0.535f).toInt())
        val atJoin = image.getPixel(image.width / 2, (image.height * 0.55f).toInt())
        assertTrue("The real photo must fade into photo-derived light, not a dark hole",
            AndroidColor.red(beforeJoin) > 140)
        assertTrue("The join must preserve the source red before dimming",
            AndroidColor.red(atJoin) > 140)
    }
}
