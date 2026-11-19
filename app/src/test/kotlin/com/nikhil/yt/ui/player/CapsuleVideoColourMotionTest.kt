package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lock the exact soft-image VIDEO backdrop from the 2026-11-13 player design.
 * The Android 13+ player-design path uses a low-frequency 2D shader and a
 * small GPU layer, not a full-screen upscaled raster gradient of wide bands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsuleVideoColourMotionTest {
    @Test fun restoredPlayerDesignUsesGpuSpatialBlendAndOriginalCadence() {
        assertEquals("The slow raster crossfade is the source of large visible bands",
            180, VIDEO_AMBIENT_BLEND_DURATION_MS)
        val shader = CAPSULE_VIDEO_MOTION_SHADER
        assertTrue(shader.contains("source * grid"))
        assertTrue(shader.contains("uv.x"))
        assertTrue(shader.contains("uv.y"))
        assertTrue(shader.contains("mix(previous.eval"))
        assertTrue(shader.contains("floorColor"))
    }

    @Test fun originalDesignRendersASoft176x256ImageRatherThanStretchedGridPixels() {
        val field = CapsuleColorField(20, 16, List(320) { Color(0xFF8AB1D6) })
        val bitmap = capsuleVideoSpatialBitmap(field, Color(0xFF111116)).asAndroidBitmap()
        try {
            assertEquals(176, bitmap.width)
            assertEquals(256, bitmap.height)
            val centre = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            val atLeft = bitmap.getPixel(bitmap.width / 8, bitmap.height / 2)
            val atRight = bitmap.getPixel(bitmap.width * 7 / 8, bitmap.height / 2)
            val lower = bitmap.getPixel(bitmap.width / 2, bitmap.height * 7 / 8)
            assertTrue("The old design's edge vignette is subtle, not a large stripe",
                abs(AndroidColor.blue(centre) - AndroidColor.blue(atLeft)) < 22)
            assertTrue(abs(AndroidColor.blue(centre) - AndroidColor.blue(atRight)) < 22)
            assertTrue(AndroidColor.blue(lower) < AndroidColor.blue(centre))
            assertEquals(255, AndroidColor.alpha(centre))
        } finally { bitmap.recycle() }
    }

    @Test fun originalDesignKeepsRealVideoColoursInBothAxes() {
        val field = CapsuleColorField(20, 16, List(320) { index ->
            when {
                index / 20 < 5 -> Color(0xFF4CE985)
                index % 20 < 10 -> Color(0xFFE64A47)
                else -> Color(0xFF456EF3)
            }
        })
        val bitmap = capsuleVideoSpatialBitmap(field, Color(0xFF111116)).asAndroidBitmap()
        try {
            val top = bitmap.getPixel(bitmap.width / 2, bitmap.height / 8)
            val lowerLeft = bitmap.getPixel(bitmap.width / 5, bitmap.height * 4 / 5)
            val lowerRight = bitmap.getPixel(bitmap.width * 4 / 5, bitmap.height * 4 / 5)
            assertTrue(AndroidColor.green(top) > AndroidColor.green(lowerLeft))
            assertTrue(AndroidColor.red(lowerLeft) > AndroidColor.red(lowerRight))
            assertTrue(AndroidColor.blue(lowerRight) > AndroidColor.blue(lowerLeft))
        } finally { bitmap.recycle() }
    }

    @Test fun alternatingSmallVideoStripesDiffuseBeforeUpsampling() {
        val alternating = CapsuleColorField(20, 16, List(320) { index ->
            if (index % 20 % 2 == 0) Color.Red else Color.Blue
        })
        val bitmap = capsuleVideoSpatialBitmap(alternating, Color(0xFF101016)).asAndroidBitmap()
        try {
            val y = bitmap.height / 2
            var largestRedJump = 0
            for (x in 1 until bitmap.width) {
                largestRedJump = maxOf(largestRedJump,
                    abs(AndroidColor.red(bitmap.getPixel(x, y)) -
                        AndroidColor.red(bitmap.getPixel(x - 1, y))))
            }
            assertTrue("No visible wide band boundary should survive diffusion", largestRedJump < 12)
        } finally { bitmap.recycle() }
    }

    @Test fun legacyVideoLightFilterRemainsAvailableForOldSavedCacheRecords() {
        val filter = CapsuleVideoLightFilter()
        val dark = CapsuleColorField(20, 16, List(320) { Color.Black })
        val bright = CapsuleColorField(20, 16, List(320) { Color.White })
        filter.apply(dark, 1_000_000_000L)
        val flash = filter.apply(bright, 1_100_000_000L)
        assertTrue(flash.cells.first().red < 0.45f)
    }
}
