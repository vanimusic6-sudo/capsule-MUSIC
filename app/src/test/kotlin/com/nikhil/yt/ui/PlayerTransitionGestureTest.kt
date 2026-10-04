package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.component.BottomSheet
import com.nikhil.yt.ui.component.BottomSheetState
import com.nikhil.yt.ui.component.COLLAPSED_ANCHOR
import com.nikhil.yt.ui.component.rememberBottomSheetState
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
class PlayerTransitionGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var state: BottomSheetState
    private var childClicks = 0
    private var horizontalDistance = 0f
    private var dismisses = 0

    private fun showSheet() {
        childClicks = 0
        horizontalDistance = 0f
        dismisses = 0

        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    state =
                        rememberBottomSheetState(
                            dismissedBound = 0.dp,
                            collapsedBound = 80.dp,
                            expandedBound = 851.dp,
                            initialAnchor = COLLAPSED_ANCHOR,
                        )

                    BottomSheet(
                        state = state,
                        backgroundColor = Color.Black,
                        onDismiss = { dismisses++ },
                        dismissOnlyFromCollapsed = true,
                        collapsedContentHeight = 80.dp,
                        collapsedContent = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .testTag("mini")
                                    .pointerInput(Unit) {
                                        detectHorizontalDragGestures { change, amount ->
                                            change.consume()
                                            horizontalDistance += amount
                                        }
                                    },
                            ) {
                                Button(
                                    modifier = Modifier.testTag("childButton"),
                                    onClick = { childClicks++ },
                                ) {
                                    Text("Pause")
                                }
                            }
                        },
                    ) {
                        Box(Modifier.fillMaxSize().testTag("full"))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `child click is not stolen by vertical sheet gesture`() {
        showSheet()
        compose.onNodeWithTag("childButton").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, childClicks)
            assertTrue(state.isCollapsed)
        }
    }

    @Test fun `tap on mini background opens player`() {
        showSheet()
        compose.onNodeWithTag("mini").performTouchInput {
            down(Offset(size.width * 0.85f, size.height * 0.5f))
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.isExpanded) }
    }

    @Test fun `horizontal mini swipe wins without moving vertical transition`() {
        showSheet()
        compose.onNodeWithTag("mini").performTouchInput {
            down(center)
            advanceEventTime(16)
            moveBy(Offset(120f, 0f))
            advanceEventTime(16)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(abs(horizontalDistance) > 1f)
            assertTrue(state.isCollapsed)
            assertEquals(0f, state.rawProgress, 0.001f)
        }
    }

    @Test fun `vertical mini drag opens without mini owning vertical state`() {
        showSheet()
        compose.onNodeWithTag("mini").performTouchInput {
            down(center)
            advanceEventTime(16)
            moveBy(Offset(0f, -90f))
            advanceEventTime(16)
            moveBy(Offset(0f, -90f))
            advanceEventTime(16)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(state.isExpanded)
            assertEquals(0f, horizontalDistance, 0.001f)
        }
    }

    @Test fun `downward action dismisses only when gesture begins at mini dock`() {
        showSheet()
        compose.onNodeWithTag("mini").performTouchInput {
            down(center)
            advanceEventTime(16)
            moveBy(Offset(0f, 120f))
            advanceEventTime(16)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(state.isDismissed)
            assertEquals(1, dismisses)
        }

        showSheet()
        compose.runOnIdle { state.expandSoft() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.isExpanded) }

        compose.onNodeWithTag("full").performTouchInput {
            down(center)
            advanceEventTime(16)
            moveBy(Offset(0f, 1200f))
            advanceEventTime(16)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(state.isCollapsed)
            assertEquals(0, dismisses)
        }
    }
}
