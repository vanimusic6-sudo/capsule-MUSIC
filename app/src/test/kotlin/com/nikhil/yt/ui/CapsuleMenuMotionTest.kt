package com.nikhil.yt.ui

import com.nikhil.yt.ui.component.MenuPresentation
import com.nikhil.yt.ui.component.MenuState
import com.nikhil.yt.ui.component.capsuleMenuElasticX
import com.nikhil.yt.ui.component.capsuleMenuElasticY
import com.nikhil.yt.ui.component.capsuleMenuDismissScale
import com.nikhil.yt.ui.component.capsuleMenuTopFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The original Material3 sheet retains the real anchored gesture owner. */
class CapsuleMenuMotionTest {
    @Test fun nativePlayerMenuIsKeptUntilMaterialHideCompletes() {
        val state = MenuState()
        state.show(presentation = MenuPresentation.CAPSULE_PLAYER) {}
        assertTrue(state.isVisible)
        state.dismiss()
        assertTrue("closing should not abruptly tear down the original sheet", state.isVisible)
        assertTrue(state.dismissRequested)
        state.completeAnimatedDismiss()
        assertFalse(state.isVisible)
        assertFalse(state.dismissRequested)
    }

    @Test fun reopeningCancelsStaleHideAndPreservesPresentation() {
        val state = MenuState()
        state.show(presentation = MenuPresentation.CAPSULE_PLAYER) {}
        state.dismiss()
        state.show(presentation = MenuPresentation.CAPSULE_PLAYER) {}
        assertFalse(state.dismissRequested)
        state.completeAnimatedDismiss()
        assertTrue("stale close completion cannot dismiss a reopened menu", state.isVisible)
    }

    @Test fun oldCloseCannotCompleteANewerCloseAfterRapidReopen() {
        val state = MenuState()
        state.show {}
        val old = state.generation
        state.dismiss()
        state.show {}
        state.dismiss()
        state.completeAnimatedDismiss(old)
        assertTrue(state.isVisible)
        assertTrue(state.dismissRequested)
        state.completeAnimatedDismiss(state.generation)
        assertFalse(state.isVisible)
    }

    @Test fun allMenusUseThePlayersCommittedCloseLifecycle() {
        val state = MenuState()
        state.show {}
        state.dismiss()
        assertTrue(state.isVisible)
        assertTrue(state.dismissRequested)
        state.completeAnimatedDismiss()
        assertFalse(state.isVisible)
    }

    @Test fun elasticEffectIsZeroAtRestAndBoundedAtAllFrames() {
        assertEquals(1f, capsuleMenuElasticX(0f), 0.00001f)
        assertEquals(1f, capsuleMenuElasticY(0f), 0.00001f)
        for (sample in -120..120) {
            val deform = sample / 1000f
            assertTrue(capsuleMenuElasticX(deform) in 0.99f..1.01f)
            assertTrue(capsuleMenuElasticY(deform) in 0.95f..1.05f)
        }
    }
    @Test fun appleClosePullStartsAtFullSizeAndShrinksSmoothlyWithoutJump() {
        assertEquals(1f, capsuleMenuDismissScale(0f), 0.00001f)
        assertEquals(0.968f, capsuleMenuDismissScale(1f), 0.00001f)
        var previous = 1f
        for (frame in 1..60) {
            val now = capsuleMenuDismissScale(frame / 60f)
            assertTrue(now <= previous + 0.00001f)
            assertTrue("frame gap must be small on 60Hz", previous - now < 0.003f)
            previous = now
        }
    }

    @Test fun expandingTopHazeFillsWithoutFlashingOrJumps() {
        assertEquals(0f, capsuleMenuTopFill(Float.NaN, 1000f), 0.0001f)
        assertEquals(0f, capsuleMenuTopFill(1000f, 1000f), 0.0001f)
        var previous = 0f
        for (i in 0..100) {
            val offset = 1000f * (1f - i / 100f)
            val alpha = capsuleMenuTopFill(offset, 1000f)
            assertTrue(alpha + 0.00001f >= previous)
            assertTrue(alpha in 0f..0.30f)
            assertTrue(alpha - previous < 0.02f)
            previous = alpha
        }
        assertEquals(0.30f, previous, 0.0001f)
    }

}
