package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the Foundation drag node used by lazy lists, independently of app layout.
 * A scrollable catches a running fling with startDragImmediately: the old tests
 * only exercised lists at rest, which never enter this broken code path.
 * Based on AndroidX b/532946466 and b/524163413 regression scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
class ScrollFlingCatchRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun caughtDragKeepsOwner(parentAxis: Orientation) {
        var parentDistance = 0f
        var childDistance = 0f
        val childAxis = if (parentAxis == Orientation.Vertical) Orientation.Horizontal
            else Orientation.Vertical
        compose.setContent {
            val original = LocalViewConfiguration.current
            CompositionLocalProvider(LocalViewConfiguration provides object : ViewConfiguration by original {
                override val touchSlop = 10f
            }) {
                Box(Modifier.fillMaxSize().draggable(
                    state = rememberDraggableState { parentDistance += it },
                    orientation = parentAxis,
                    startDragImmediately = true,
                )) {
                    Box(Modifier.size(200.dp).draggable(
                        state = rememberDraggableState { childDistance += it },
                        orientation = childAxis,
                    ).testTag("child"))
                }
            }
        }
        compose.onNodeWithTag("child").performTouchInput {
            down(center)
            // Cross-axis correction FIRST, while the parent catches its fling.
            // The previous tests only changed direction after acquiring an idle list.
            moveBy(if (parentAxis == Orientation.Vertical) Offset(70f, 0f) else Offset(0f, 70f))
            moveBy(Offset.Zero) // actual stationary MOVE, not just a timestamp gap
            advanceEventTime(750)
            moveBy(if (parentAxis == Orientation.Vertical) Offset(0f, 90f) else Offset(90f, 0f))
            moveBy(if (parentAxis == Orientation.Vertical) Offset(-40f, -30f) else Offset(-30f, -40f))
            up()
        }
        compose.runOnIdle {
            assertTrue("Caught drag must continue after a held diagonal correction", parentDistance > 0f)
            assertEquals("Nested child must not steal the caught fling", 0f, childDistance, 0.01f)
        }
    }

    @Test fun caughtVerticalFlingCannotBeStolenByHorizontalShelf() =
        caughtDragKeepsOwner(Orientation.Vertical)

    @Test fun caughtHorizontalFlingCannotBeStolenByVerticalChild() =
        caughtDragKeepsOwner(Orientation.Horizontal)

    @Test fun heldPointerMoveRemainsConsumedByTheActiveDrag() {
        val stationaryConsumption = mutableListOf<Boolean>()
        compose.setContent {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { change ->
                            if (change.pressed && change.previousPressed &&
                                change.position == change.previousPosition) {
                                stationaryConsumption += change.isConsumed
                            }
                        }
                    }
                }
            }.draggable(
                state = rememberDraggableState {},
                orientation = Orientation.Vertical,
            ).testTag("drag"))
        }
        compose.onNodeWithTag("drag").performTouchInput {
            down(center)
            moveBy(Offset(0f, 100f))
            repeat(4) {
                advanceEventTime(200)
                moveBy(Offset.Zero)
            }
            moveBy(Offset(60f, -20f))
            up()
        }
        compose.runOnIdle {
            assertTrue("The test must dispatch stationary MOVE events", stationaryConsumption.isNotEmpty())
            assertTrue("Holding a finger must not expose unconsumed events to other recognizers",
                stationaryConsumption.all { it })
        }
    }
}
