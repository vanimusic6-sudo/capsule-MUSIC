package com.nikhil.yt.ui.component

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsSparseClockRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun actualRestoredWordFillsEveryFrameWhenThePlayerRepeatsItsPositionFor267ms() {
        compose.mainClock.autoAdvance = false
        val clock = LyricsPlaybackClock()
        val position = mutableLongStateOf(clock.update(500L, 0L, 1f, true, 6_000L))
        val focus = mutableStateOf(1f)
        compose.setContent {
            Box(Modifier.size(300.dp, 120.dp).background(Color.Black).testTag("lyrics-sparse-scene")) {
                ArchiveTuneWord("Capsule", 0L, 2_000L, position, false, 40.sp, Color.White,
                    false, focus, true)
            }
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val images = mutableSetOf<Int>()
        repeat(15) { frame ->
            compose.runOnIdle {
                // EXACTLY the same source position in every sample, unlike the
                // previous renderer test which fed an already smooth clock.
                position.longValue = clock.update(500L, (frame + 1) * 16_666_667L, 1f, true, 6_000L)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            images += snapshotHash()
        }
        assertEquals("All 15 rendered frames must differ while Media3's source remains at 500 ms", 15, images.size)
    }

    private fun snapshotHash(): Int {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("lyrics-sparse-scene").fetchSemanticsNode().boundsInRoot
        var hash = 0
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            compose.activity.findViewById<View>(android.R.id.content).draw(canvas)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            hash = pixels.contentHashCode()
            bitmap.recycle()
        }
        return hash
    }
}
