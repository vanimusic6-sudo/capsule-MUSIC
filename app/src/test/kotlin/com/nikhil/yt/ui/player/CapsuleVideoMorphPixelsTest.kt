package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsuleVideoMorphPixelsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun contentHandoverFinishesEarlyAndClosingRetainsTheVideoInitially() {
        val presenting = mutableStateOf(false)
        val needed = mutableStateOf(true)
        compose.setContent {
            CapsuleVideoArtworkTransition(
                presentingVideo = presenting.value,
                needsSurface = needed.value,
                fillArtworkSlot = true,
                modifier = Modifier.size(320.dp, 280.dp).testTag("media-slot"),
                // Red is a live decoder frame in VIDEO, and its retained exit still in AUDIO.
                video = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                artwork = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
            )
        }
        assertEquals(AndroidColor.BLUE, centre("audio"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { presenting.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        assertEquals("cover remains opaque until the silhouette has moved", AndroidColor.BLUE, centre("open-64"))
        repeat(10) {
            compose.mainClock.advanceTimeBy(16)
            assertOpaqueContent("opening-$it")
        }
        compose.mainClock.advanceTimeBy(26)
        // Content handover is short; the rest of the physical settling is opaque.
        assertEquals(AndroidColor.RED, centre("open-250"))
        compose.mainClock.advanceTimeBy(500)
        assertEquals(AndroidColor.RED, centre("video"))
        compose.runOnIdle { needed.value = false; presenting.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        assertEquals("closing must initially retain the video's still", AndroidColor.RED, centre("close-32"))
        repeat(6) {
            compose.mainClock.advanceTimeBy(16)
            assertOpaqueContent("closing-$it")
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(AndroidColor.BLUE, centre("closed"))
    }

    private fun assertOpaqueContent(name: String) {
        val pixel = centre(name)
        assertTrue("$name must show one opaque content, never blended layers or an empty frame",
            pixel == AndroidColor.RED || pixel == AndroidColor.BLUE)
    }

    @Test fun immersiveUsesOneSoftRectangularHandoverAndSettlesWithoutAnEmptyFrame() {
        val presenting = mutableStateOf(false)
        val needed = mutableStateOf(true)
        compose.setContent {
            CapsuleVideoArtworkTransition(
                presentingVideo = presenting.value, needsSurface = needed.value,
                motion = CapsuleVideoArtworkMotion.IMMERSIVE, fillArtworkSlot = true,
                artworkCornerRadius = 0.dp,
                modifier = Modifier.size(320.dp, 440.dp).testTag("media-slot"),
                video = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                artwork = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
            )
        }
        assertEquals(AndroidColor.BLUE, centre("immersive-audio"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { presenting.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        assertEquals("cover survives the beginning of the silhouette movement", AndroidColor.BLUE, centre("immersive-open-64"))
        compose.mainClock.advanceTimeBy(96)
        assertRectangularBlend("immersive-opening")
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(AndroidColor.RED, centre("immersive-video"))
        compose.runOnIdle { needed.value = false; presenting.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(160)
        assertRectangularBlend("immersive-closing")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(AndroidColor.BLUE, centre("immersive-closed"))
    }

    private fun assertRectangularBlend(name: String) {
        val bitmap = snapshot(name)
        try {
            assertTrue("the centre never becomes an empty frame",
                bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) != AndroidColor.BLACK)
            val centre = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue("the handover is soft, with both opaque contents covering the same frame",
                AndroidColor.red(centre) in 20..235 && AndroidColor.blue(centre) in 20..235)
            // A radial mask would reveal a different colour away from the centre.
            for (x in listOf(bitmap.width / 2 - 60, bitmap.width / 2 + 60)) {
                val edge = bitmap.getPixel(x, bitmap.height / 2)
                assertTrue(kotlin.math.abs(AndroidColor.red(centre) - AndroidColor.red(edge)) <= 3)
                assertTrue(kotlin.math.abs(AndroidColor.blue(centre) - AndroidColor.blue(edge)) <= 3)
            }
        } finally { bitmap.recycle() }
    }

    private fun centre(snapshotName: String): Int {
        val bitmap = snapshot(snapshotName)
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2).also { bitmap.recycle() }
    }

    private fun snapshot(snapshotName: String): Bitmap {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("media-slot").fetchSemanticsNode().boundsInRoot
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            compose.activity.findViewById<View>(android.R.id.content).draw(canvas)
            val output = File("build/video-morph-preview/$snapshotName.png")
            output.parentFile.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }
}
