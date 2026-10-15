package com.nikhil.yt.ui

import com.nikhil.yt.ui.component.MenuPresentation
import com.nikhil.yt.ui.component.MenuState
import com.nikhil.yt.ui.component.capsuleMenuBackdropOpacity
import com.nikhil.yt.ui.component.capsuleMenuSheetTranslation
import com.nikhil.yt.ui.component.capsuleMenuSettleAnchor
import com.nikhil.yt.ui.component.capsuleMenuJellyStretch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard the non-draggable menu's interruption and 60 Hz presentation policy. */
class CapsuleMenuMotionTest {
    @Test fun playerMenuRemainsMountedUntilPhysicalCloseSettles() {
        val state = MenuState()
        state.show(presentation = MenuPresentation.CAPSULE_PLAYER) {}
        assertTrue(state.isVisible)
        assertFalse(state.dismissRequested)
        state.dismiss()
        assertTrue("do not abruptly remove menu on tap", state.isVisible)
        assertTrue(state.dismissRequested)
        state.completeAnimatedDismiss()
        assertFalse(state.isVisible)
        assertFalse(state.dismissRequested)
    }

    @Test fun reOpenDuringClosingCancelsPendingDismissal() {
        val state = MenuState()
        state.show(presentation = MenuPresentation.CAPSULE_PLAYER) {}
        state.dismiss()
        state.show(presentation = MenuPresentation.CAPSULE_PLAYER) {}
        assertFalse(state.dismissRequested)
        assertTrue(state.isVisible)
        state.completeAnimatedDismiss()
        assertTrue("stale close must not destroy reopened menu", state.isVisible)
    }

    @Test fun nonPlayerMenusStillDismissImmediately() {
        val state = MenuState()
        state.show {}
        state.dismiss()
        assertFalse(state.isVisible)
    }

    @Test fun swipesRestoreExpandedPeekAndDismissedAnchors() {
        assertEquals(0f, capsuleMenuSettleAnchor(0.11f, 0f), 0.0001f)
        assertEquals(0.29f, capsuleMenuSettleAnchor(0.31f, 0f), 0.0001f)
        assertEquals(1f, capsuleMenuSettleAnchor(0.75f, 0f), 0.0001f)
        assertEquals(0f, capsuleMenuSettleAnchor(0.4f, -900f), 0.0001f)
        assertEquals(1f, capsuleMenuSettleAnchor(0.4f, 1450f), 0.0001f)
    }

    @Test fun rubberMotionIsBoundedAndVanishingAtBothAnchors() {
        assertEquals(0f, capsuleMenuJellyStretch(0f, -5f), 0.0001f)
        assertEquals(0f, capsuleMenuJellyStretch(1f, 5f), 0.0001f)
        assertEquals(0f, capsuleMenuJellyStretch(0.35f, 0f), 0.0001f)
        assertTrue(capsuleMenuJellyStretch(0.35f, -4f) > 0f)
        assertTrue(capsuleMenuJellyStretch(0.35f, 4f) < 0f)
        assertTrue(kotlin.math.abs(capsuleMenuJellyStretch(0.5f, -300f)) <= 0.018f)
    }

    @Test fun sheetMovesOnlyWhenTheAnimationProgressChanges() {
        for (p in listOf(0f, 0.10f, 0.35f, 0.70f, 1f)) {
            assertEquals("fixed progress means fixed layout, regardless of press duration",
                p * 720f, capsuleMenuSheetTranslation(p, 720f), 0.001f)
        }
        assertEquals(0.38f, capsuleMenuBackdropOpacity(0f), 0.0001f)
        assertEquals(0f, capsuleMenuBackdropOpacity(1f), 0.0001f)
    }
}
