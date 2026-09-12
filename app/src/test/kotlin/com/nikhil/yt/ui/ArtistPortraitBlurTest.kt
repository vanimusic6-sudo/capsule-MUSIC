package com.nikhil.yt.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.nikhil.yt.ui.component.createArtistPortraitBlur
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtistPortraitBlurTest {
    @Test fun upperPortraitStaysSharpWhileDetailSoftensProgressivelyDownward() = runBlocking {
        val source = Bitmap.createBitmap(96, 160, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            source.setPixel(x, y, if (x % 8 < 4) Color.WHITE else Color.BLACK)
        }
        val blurred = createArtistPortraitBlur(source)
        for (y in 0 until 60) for (x in 0 until source.width) {
            assertEquals("The face area must remain unblurred", source.getPixel(x, y), blurred.getPixel(x, y))
        }
        fun contrast(y: Int): Float = (16 until 80).map {
            kotlin.math.abs(Color.red(blurred.getPixel(it, y)) - 127.5f)
        }.average().toFloat()
        val upper = contrast(40)
        val middle = contrast(104)
        val lower = contrast(152)
        assertTrue("Blur must become visible below the sharp face", middle < upper * 0.8f)
        assertTrue("Blur must grow toward the lower edge", lower < middle * 0.75f)
    }

    @Test fun cachedSourceIsNeitherChangedNorRecycled() = runBlocking {
        val source = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(174, 67, 29))
        source.setPixel(16, 44, Color.WHITE)
        val before = IntArray(32 * 48)
        source.getPixels(before, 0, 32, 0, 0, 32, 48)
        createArtistPortraitBlur(source)
        assertFalse(source.isRecycled)
        val after = IntArray(before.size)
        source.getPixels(after, 0, 32, 0, 0, 32, 48)
        assertTrue(before.contentEquals(after))
    }

    @Test fun tinyImagesAndFlatColoursHaveNoDarkEdges() = runBlocking {
        for ((width, height) in listOf(1 to 1, 1 to 80, 80 to 1, 48 to 64)) {
            val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.rgb(174, 67, 29))
            val blurred = createArtistPortraitBlur(source)
            for (y in 0 until height) for (x in 0 until width) {
                assertEquals(source.getPixel(x, y), blurred.getPixel(x, y))
            }
        }
    }
}
