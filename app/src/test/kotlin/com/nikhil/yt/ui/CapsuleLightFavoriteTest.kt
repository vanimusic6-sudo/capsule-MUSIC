package com.nikhil.yt.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import com.nikhil.yt.ui.player.CapsuleLightFavorite
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsuleLightFavoriteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theLightHeartStillSqueezesWhenItIsPressed() {
        var liked by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.background(Color.Black).testTag("light-heart")) {
                    CapsuleLightFavorite(liked, Color.White) { liked = !liked }
                }
            }
        }
        val initial = capture()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("light-heart").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(160)
        val pressed = capture()
        compose.onNodeWithTag("light-heart").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        val released = capture()
        compose.mainClock.autoAdvance = true
        assertTrue("the pressed heart must become wider", inkWidth(pressed) > inkWidth(initial))
        assertEquals(
            "and it must come back to its own shape when the finger leaves",
            inkWidth(initial),
            inkWidth(released),
        )
        assertTrue("the pressed heart must become shorter", inkHeight(pressed) < inkHeight(initial))
    }

    /**
     * A like applied a beat after the tap must still animate its fill.
     *
     * The heart is not told that it was tapped; it infers it from a press, and the liked state
     * comes back from the database rather than from the click, so there is a gap between the two
     * that the animation has to survive.
     *
     * What this does NOT cover: the gap being long. Robolectric's clock reaches the effect's own
     * delays but not, apparently, the disarm path this once relied on — the same assertion passes
     * against the version that disarmed after 900ms, which was measured, not assumed. So a long
     * round trip is untested here, and the timing bet was removed rather than tuned.
     */
    @Test fun aLikeThatLandsAfterTheTapStillAnimatesItsFill() {
        var liked by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.background(Color.Black).testTag("light-heart")) {
                    CapsuleLightFavorite(liked, Color.White) {}
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("light-heart").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("light-heart").performTouchInput { up() }
        // A database round trip, not an instant flip.
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { liked = true }
        compose.mainClock.advanceTimeBy(100)
        val midway = redAtCentre(capture())
        compose.mainClock.advanceTimeBy(600)
        val settled = redAtCentre(capture())
        compose.mainClock.autoAdvance = true

        assertTrue("the fill must end up applied, red was $settled", settled > 100)
        assertTrue(
            "the fill must travel rather than snap: it was already $midway of $settled a frame in",
            midway < settled,
        )
    }

    /**
     * The Capsule Light column scrolls once its content is taller than the window, and the heart
     * lives inside it. A tap has to keep its animation there too.
     */
    @Test fun theHeartStillAnimatesInsideAScrollingColumn() {
        var liked by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.size(320.dp, 200.dp).verticalScroll(rememberScrollState())) {
                    Box(Modifier.background(Color.Black).testTag("light-heart")) {
                        CapsuleLightFavorite(liked, Color.White) {}
                    }
                    Box(Modifier.size(320.dp, 600.dp))
                }
            }
        }
        compose.mainClock.autoAdvance = false
        // A real tap, not a long press: down and up inside a single frame budget.
        compose.onNodeWithTag("light-heart").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("light-heart").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { liked = true }
        compose.mainClock.advanceTimeBy(100)
        val midway = redAtCentre(capture())
        compose.mainClock.advanceTimeBy(600)
        val settled = redAtCentre(capture())
        compose.mainClock.autoAdvance = true

        assertTrue("the fill must end up applied, red was $settled", settled > 100)
        assertTrue(
            "a tap inside a scrolling column lost its animation: $midway of $settled a frame in",
            midway < settled,
        )
    }

    private fun redAtCentre(image: Bitmap): Int =
        AndroidColor.red(image.getPixel(image.width / 2, image.height / 2))

    private fun inkWidth(image: Bitmap): Int {
        val columns = (0 until image.width).filter { x ->
            (0 until image.height).any { y -> AndroidColor.red(image.getPixel(x, y)) > 120 }
        }
        return columns.last() - columns.first() + 1
    }

    private fun inkHeight(image: Bitmap): Int {
        val rows = (0 until image.height).filter { y ->
            (0 until image.width).any { x -> AndroidColor.red(image.getPixel(x, y)) > 120 }
        }
        return rows.last() - rows.first() + 1
    }

    private fun capture(): Bitmap {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("light-heart").fetchSemanticsNode().boundsInRoot
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            compose.activity.findViewById<View>(android.R.id.content).draw(canvas)
        }
        return bitmap
    }
}
