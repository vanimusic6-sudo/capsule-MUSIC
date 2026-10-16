package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
        val result = image.immersiveArtworkBackground(0.95f)
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(result.toArgb(), hsv)
        assertTrue("A white paper background must stay grey, not green: saturation=${hsv[1]}", hsv[1] < 0.10f)
        assertTrue("White cover must no longer be clamped to dirty #707070", hsv[2] > 0.94f)
        image.recycle()
    }

    @Test fun redLowerBackgroundBeatsUnrelatedBlueAndGreenSubject() {
        val image = Bitmap.createBitmap(180, 100, Bitmap.Config.ARGB_8888)
        image.eraseColor(AndroidColor.rgb(163, 26, 51))
        for (y in 50 until 88) for (x in 77 until 107) {
            image.setPixel(x, y, if (y % 2 == 0) AndroidColor.BLUE else AndroidColor.GREEN)
        }
        val result = image.immersiveArtworkBackground(0.95f)
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
        val result = image.immersiveArtworkBackground(0.85f)
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(result.toArgb(), hsv)
        assertTrue("Off-screen green bars must not tint a white visible cover", hsv[1] < 0.10f)
        image.recycle()
    }
    @Test fun colouredLowerCreditsDoNotReplaceActualAlbumBackground() {
        val image = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val background = AndroidColor.rgb(235, 210, 181)
        image.eraseColor(background)
        // The old lower-strip detector would pick this full-width bright blue
        // foreground, even though it is not the cover's background.
        for (y in 175 until 232) for (x in 0 until 240) {
            image.setPixel(x, y, AndroidColor.rgb(35, 95, 224))
        }
        val actual = image.immersiveArtworkBackground(1f).toArgb()
        assertEquals("background must come from the cover, not its lower banner",
            background, actual)
        image.recycle()
    }

    @Test fun dominantBackgroundBeatsLargeCentralForegroundSubject() {
        val image = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val background = AndroidColor.rgb(187, 46, 74)
        image.eraseColor(background)
        // A huge foreground object covers most of the centre and even some
        // lower scan lines. Background remains connected to the image border.
        for (y in 34 until 212) for (x in 33 until 210) {
            image.setPixel(x, y, AndroidColor.rgb(34, 148, 239))
        }
        val colour = image.immersiveArtworkBackground(1f).toArgb()
        assertEquals("perimeter colour should beat central subject", background, colour)
        image.recycle()
    }

    @Test fun darkVideoLetterboxDoesNotBecomeBackgroundColour() {
        val image = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        val background = AndroidColor.rgb(184, 124, 88)
        image.eraseColor(background)
        for (y in 0 until 24) for (x in 0 until 240) image.setPixel(x, y, AndroidColor.BLACK)
        for (y in 136 until 160) for (x in 0 until 240) image.setPixel(x, y, AndroidColor.BLACK)
        val actual = image.immersiveArtworkBackground(1.5f).toArgb()
        assertEquals("black bars are video framing, not the album background", background, actual)
        image.recycle()
    }

    @Test fun allBlackArtworkRemainsBlackInsteadOfBeingRejectedAsBars() {
        val image = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        image.eraseColor(AndroidColor.BLACK)
        assertEquals(AndroidColor.BLACK, image.immersiveArtworkBackground(1.5f).toArgb())
        image.recycle()
    }

    @Test fun texturedBackgroundDoesNotAccidentallySelectSmallLettering() {
        val image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        // Deterministic, small deviations within one backdrop family.
        for (y in 0 until 256) for (x in 0 until 256) {
            val variation = ((x * 13 + y * 7) % 13) - 6
            image.setPixel(x, y, AndroidColor.rgb(
                193 + variation, 100 + variation, 63 + variation,
            ))
        }
        for (y in 178 until 225) for (x in 88 until 171) {
            if ((x / 4 + y / 3) % 3 != 0) image.setPixel(x, y, AndroidColor.WHITE)
        }
        val colour = image.immersiveArtworkBackground(1f).toArgb()
        val red = colour shr 16 and 255
        val green = colour shr 8 and 255
        val blue = colour and 255
        assertTrue("keep the textured background hue, not credits", red in 184..202)
        assertTrue(green in 90..110)
        assertTrue(blue in 54..74)
        image.recycle()
    }

    @Test fun mutedFloorPreservesColourfulExtractedHueInsteadOfBlack() {
        val source = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        source.eraseColor(AndroidColor.rgb(218, 39, 78))
        val sampled = source.immersiveArtworkBackground(0.95f)
        val muted = sampled.immersiveMutedAccent()
        val sourceHsv = FloatArray(3)
        val mutedHsv = FloatArray(3)
        AndroidColor.colorToHSV(sampled.toArgb(), sourceHsv)
        AndroidColor.colorToHSV(muted.toArgb(), mutedHsv)
        assertTrue("preserve original red hue", kotlin.math.abs(mutedHsv[0] - sourceHsv[0]) < 2f)
        assertTrue("preserve actual saturation with slight softening",
            kotlin.math.abs(mutedHsv[1] - sourceHsv[1] * 0.92f) < 0.025f)
        assertTrue("soften brightness rather than clamp to a dark destination",
            mutedHsv[2] > 0.64f && mutedHsv[2] < 0.70f)
        source.recycle()
    }

    @Test fun whiteMonochromeArtworkStaysNeutralWithoutInventedBlue() {
        val source = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        source.eraseColor(AndroidColor.WHITE)
        val sampled = source.immersiveArtworkBackground(0.95f)
        val muted = sampled.immersiveMutedAccent()
        assertEquals(AndroidColor.WHITE, sampled.toArgb())
        assertEquals("white has no hue to extract: do not invent blue",
            muted.red, muted.blue, 1f / 255f + 0.0001f)
        assertEquals("white stays neutral", muted.red, muted.green, 1f / 255f + 0.0001f)
        assertTrue("floor stays softly shaded, not near black", muted.red in 0.75f..0.81f)
        source.recycle()
    }

    @Test fun paleButColourfulAlbumKeepsItsOwnHueNotNeutralFallback() {
        val sample = androidx.compose.ui.graphics.Color(0xFFF0DDC8)
        val shaded = sample.immersiveMutedAccent()
        assertTrue("warm colour must stay warm", shaded.red > shaded.blue + 0.05f)
        assertTrue("bright colour must be gently darkened", shaded.red < sample.red)
    }

    @Test fun sampledNearWhiteAndPastelCoversStayBrightInsteadOfTurningGrey() {
        for (expected in listOf(
            AndroidColor.WHITE,
            AndroidColor.rgb(245, 245, 245),
            AndroidColor.rgb(240, 224, 204),
            AndroidColor.rgb(202, 228, 246),
        )) {
            val image = Bitmap.createBitmap(180, 100, Bitmap.Config.ARGB_8888)
            image.eraseColor(expected)
            val actual = image.immersiveArtworkBackground(0.95f).toArgb()
            assertEquals(
                "Immersion must use the actual album colour without forced HSV brightness",
                expected,
                actual,
            )
            image.recycle()
        }
    }

}
