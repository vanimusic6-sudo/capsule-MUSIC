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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
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
class ArchiveTuneWordRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun actualLyricWordRedrawsEveryFrameWithoutReshapingOrMeasuringText() {
        compose.mainClock.autoAdvance = false
        val position = mutableLongStateOf(200L)
        val focus = mutableStateOf(1f)
        val fontSize = mutableStateOf(40.sp)
        var textLayouts = 0
        var measures = 0
        var laidOutFontSize = 0.sp
        compose.setContent {
            Box(Modifier.size(300.dp, 120.dp).background(Color.Black).testTag("lyrics-word-scene")) {
                ArchiveTuneWord("Capsule", 0L, 2_000L, position, false, fontSize.value, Color.White,
                    false, focus, true,
                    modifier = Modifier.layout { measurable, constraints ->
                        measures++
                        val word = measurable.measure(constraints)
                        layout(word.width, word.height) { word.place(0, 0) }
                    },
                    onTextLayout = { textLayouts++; laidOutFontSize = it.layoutInput.style.fontSize },
                )
            }
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val baselineLayouts = textLayouts
        val baselineMeasures = measures
        assertTrue(baselineLayouts > 0)
        val images = mutableSetOf<Int>()
        repeat(10) { frame ->
            compose.runOnIdle { position.longValue = 500L + frame * 16L }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            images += snapshotHash()
            assertEquals("Playback frames must reuse glyph layout", baselineLayouts, textLayouts)
            assertEquals("Playback frames must not remeasure/reflow words", baselineMeasures, measures)
        }
        assertEquals("The lyric itself must advance on every frame", 10, images.size)
        compose.onAllNodesWithText("Capsule").assertCountEquals(1)
        compose.runOnIdle { focus.value = 0.5f }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        assertEquals(baselineLayouts, textLayouts)
        assertEquals(baselineMeasures, measures)
        compose.runOnIdle { fontSize.value = 48.sp }
        // Text layout/draw is driven by Android as well as the Compose clock.
        // Let host passes finish instead of waiting only on virtual animation time.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        snapshotHash()
        compose.waitUntil(timeoutMillis = 5_000L) { textLayouts > baselineLayouts && laidOutFontSize == 48.sp }
        compose.waitForIdle()
        assertTrue("An actual typography change must still invalidate the layout", textLayouts > baselineLayouts)
        assertEquals(48.sp, laidOutFontSize)
        assertTrue(measures > baselineMeasures)
    }

    @Test fun paintPreservesTheExistingWaveAndCompletedLineTail() {
        val active = archiveTuneWordPaint(500L, 0L, 1_000L, 1f, true, false, false)
        assertEquals(1f, active.scale, 0.0001f)
        assertEquals(-3.2f, active.lift, 0.0001f)
        assertEquals(0.34f, active.glowAlpha, 0.0001f)
        assertTrue(active.mask)
        val completed = archiveTuneWordPaint(Long.MIN_VALUE, 0L, 1_000L, 0.5f, false, true, false)
        assertEquals(1f, completed.progress, 0f)
        assertEquals(0.5f, completed.revealAlpha, 0f)
        assertFalse(completed.mask)
        assertEquals(0f, completed.glowAlpha, 0f)
    }

    private fun snapshotHash(): Int {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("lyrics-word-scene").fetchSemanticsNode().boundsInRoot
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
