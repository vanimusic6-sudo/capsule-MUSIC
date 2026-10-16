package com.nikhil.yt.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure axis-arbitration checks; end-to-end input still requires a touch device. */
class MiniPlayerGestureArbiterTest {
    @Test fun horizontalDirectionStaysLockedDuringDiagonalDriftAndRelease() {
        val arbiter = MiniPlayerGestureArbiter()
        arbiter.begin()
        arbiter.record(dx = 16f, dy = 5f, touchSlop = 8f)
        assertEquals(MiniGestureAxis.HORIZONTAL, arbiter.axis)
        arbiter.record(dx = -4f, dy = 40f, touchSlop = 8f)
        assertTrue(arbiter.wasHorizontal())
        arbiter.finish()
        assertTrue(arbiter.wasHorizontal())
        arbiter.begin()
        assertFalse(arbiter.wasHorizontal())
    }

    @Test fun verticalDirectionWinsBeforeHorizontalMovementAndNeverSwitchesMidGesture() {
        val arbiter = MiniPlayerGestureArbiter()
        arbiter.begin()
        arbiter.record(dx = 3f, dy = 9f, touchSlop = 8f)
        assertEquals(MiniGestureAxis.VERTICAL, arbiter.axis)
        arbiter.record(dx = 45f, dy = 0f, touchSlop = 8f)
        assertEquals(MiniGestureAxis.VERTICAL, arbiter.axis)
        assertFalse(arbiter.wasHorizontal())
    }

    @Test fun subSlopTapDoesNotStealEitherGesture() {
        val arbiter = MiniPlayerGestureArbiter()
        arbiter.begin()
        arbiter.record(dx = 3f, dy = 4f, touchSlop = 8f)
        assertEquals(MiniGestureAxis.UNKNOWN, arbiter.axis)
        arbiter.finish()
        assertFalse(arbiter.wasHorizontal())
    }
}
