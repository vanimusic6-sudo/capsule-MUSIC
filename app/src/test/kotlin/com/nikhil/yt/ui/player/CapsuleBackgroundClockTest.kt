package com.nikhil.yt.ui.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CapsuleBackgroundClockTest {
    @get:Rule val compose = createComposeRule()

    @Test fun visibleMotionAdvancesEveryDisplayFrameWithoutRecomposingItsOwner() {
        compose.mainClock.autoAdvance = false
        val running = mutableStateOf(true)
        lateinit var timeline: State<Long>
        var compositions = 0
        compose.setContent {
            compositions++
            timeline = rememberCapsuleAnimationTime(compact = true, running = running.value)
            Box(Modifier.size(20.dp).drawBehind { timeline.value })
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        var before = 0L
        var compositionBaseline = 0
        compose.runOnIdle { before = timeline.value; compositionBaseline = compositions }
        repeat(5) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertTrue("Visible motion must not repeat the same 6/12fps frame", timeline.value > before)
                assertTrue(timeline.value - before <= 20L)
                before = timeline.value
                assertEquals("Drawing should not recompose the player", compositionBaseline, compositions)
            }
        }
        compose.runOnIdle { running.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { before = timeline.value }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.runOnIdle { assertEquals(before, timeline.value); running.value = true }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.runOnIdle { assertTrue(timeline.value - before in 1L..64L) }
    }
}
