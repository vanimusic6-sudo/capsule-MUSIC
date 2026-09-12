package com.nikhil.yt.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.CapsuleFavoriteIcon
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
class CapsuleFavoriteAnimationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pressDeformsTheHeartAndEachClickTogglesItsOpeningOnce() {
        var liked by mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                val interaction = remember { MutableInteractionSource() }
                Box(Modifier.background(Color.Black)) {
                    IconButton(
                        onClick = { clicks++; liked = !liked },
                        interactionSource = interaction,
                        modifier = Modifier.size(72.dp).testTag("like-button"),
                    ) {
                        CapsuleFavoriteIcon(liked, Color.White,
                            Modifier.size(48.dp).testTag("heart"), interaction)
                    }
                }
            }
        }
        val initial = capture()
        assertEquals(0, AndroidColor.red(initial.getPixel(initial.width / 2, initial.height / 2)))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("like-button").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(160)
        val pressed = capture()
        assertTrue("The pressed heart must become wider", inkWidth(pressed) > inkWidth(initial))
        assertTrue("The pressed heart must become shorter", inkHeight(pressed) < inkHeight(initial))
        compose.onNodeWithTag("like-button").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1000)
        val filled = capture()
        assertEquals(1, clicks)
        assertTrue(AndroidColor.red(filled.getPixel(filled.width / 2, filled.height / 2)) > 240)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_remove_like)).assertIsDisplayed()
        compose.onNodeWithTag("like-button").performClick()
        compose.mainClock.advanceTimeBy(1000)
        val empty = capture()
        assertEquals(2, clicks)
        assertEquals(0, AndroidColor.red(empty.getPixel(empty.width / 2, empty.height / 2)))
        assertEquals(inkWidth(initial), inkWidth(empty))
        assertEquals(inkHeight(initial), inkHeight(empty))
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_like)).assertIsDisplayed()
        compose.mainClock.autoAdvance = true
    }

    private fun inkWidth(image: Bitmap): Int {
        val columns = (0 until image.width).filter { x ->
            (0 until image.height).any { y -> AndroidColor.red(image.getPixel(x, y)) > 200 }
        }
        return columns.last() - columns.first() + 1
    }

    private fun inkHeight(image: Bitmap): Int {
        val rows = (0 until image.height).filter { y ->
            (0 until image.width).any { x -> AndroidColor.red(image.getPixel(x, y)) > 200 }
        }
        return rows.last() - rows.first() + 1
    }

    private fun capture(): Bitmap {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("heart").fetchSemanticsNode().boundsInRoot
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
