package com.nikhil.yt.ui.component

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.component.shimmer.ShimmerHost
import com.nikhil.yt.ui.component.shimmer.rememberLoadingPlaceholderOpacity
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadingPlaceholderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun loadingBlockHasOneBrightnessAcrossAllRowsAndColumns() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.background(Color.Black)) {
                ShimmerHost(Modifier.testTag("loading"), fadeToSurface = false) {
                    Box(Modifier.size(240.dp, 40.dp).background(Color.White))
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.size(240.dp, 40.dp).background(Color.White))
                }
            }
        }
        val brightness = mutableListOf<Int>()
        repeat(6) {
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
            val bounds = compose.onNodeWithTag("loading").fetchSemanticsNode().boundsInRoot
            lateinit var bitmap: Bitmap
            compose.runOnIdle {
                bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.translate(-bounds.left, -bounds.top)
                compose.activity.findViewById<View>(android.R.id.content).draw(canvas)
            }
            val values = listOf(0.1f to 0.2f, 0.9f to 0.2f, 0.1f to 0.8f, 0.9f to 0.8f).map { (x, y) ->
                AndroidColor.red(bitmap.getPixel((bitmap.width * x).toInt(), (bitmap.height * y).toInt()))
            }
            assertTrue("No travelling wave or row delay: $values", values.max() - values.min() <= 2)
            brightness += values.first()
            bitmap.recycle()
        }
        assertTrue("The whole block still breathes softly", brightness.max() - brightness.min() >= 5)
    }

    @Test fun pulseDoesNotRecomposeItsContentAndStopsWhenInactive() {
        compose.mainClock.autoAdvance = false
        val active = mutableStateOf(true)
        lateinit var opacity: State<Float>
        var compositions = 0
        compose.setContent {
            compositions++
            opacity = rememberLoadingPlaceholderOpacity(active.value)
            Box(Modifier.size(20.dp).drawBehind { opacity.value })
        }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        val baseline = compositions
        repeat(4) {
            compose.mainClock.advanceTimeBy(200)
            compose.runOnIdle {
                assertTrue(opacity.value in 0.22f..0.30f)
                assertEquals(baseline, compositions)
            }
        }
        compose.runOnIdle { active.value = false }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(0.25f, opacity.value, 0.001f) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals(0.25f, opacity.value, 0.001f) }
    }
}
