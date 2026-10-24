package com.nikhil.yt.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.component.CapsuleSubscribeIcon
import java.io.File
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
class CapsuleSubscribeMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun plusCheckKeepsItsBoundsThroughForwardReverseAndInterruptedMotion() {
        var selected by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(48.dp).background(Color.Black).testTag("glyph")) {
                CapsuleSubscribeIcon(selected, Color.White, Modifier.size(48.dp))
            }
        }
        compose.mainClock.advanceTimeBy(32)
        val initial = compose.onNodeWithTag("glyph").fetchSemanticsNode().boundsInRoot
        compose.runOnUiThread { selected = true }
        for (frame in 0..4) {
            compose.mainClock.advanceTimeBy(80)
            val bounds = compose.onNodeWithTag("glyph").fetchSemanticsNode().boundsInRoot
            assertEquals(initial, bounds)
            compose.runOnUiThread {
                val bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap); canvas.translate(-bounds.left, -bounds.top)
                compose.activity.findViewById<View>(android.R.id.content).draw(canvas)
                val target = File("build/reports/ui-previews/subscribe-$frame.png")
                target.parentFile.mkdirs(); target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        compose.runOnUiThread { selected = false }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { selected = true }
        compose.mainClock.advanceTimeBy(400)
        assertEquals(initial, compose.onNodeWithTag("glyph").fetchSemanticsNode().boundsInRoot)
    }
}
