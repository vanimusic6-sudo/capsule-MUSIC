package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nikhil.yt.constants.MiniPlayerBackgroundStyle
import com.nikhil.yt.ui.player.MiniPlayerMaxSwipeTilt
import com.nikhil.yt.ui.player.MiniPlayerSurface
import com.nikhil.yt.ui.player.MiniPlayerSwipeSpring
import com.nikhil.yt.ui.player.MiniPlayerSwipeTransform
import com.nikhil.yt.ui.player.miniPlayerSwipeMotion
import com.nikhil.yt.ui.player.miniPlayerSwipeTransform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
class MiniPlayerSwipeMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tiltIsBoundedAndHasTheSameAngleAcrossDisplayDensities() {
        for (offset in -2000..2000 step 17) {
            val motion = miniPlayerSwipeTransform(offset.toFloat(), 360f)
            assertTrue(abs(motion.rotation) <= MiniPlayerMaxSwipeTilt)
            assertTrue(motion.y in 0f..8f)
        }
        val regular = miniPlayerSwipeTransform(72f, 360f)
        val dense = miniPlayerSwipeTransform(144f, 720f)
        assertTrue(regular.rotation in 0.5f..1.5f)
        assertEquals(regular.rotation, dense.rotation, 0.001f)
        assertEquals(regular.y * 2, dense.y, 0.001f)
        assertEquals(MiniPlayerSwipeTransform(0f, 0f, 0f), miniPlayerSwipeTransform(0f, 360f))
        assertEquals(MiniPlayerSwipeTransform(0f, 0f, 0f), miniPlayerSwipeTransform(72f, 0f))
    }

    @Test fun oppositeDirectionsAndRtlMirrorTheMotionWithoutChangingTheArc() {
        val right = miniPlayerSwipeTransform(80f, 360f)
        val left = miniPlayerSwipeTransform(-80f, 360f)
        val rtl = miniPlayerSwipeTransform(80f, 360f, isRtl = true)
        assertEquals(-right.x, left.x, 0.001f)
        assertEquals(-right.rotation, left.rotation, 0.001f)
        assertEquals(right.y, left.y, 0.001f)
        assertEquals(left, rtl)
    }

    @Test fun renderedCardTiltsAndSettlesAfterAnInterruptedReturn() {
        val offset = Animatable(0f)
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            MaterialTheme {
                Box(Modifier.size(393.dp, 220.dp), contentAlignment = Alignment.Center) {
                    MiniPlayerSurface(
                        MiniPlayerBackgroundStyle.THEME, false, emptyList(), animated = false,
                        modifier = Modifier.width(240.dp).height(64.dp)
                            .miniPlayerSwipeMotion({ offset.value }, LayoutDirection.Ltr),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.align(Alignment.CenterStart).size(2.dp).testTag("left"))
                            Box(Modifier.align(Alignment.CenterEnd).size(2.dp).testTag("right"))
                        }
                    }
                }
            }
        }
        fun slope(): Float = compose.onNodeWithTag("right").fetchSemanticsNode().boundsInRoot.center.y -
            compose.onNodeWithTag("left").fetchSemanticsNode().boundsInRoot.center.y
        assertEquals(0f, slope(), 0.1f)
        compose.runOnIdle { scope.launch { offset.snapTo(120f) } }
        val tilted = slope()
        assertTrue("The card must tilt, not only translate", tilted in 1f..25f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { scope.launch { offset.animateTo(0f, MiniPlayerSwipeSpring) } }
        repeat(6) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertTrue("Return should pass through intermediate angles", slope() in 0.1f..(tilted - 0.1f))
        compose.runOnIdle { scope.launch { offset.snapTo(-120f) } }
        assertTrue(slope() < -1f)
        compose.runOnIdle { scope.launch { offset.animateTo(0f, MiniPlayerSwipeSpring) } }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0f, offset.value, 0.001f)
        assertEquals(0f, slope(), 0.1f)
    }
}
