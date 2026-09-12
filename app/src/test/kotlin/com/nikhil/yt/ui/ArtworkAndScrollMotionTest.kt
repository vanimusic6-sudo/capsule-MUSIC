package com.nikhil.yt.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.AlbumArtworkLayers
import com.nikhil.yt.ui.component.ScrollActionButton
import com.nikhil.yt.ui.component.createAlbumArtworkBlur
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkAndScrollMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(tag: String, name: String? = null): Bitmap {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            compose.activity.findViewById<View>(android.R.id.content).draw(canvas)
        }
        if (name != null) {
            val file = File("build/reports/ui-previews/$name.png")
            file.parentFile.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    @Test fun albumFadesEquallyIntoThePageAtBothEdges() {
        val cover = Bitmap.createBitmap(200, 236, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }.asImageBitmap()
        compose.setContent {
            AlbumArtworkLayers(BitmapPainter(cover), cover, Color(0xFF080808), Modifier.width(200.dp).testTag("album"))
        }
        val frame = capture("album", "album-symmetric-fade")
        val x = frame.width / 2
        for (y in 0 until frame.height / 2) {
            assertTrue("Top and bottom must match at row $y", abs(red(frame, x, y) - red(frame, x, frame.height - 1 - y)) <= 2)
        }
        assertTrue(red(frame, x, 0) in 8..10)
        assertTrue(red(frame, x, frame.height / 2) > 250)
        assertTrue(red(frame, x, frame.height / 8) in 40..150)
    }

    @Test fun albumBlurSoftensOnlyTheEdgesAndKeepsTheCentreSharp() {
        val bitmap = Bitmap.createBitmap(200, 236, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            bitmap.setPixel(x, y, if (x / 4 % 2 == 0) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
        }
        val cover = bitmap.asImageBitmap()
        val blurred = createAlbumArtworkBlur(bitmap).asImageBitmap()
        var useBlur by mutableStateOf(false)
        compose.setContent {
            AlbumArtworkLayers(BitmapPainter(cover), if (useBlur) blurred else null, Color.Black, Modifier.width(200.dp).testTag("album"))
        }
        val sharp = capture("album")
        compose.runOnIdle { useBlur = true }
        val softEdges = capture("album", "album-soft-edges-sharp-centre")
        fun detail(frame: Bitmap, y: Int): Long = (1 until frame.width - 1).sumOf { x -> abs(red(frame, x, y) - red(frame, x - 1, y)).toLong() }
        for (y in listOf(sharp.height / 8, sharp.height - 1 - sharp.height / 8)) {
            assertTrue("Edges should soften beyond the darkening alone", detail(softEdges, y) < detail(sharp, y) * 0.85)
        }
        for (x in 0 until sharp.width) {
            assertEquals("Centre must retain the original pixels", sharp.getPixel(x, sharp.height / 2), softEdges.getPixel(x, softEdges.height / 2))
        }
    }

    @Test fun blurIsBoundedAndDoesNotModifyOrRecycleTheSource() {
        val source = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(android.graphics.Color.BLACK)
            drawRect(0f, 0f, 500f, 800f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE })
        }
        val blurred = createAlbumArtworkBlur(source)
        assertTrue(blurred.width <= 128 && blurred.height <= 128)
        assertTrue(red(blurred, blurred.width / 2, blurred.height / 2) in 1..254)
        assertFalse(source.isRecycled)
        assertEquals(android.graphics.Color.WHITE, source.getPixel(499, 400))
        assertEquals(android.graphics.Color.BLACK, source.getPixel(500, 400))
        val tiny = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        assertEquals(android.graphics.Color.RED, createAlbumArtworkBlur(tiny).getPixel(0, 0))
        assertFalse(tiny.isRecycled)
    }

    private var visible by mutableStateOf(true)
    private var clicks = 0

    private fun showButton() {
        compose.setContent {
            CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0)) {
                MaterialTheme(colorScheme = darkColorScheme(primaryContainer = Color.White, onPrimaryContainer = Color.Black)) {
                    Box(Modifier.fillMaxWidth().height(320.dp).background(Color.Black).testTag("scene")) {
                        ScrollActionButton(visible, R.drawable.shuffle) { clicks++ }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun brightness(frame: Bitmap): Pair<Long, Double> {
        var light = 0L
        var weightedY = 0.0
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val value = red(frame, x, y)
            light += value
            weightedY += y * value.toDouble()
        }
        return light to weightedY / light.coerceAtLeast(1)
    }

    private fun advanceMotionFrames(count: Int) {
        repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            // Android draws on its own clock. Let layout and layer updates finish
            // between frames, including the setup of the slide transition.
            compose.waitForIdle()
        }
    }

    @Test fun actionButtonDissolvesBeforeItReachesTheBottom() {
        showButton()
        val before = brightness(capture("scene", "shuffle-visible"))
        assertTrue("The initial button must be rendered: $before", before.first > 0)
        compose.runOnIdle { visible = false }
        advanceMotionFrames(7)
        val during = brightness(capture("scene", "shuffle-disappearing"))
        assertTrue("Button must fade during its movement: before=$before, during=$during", during.first > before.first * 0.03 && during.first < before.first * 0.85)
        assertTrue("Downward movement stays small: before=$before, during=$during", during.second > before.second && during.second < before.second + 40)
        val shuffle = compose.activity.getString(R.string.shuffle)
        compose.onNodeWithContentDescription(shuffle).assertIsNotEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals("A disappearing button must not start playback", 0, clicks)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription(shuffle).assertDoesNotExist()
    }

    @Test fun reversingVisibilityRestoresOneWorkingButton() {
        showButton()
        compose.runOnIdle { visible = false }
        advanceMotionFrames(7)
        compose.runOnIdle { visible = true }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.shuffle)).assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    private fun red(bitmap: Bitmap, x: Int, y: Int) = android.graphics.Color.red(bitmap.getPixel(x, y))
}
