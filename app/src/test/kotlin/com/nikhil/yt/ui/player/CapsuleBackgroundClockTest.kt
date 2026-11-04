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

    @Test fun slowAmbientClockRunsWithoutRecomposingItsOwner() {
        compose.mainClock.autoAdvance = false
        val running = mutableStateOf(true)
        lateinit var timeline: State<Long>
        var compositions = 0
        compose.setContent {
            compositions++
            timeline = rememberCapsuleAnimationTime(compact = true, running = running.value)
            Box(Modifier.size(20.dp).drawBehind { timeline.value })
        }
        repeat(4) { compose.mainClock.advanceTimeBy(100L); compose.waitForIdle() }
        var baseline = 0L
        var compositionBaseline = 0
        compose.runOnIdle { baseline = timeline.value; compositionBaseline = compositions }
        repeat(4) {
            compose.mainClock.advanceTimeBy(100L)
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue("Ambient timeline keeps advancing", timeline.value > baseline)
                assertTrue("Elapsed phase cannot jump", timeline.value - baseline <= 120L)
                baseline = timeline.value
                assertEquals("No player recomposition on wallpaper tick", compositionBaseline, compositions)
            }
        }
        compose.runOnIdle { running.value = false }
        compose.mainClock.advanceTimeBy(150L)
        compose.runOnIdle { baseline = timeline.value }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.runOnIdle { assertEquals(baseline, timeline.value); running.value = true }
        repeat(3) { compose.mainClock.advanceTimeBy(100L); compose.waitForIdle() }
        compose.runOnIdle {
            assertTrue("Resuming must exclude paused time", timeline.value - baseline in 1L..320L)
        }
    }
}
