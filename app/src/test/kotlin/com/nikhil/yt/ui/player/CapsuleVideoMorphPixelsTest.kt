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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsuleVideoMorphPixelsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun openingUsesAnOpaqueApertureAndClosingDoesNotSnapStraightToTheCover() {
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
        assertEquals(AndroidColor.BLUE, centre())
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { presenting.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(120)
        // A crossfade would produce purple here. The new aperture shows the untouched video.
        assertEquals(AndroidColor.RED, centre())
        compose.mainClock.advanceTimeBy(500)
        assertEquals(AndroidColor.RED, centre())
        compose.runOnIdle { needed.value = false; presenting.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(120)
        assertEquals("closing must initially retain the video's still", AndroidColor.RED, centre())
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(AndroidColor.BLUE, centre())
    }

    private fun centre(): Int {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("media-slot").fetchSemanticsNode().boundsInRoot
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            compose.activity.findViewById<View>(android.R.id.content).draw(canvas)
        }
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2).also { bitmap.recycle() }
    }
}
