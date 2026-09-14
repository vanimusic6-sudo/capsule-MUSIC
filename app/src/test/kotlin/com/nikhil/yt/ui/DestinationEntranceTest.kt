package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nikhil.yt.constants.DestinationEntranceMinAlpha
import com.nikhil.yt.ui.screens.destinationEntrance
import com.nikhil.yt.ui.screens.destinationEntranceAlpha
import com.nikhil.yt.ui.screens.destinationEntranceLift
import com.nikhil.yt.ui.screens.rememberDestinationEntranceProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The destination entrance is the app's only route-level motion, so its contract is pinned here:
 * it must be quick, it must never overshoot, and it must not disturb the screen it animates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], application = Application::class)
class DestinationEntranceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun alphaStaysInRangeForEveryProgressValueIncludingImpossibleOnes() {
        assertEquals(DestinationEntranceMinAlpha, destinationEntranceAlpha(0f), 1e-4f)
        assertEquals(1f, destinationEntranceAlpha(1f), 1e-4f)
        // Overshoot, undershoot and a non-finite progress must all resolve to a drawable alpha.
        listOf(-1f, -0.001f, 1.001f, 4f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
            .forEach { progress ->
                val alpha = destinationEntranceAlpha(progress)
                assertTrue("alpha=$alpha for progress=$progress", alpha.isFinite())
                assertTrue("alpha=$alpha for progress=$progress", alpha in DestinationEntranceMinAlpha..1f)
            }
    }

    @Test fun liftIsNeverNegativeAndNeverExceedsItsTravel() {
        assertEquals(8f, destinationEntranceLift(0f, 8f), 1e-4f)
        assertEquals(0f, destinationEntranceLift(1f, 8f), 1e-4f)
        listOf(-1f, 1.5f, Float.NaN, Float.POSITIVE_INFINITY).forEach { progress ->
            val lift = destinationEntranceLift(progress, 8f)
            assertTrue("lift=$lift for progress=$progress", lift in 0f..8f)
        }
        // A degenerate density must not turn into a degenerate translation.
        listOf(0f, -8f, Float.NaN, Float.POSITIVE_INFINITY).forEach { liftPx ->
            assertEquals(0f, destinationEntranceLift(0f, liftPx), 1e-4f)
        }
    }

    @Test fun theEntranceSettlesQuicklyAndMonotonicallyWithoutOvershooting() {
        var progress = 0f
        // Pin the clock before the first composition, otherwise the entrance is already over.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val value by rememberDestinationEntranceProgress()
            progress = value
            Box(Modifier.fillMaxSize())
        }
        compose.waitForIdle()

        var previous = -1f
        var elapsed = 0L
        while (progress < 0.999f && elapsed <= 400L) {
            assertTrue("progress went backwards: $previous -> $progress", progress >= previous)
            assertTrue("progress overshot: $progress", progress <= 1f)
            previous = progress
            compose.mainClock.advanceTimeBy(16L)
            compose.waitForIdle()
            elapsed += 16L
        }

        // Calm, but not slow: the brief rules out both a snap and a noticeable wait.
        assertTrue("entrance took ${elapsed}ms", elapsed in 1L..260L)
    }

    @Test fun theEntranceDoesNotRemountOrResetTheScreenItAnimates() {
        var mounts = 0
        var recomposeTrigger by mutableStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.destinationEntrance(), propagateMinConstraints = true) {
                    // A remember block runs again only if this subtree is actually remounted.
                    remember { mounts++ }
                    var count by rememberSaveable { mutableIntStateOf(0) }
                    Box(Modifier.fillMaxSize().testTag("screen")) {
                        Button(onClick = { count++ }) { Text("taps:$count-$recomposeTrigger") }
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("taps:0-0").performClick()
        compose.mainClock.advanceTimeBy(16L)
        compose.waitForIdle()
        compose.onNodeWithText("taps:1-0").assertIsDisplayed()

        // Recomposing the subtree mid-entrance must not restart the animation or drop screen state.
        compose.runOnIdle { recomposeTrigger = 1 }
        compose.mainClock.advanceTimeBy(16L)
        compose.waitForIdle()
        compose.onNodeWithText("taps:1-1").assertIsDisplayed()

        // ...nor once it has finished.
        compose.mainClock.advanceTimeBy(600L)
        compose.waitForIdle()
        compose.onNodeWithText("taps:1-1").assertIsDisplayed()
        compose.onNodeWithTag("screen").assertIsDisplayed()
        assertEquals(1, mounts)
    }

    @Test fun theWrapperIsLayoutNeutralSoAFullSizeScreenStillFills() {
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("host")) {
                Box(Modifier.destinationEntrance(), propagateMinConstraints = true) {
                    Box(Modifier.fillMaxSize().testTag("screen"))
                }
            }
        }
        compose.waitForIdle()

        val host = compose.onNodeWithTag("host").fetchSemanticsNode().size
        val screen = compose.onNodeWithTag("screen").fetchSemanticsNode().size
        assertTrue("host is $host", host.width > 0 && host.height > 0)
        // The wrapper must hand the screen exactly the space NavHost would have given it.
        assertEquals(host, screen)
    }

    @Test fun entranceIsSkippedWhenTheSystemHasAnimationsTurnedOff() {
        android.provider.Settings.Global.putFloat(
            compose.activity.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
        var modifier: Modifier? = null
        compose.setContent {
            modifier = Modifier.destinationEntrance()
            Box(Modifier.fillMaxSize().testTag("screen"))
        }
        compose.waitForIdle()
        // No graphicsLayer is attached at all, so there is nothing to animate and nothing to clamp.
        assertEquals(Modifier, modifier)
        compose.onNodeWithTag("screen").assertIsDisplayed()
    }
}
