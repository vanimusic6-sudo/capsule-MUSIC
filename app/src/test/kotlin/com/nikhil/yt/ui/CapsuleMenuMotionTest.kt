package com.nikhil.yt.ui

import com.nikhil.yt.ui.component.MenuPresentation
import com.nikhil.yt.ui.component.MenuState
import com.nikhil.yt.ui.component.capsuleMenuElasticX
import com.nikhil.yt.ui.component.capsuleMenuElasticY
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

    @Test fun unrelatedMenusKeepOriginalImmediateClose() {
        val state = MenuState()
        state.show {}
        state.dismiss()
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
}
