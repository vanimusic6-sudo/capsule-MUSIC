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

    @Test fun widerDiffusionUsesMoreArtworkWithoutChangingThePaletteSample() {
        val image = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            image.setPixel(x, y, if (y < 174) AndroidColor.BLUE else AndroidColor.RED)
        }
        val palette = image.capsuleImmersiveVisibleTail(0.6f, false)
        val overlap = image.capsuleImmersiveVisibleTail(0.6f, false,
            fraction = 1f - IMMERSIVE_DIFFUSION_START)
        assertTrue(overlap.height > palette.height)
        assertTrue(overlap.cell(40, 0).blue > 0.9f)
        assertTrue(palette.cell(40, 0).red > 0.9f)
        val floor = capsuleCleanImmersiveFloor(palette)
        assertTrue("Extra photographic overlap cannot tint the floor blue", floor.red > floor.blue)
        image.recycle()
    }

    @Test fun smallWhiteEdgeLabelCannotBecomeABroadWhitePatch() {
        val dark = Color(0xFF121117)
        val source = CapsuleColorField(96, 34, List(96 * 34) { i ->
            if (i % 96 >= 91 && i / 96 >= 29) Color.White else dark
        })
        val image = immersiveSpatialFieldBitmap(emptyList(), dark,
            0.55f, 0.06f, 0f, false, false, false, edgeMap = source).asAndroidBitmap()
        val y = (image.height * 0.58f).toInt()
        val maximum = (0 until image.width).maxOf { AndroidColor.red(image.getPixel(it, y)) }
        assertTrue("A label occupying less than 1% of the photo cannot light the page: $maximum", maximum < 42)
        image.recycle()
    }

    @Test fun sparseWhiteMarkProtectionDoesNotSuppressARealColouredLightSource() {
        val dark = Color(0xFF121117)
        val source = CapsuleColorField(96, 34, List(96 * 34) { i ->
            if (i % 96 >= 85) Color(0xFFFF4810) else dark
        })
        val image = immersiveSpatialFieldBitmap(emptyList(), dark,
            0.55f, 0.06f, 0f, false, false, false, edgeMap = source).asAndroidBitmap()
        val y = (image.height * 0.58f).toInt()
        assertTrue("A real red flame must retain its own light; only pale marks are limited",
            AndroidColor.red(image.getPixel(image.width - 2, y)) > 80)
        image.recycle()
    }

    @Test fun progressiveSoftArtworkRemovesFineTextureBeforeThePhysicalEdge() {
        val bitmap = Bitmap.createBitmap(320, 280, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            bitmap.setPixel(x, y, if ((x / 2 + y / 2) % 2 == 0) AndroidColor.WHITE else AndroidColor.BLACK)
        }
        val soft = capsuleSoftArtwork(bitmap, 320f / 280f, false).asAndroidBitmap()
        assertTrue(AndroidColor.alpha(soft.getPixel(30, soft.height / 3)) == 0)
        val y = (soft.height * 0.83f).toInt()
        val samples = (40..180).map { AndroidColor.red(soft.getPixel(it, y)) }
        assertTrue("Dense ink must actually diffuse, rather than just becoming translucent",
            requireNotNull(samples.maxOrNull()) - requireNotNull(samples.minOrNull()) < 14)
        assertTrue(AndroidColor.alpha(soft.getPixel(60, y)) > 245)
        bitmap.recycle(); soft.recycle()
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

    @Test fun fineStripedHairBelowPhotoGetsDiffusedRatherThanProjectedVertically() {
        // This is the screenshot failure: 1px black hair/face fragments at
        // the bottom edge became dark columns down the whole screen.
        val stripes = CapsuleColorField(96, 12, List(96 * 12) { i ->
            if (i % 96 % 6 < 3) Color(0xFF050609) else Color(0xFF637C9E)
        })
        val floor = Color(0xFF1B1D23)
        val continuation = immersiveSpatialFieldBitmap(
            bands = emptyList(), fallbackFloor = floor,
            coverFraction = 0.57f, tailFraction = 0.06f,
            texture = 0.4f, landscape = false,
            nearWhite = false, photoOverlay = false, edgeMap = stripes,
        ).asAndroidBitmap()
        val y = (0.60f * continuation.height).toInt()
        val left = continuation.getPixel(continuation.width * 5 / 11, y)
        val right = continuation.getPixel(continuation.width * 6 / 11, y)
        assertTrue("High-frequency dark bars must not remain visible as vertical beams",
            kotlin.math.abs(AndroidColor.blue(left) - AndroidColor.blue(right)) < 13)
    }

    @Test fun realPhotoToBlurTransitionHasNoSingleRowColourJump() {
        val source = CapsuleColorField(96, 12, List(96 * 12) { i ->
            val x = i % 96
            when {
                x < 31 -> Color(0xFF080B15)
                x < 69 -> Color(0xFF557598)
                else -> Color(0xFFAA4D65)
            }
        })
        val image = immersiveSpatialFieldBitmap(
            bands = emptyList(),
            fallbackFloor = Color(0xFF1A1C22),
            coverFraction = 0.55f, tailFraction = 0.06f,
            texture = 0.3f, landscape = false,
            nearWhite = false, photoOverlay = false, edgeMap = source,
        ).asAndroidBitmap()
        val before = (0.55f * image.height).toInt() - 1
        val after = before + 1
        for (x in listOf(8, image.width / 2, image.width - 9)) {
            val above = image.getPixel(x, before)
            val below = image.getPixel(x, after)
            assertTrue("No abrupt switch from unblurred photo row to diffuse page",
                abs(AndroidColor.red(above) - AndroidColor.red(below)) < 13)
            assertTrue("No blue-channel seam at album boundary",
                abs(AndroidColor.blue(above) - AndroidColor.blue(below)) < 13)
        }
    }
}
