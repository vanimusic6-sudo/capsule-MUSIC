package com.nikhil.yt.ui.player

import android.app.Application

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp")
class CapsuleVideoArtworkTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lightKeepsThePreparingSurfaceWhenFirstFrameArrives() = verifyStableOutput(320, 280, true, 0)
    @Test fun cosmoKeepsThePreparingSurfaceWhenFirstFrameArrives() = verifyStableOutput(340, 420, false, 0)
    @Test fun immersiveKeepsThePreparingSurfaceWhenFirstFrameArrives() = verifyStableOutput(380, 440, true, 12, motion = CapsuleVideoArtworkMotion.IMMERSIVE)
    @Test fun enlargedLightWindowKeepsOneFixedNativeViewport() = verifyStableOutput(320, 280, true, 0, 1.08f)

    private fun verifyStableOutput(width: Int, height: Int, fill: Boolean, inset: Int, widthScale: Float = 1f,
        motion: CapsuleVideoArtworkMotion = CapsuleVideoArtworkMotion.SCULPTED) {
        val presenting = mutableStateOf(false)
        val needed = mutableStateOf(true)
        var attaches = 0
        var detaches = 0
        val measured = mutableListOf<IntSize>()
        var coverSize = IntSize.Zero
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CapsuleVideoArtworkTransition(
                presentingVideo = presenting.value,
                needsSurface = needed.value,
                modifier = Modifier.size(width.dp, height.dp),
                fillArtworkSlot = fill,
                videoSidePadding = inset.dp,
                videoWidthScale = widthScale,
                motion = motion,
                video = { active ->
                    if (active) DisposableEffect(Unit) {
                        attaches++
                        onDispose { detaches++ }
                    }
                    Box(Modifier.fillMaxSize().onSizeChanged { measured.add(it) })
                },
                artwork = { Box(Modifier.fillMaxSize().onSizeChanged { coverSize = it }) },
            )
        }
        compose.waitForIdle()
        val preparingSize = measured.single()
        assertTrue(preparingSize.width > 0 && preparingSize.height > 0)
        assertEquals(16f / 9f, preparingSize.width.toFloat() / preparingSize.height, 0.02f)
        if (widthScale > 1f) assertTrue("VIDEO may grow beyond the cover without resizing its decoder", preparingSize.width > coverSize.width)
        compose.runOnIdle { presenting.value = true }
        compose.mainClock.advanceTimeBy(120)
        compose.waitForIdle()
        assertEquals("first-frame readiness must not replace the output", 1, attaches)
        assertEquals(0, detaches)
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals("native viewport must not resize during the reveal", listOf(preparingSize), measured)
        // Rapid readiness reversals interrupt the animation, not the surface lease.
        compose.runOnIdle { presenting.value = false }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { presenting.value = true }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals(1, attaches)
        assertEquals(0, detaches)
        assertEquals(listOf(preparingSize), measured)
        // Only leaving the decoder lifecycle may detach it.
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { needed.value = false; presenting.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("AUDIO must release the renderer", 1, detaches) }
    }
}
