package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.nikhil.yt.ui.component.PlayerContentHandoffPoint
import com.nikhil.yt.ui.component.rememberBottomSheetState
import com.nikhil.yt.ui.component.miniPlayerContentAlpha
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
    private var fullBottomClicks = 0
    private var mainScreenClicks = 0
    private var compactDockHeight by mutableStateOf(80.dp)

    private fun showSheet() {
        childClicks = 0
        horizontalDistance = 0f
        dismisses = 0
        fullBottomClicks = 0
        mainScreenClicks = 0
        compactDockHeight = 80.dp

        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    state =
                        rememberBottomSheetState(
                            dismissedBound = 0.dp,
                            collapsedBound = compactDockHeight,
                            expandedBound = 851.dp,
                            initialAnchor = COLLAPSED_ANCHOR,
                        )

                    Button(
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .testTag("mainScreenButton"),
                        onClick = { mainScreenClicks++ },
                    ) {
                        Text("Main")
                    }

                    BottomSheet(
                        state = state,
                        modifier = Modifier.testTag("sheet"),
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
                        Box(Modifier.fillMaxSize().testTag("full")) {
                            Button(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .testTag("fullBottomButton"),
                                onClick = { fullBottomClicks++ },
                            ) {
                                Text("Queue")
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun miniNode() = compose.onNodeWithTag("mini", useUnmergedTree = true)

    private fun advanceUntilProgress(
        min: Float,
        max: Float,
        maxFrames: Int = 90,
    ) {
        repeat(maxFrames) {
            compose.mainClock.advanceTimeByFrame()
            val progress = compose.runOnIdle { state.rawProgress }
            if (progress in min..max) return
        }
        throw AssertionError(
            "transition never entered [" + min + ", " + max + "], last=" +
                compose.runOnIdle { state.rawProgress },
        )
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
        miniNode().performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.isExpanded) }
    }

    @Test fun `horizontal mini swipe wins without moving vertical transition`() {
        showSheet()
        miniNode().performTouchInput {
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

    @Test fun `compact stays fully opaque when its navigation dock changes height`() {
        showSheet()
        compose.runOnIdle { compactDockHeight = 112.dp }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(112.dp, state.collapsedBound)
            assertEquals(
                "offset=${state.value} anchor=${state.collapsedBound} target=${state.targetAnchor} " +
                    "running=${state.isAnimationRunning} visual=${state.visualProgress}",
                0f,
                state.rawProgress,
                0.001f,
            )
            assertEquals(0f, state.visualProgress, 0f)
            assertEquals(1f, miniPlayerContentAlpha(state.visualProgress), 0f)
        }

        compose.runOnIdle { state.expandSoft() }
        compose.waitForIdle()
        compose.runOnIdle { state.collapseSoft() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0f, state.rawProgress, 0.001f)
            assertEquals(1f, miniPlayerContentAlpha(state.visualProgress), 0f)
        }
    }

    @Test fun `vertical mini drag opens without mini owning vertical state`() {
        showSheet()
        miniNode().performTouchInput {
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

    @Test fun `downward action from mini dock dismisses playback surface`() {
        showSheet()
        miniNode().performTouchInput {
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
    }

    @Test fun `downward action that begins expanded cannot destructively dismiss`() {
        showSheet()
        compose.runOnIdle { state.expandSoft() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.isExpanded) }

        compose.onNodeWithTag("full", useUnmergedTree = true).performTouchInput {
            down(Offset(center.x, 40f))
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

    @Test fun `near dock closing gesture can continue downward into dismiss`() {
        showSheet()
        compose.runOnIdle { state.expandSoft() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { state.collapseSoft() }
            advanceUntilProgress(min = 0.08f, max = 0.14f)

            miniNode().performTouchInput {
                down(center)
                advanceEventTime(16)
                moveBy(Offset(0f, 600f))
                advanceEventTime(16)
                up()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(state.isDismissed)
            assertEquals(1, dismisses)
        }
    }

    @Test fun `close request releases expanded ownership before spring settles`() {
        showSheet()
        compose.runOnIdle { state.expandSoft() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { state.collapseSoft() }
            compose.runOnIdle {
                assertTrue(state.rawProgress > 0.9f)
                assertTrue(!state.isExpandedOrExpanding)
                assertTrue(state.isCollapsedOrCollapsing)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    @Test fun `mini child accepts tap while closing spring is still running`() {
        showSheet()
        compose.runOnIdle { state.expandSoft() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { state.collapseSoft() }
            advanceUntilProgress(
                min = PlayerContentHandoffPoint * 0.35f,
                max = PlayerContentHandoffPoint * 0.75f,
            )
            compose.onNodeWithTag("childButton").performTouchInput {
                down(center)
                up()
            }
            compose.runOnIdle {
                assertEquals(1, childClicks)
                assertTrue(state.isAnimationRunning)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    @Test fun `full player content leaves hit tree when mini owns handoff`() {
        showSheet()
        compose.runOnIdle { state.expandSoft() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { state.collapseSoft() }
            advanceUntilProgress(
                min = PlayerContentHandoffPoint * 0.35f,
                max = PlayerContentHandoffPoint * 0.75f,
            )
            val fullPlayerMissing =
                runCatching {
                    compose.onNodeWithTag("full", useUnmergedTree = true).fetchSemanticsNode()
                }.isFailure
            assertTrue(fullPlayerMissing)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    @Test fun `main screen receives input as soon as mini owns closing handoff`() {
        showSheet()
        compose.runOnIdle { state.expandSoft() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { state.collapseSoft() }
            advanceUntilProgress(
                min = PlayerContentHandoffPoint * 0.35f,
                max = PlayerContentHandoffPoint * 0.75f,
            )
            compose.onNodeWithTag("mainScreenButton").performTouchInput {
                down(center)
                up()
            }
            compose.runOnIdle {
                assertEquals(1, mainScreenClicks)
                assertTrue(!state.isExpandedOrExpanding)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    @Test fun `latest rapid programmatic request owns the transition`() {
        showSheet()
        compose.runOnIdle {
            state.expandSoft()
            state.collapseSoft()
            state.expandSoft()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.isExpanded) }
    }

    @Test fun `authoritative dismiss cancels an in flight open`() {
        showSheet()
        compose.runOnIdle {
            state.expandSoft()
            state.dismiss()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.isDismissed) }
    }

    @Test fun `opening settle can be reversed from its current visual position`() {
        showSheet()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { state.expandSoft() }
            advanceUntilProgress(min = 0.08f, max = 0.20f)
            miniNode().performTouchInput {
                down(center)
                // First movement intentionally crosses vertical touch slop without stealing DOWN
                // from Mini child controls. Subsequent deltas must take over the same anchored
                // mutation and move from the live in-flight offset.
                repeat(4) {
                    advanceEventTime(16)
                    moveBy(Offset(0f, 80f))
                }
                advanceEventTime(16)
                up()
            }

        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.isCollapsed) }
    }

    @Test fun `closing settle can be reversed back to expanded`() {
        showSheet()
        compose.runOnIdle { state.expandSoft() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.isExpanded) }

        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { state.collapseSoft() }
            advanceUntilProgress(min = 0.35f, max = 0.80f)
            val beforeReverse = compose.runOnIdle { state.rawProgress }

            compose.onNodeWithTag("full", useUnmergedTree = true).performTouchInput {
                down(Offset(center.x, 40f))
                repeat(4) {
                    advanceEventTime(16)
                    moveBy(Offset(0f, -100f))
                }
                advanceEventTime(16)
                up()
            }

            val afterDrag = compose.runOnIdle { state.rawProgress }
            assertTrue("reopen drag did not continue from current progress", afterDrag > beforeReverse)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.isExpanded) }
    }

    @Test fun `hidden mini cannot block full player bottom controls`() {
        showSheet()
        compose.runOnIdle { state.expandSoft() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.isExpanded) }

        compose.onNodeWithTag("fullBottomButton").performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, fullBottomClicks) }
    }
}
