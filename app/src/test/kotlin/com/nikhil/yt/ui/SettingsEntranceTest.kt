package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.motion.rememberSettingsEntrance
import com.nikhil.yt.ui.motion.settingsEntranceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings entrance fades cards in from zero opacity, which means a cascade that fails to run
 * leaves the screen blank. That is not hypothetical — an earlier attempt at route motion did exactly
 * that and shipped a settings screen that was nothing but a flat surface — so completion is pinned
 * here rather than assumed.
 *
 * The other half of the contract is that the cascade is draw-only: cards must occupy their final
 * positions from the very first frame, because a staggered entrance that moves layout is what let
 * settings cards travel over each other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], application = Application::class)
class SettingsEntranceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theCascadeAlwaysCompletesSoNoCardIsLeftInvisible() {
        var progress = 0f
        compose.setContent {
            val value by rememberSettingsEntrance()
            progress = value
            Box(Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        assertEquals("the settings cascade never finished", 1f, progress, 1e-4f)
    }

    @Test fun everyCardIsVisibleAndReadableOnceTheCascadeHasRun() {
        compose.setContent {
            MaterialTheme {
                val entrance = rememberSettingsEntrance()
                Column(Modifier.fillMaxSize()) {
                    repeat(6) { index ->
                        Box(
                            Modifier
                                .settingsEntranceItem(entrance, index)
                                .height(40.dp)
                                .testTag("card:$index"),
                        ) { Text("card $index") }
                    }
                }
            }
        }
        compose.waitForIdle()

        repeat(6) { index ->
            compose.onNodeWithTag("card:$index").assertIsDisplayed()
            compose.onNodeWithText("card $index").assertIsDisplayed()
        }
    }

    @Test fun cardsHoldTheirFinalLayoutFromTheFirstFrameSoTheyCannotOverlap() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                val entrance = rememberSettingsEntrance()
                Column(Modifier.fillMaxSize()) {
                    repeat(4) { index ->
                        Box(
                            Modifier
                                .settingsEntranceItem(entrance, index)
                                .height(40.dp)
                                .testTag("card:$index"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        fun bounds(index: Int) = compose.onNodeWithTag("card:$index").fetchSemanticsNode().boundsInRoot

        val midEntrance = (0 until 4).map { bounds(it) }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        val settled = (0 until 4).map { bounds(it) }

        // Layout is identical mid-cascade and at rest: only the drawing moved.
        midEntrance.forEachIndexed { index, rect ->
            assertEquals("card $index moved in layout", rect, settled[index])
        }
        // ...and neighbours never share vertical space.
        (1 until 4).forEach { index ->
            assertTrue(
                "card $index overlaps card ${index - 1}",
                settled[index].top >= settled[index - 1].bottom - 0.01f,
            )
        }
    }
}
