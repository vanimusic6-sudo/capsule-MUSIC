package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reproduces the frame/duration arithmetic from AndroidX issue 555294641 on the real runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SeekableTransitionNaNTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun zeroDurationWithBackwardsFrameDoesNotProduceNaN() {
        checkZeroDuration(100_000_000L, 50_000_000L)
    }

    @Test fun zeroDurationWithUnspecifiedFirstFrameDoesNotOverflow() {
        checkZeroDuration(Long.MIN_VALUE, 100_000_000L)
    }

    @Test fun disabledAnimationsCompleteNormally() { checkDurationScale(0f) }

    @Test fun accessibilityDurationScaleStillCompletesNormally() { checkDurationScale(0.5f) }

    private fun checkDurationScale(factor: Float) {
        val state = SeekableTransitionState(false)
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            val transition = rememberTransition(state)
            transition.animateFloat(transitionSpec = { tween(0) }) { if (it) 1f else 0f }
        }
        val scale = object : MotionDurationScale { override val scaleFactor = factor }
        compose.runOnIdle { scope.launch(scale) { state.animateTo(true) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(state.currentState)
            assertTrue(state.fraction.isFinite())
        }
    }

    private fun checkZeroDuration(firstFrame: Long, secondFrame: Long) {
        val state = SeekableTransitionState(false)
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            val transition = rememberTransition(state)
            transition.animateFloat(transitionSpec = { tween(0) }) { if (it) 1f else 0f }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { scope.launch { state.seekTo(0.5f, true) } }
        compose.mainClock.advanceTimeByFrame()
        var frames = 0
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
                onFrame(if (frames++ == 0) firstFrame else secondFrame)
        }
        compose.runOnIdle { scope.launch(clock) { state.animateTo(true) } }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(state.currentState)
            assertTrue(state.fraction.isFinite())
            assertEquals(0f, state.fraction, 0f)
        }
    }

    @Test fun interruptedFiniteTransitionStillSettlesInTheRequestedState() {
        val state = SeekableTransitionState(false)
        lateinit var scope: CoroutineScope
        var value = 0f
        compose.setContent {
            scope = rememberCoroutineScope()
            val transition = rememberTransition(state)
            val animated by transition.animateFloat(transitionSpec = { tween(80) }) { if (it) 1f else 0f }
            value = animated
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { scope.launch { state.animateTo(true) } }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { scope.launch { state.animateTo(false) } }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(false, state.currentState)
            assertEquals(false, state.targetState)
            assertTrue(value.isFinite())
            assertEquals(0f, value, 0.0001f)
        }
    }
}
