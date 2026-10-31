package com.nikhil.yt.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleScrollbarMathTest {
    @Test fun smallOrEmptyListsCannotFlingBeyondTheirContents() {
        assertEquals(0, CapsuleScrollbarMath.lastStartingIndex(0, 0))
        assertEquals(0, CapsuleScrollbarMath.lastStartingIndex(5, 10))
        assertEquals(80, CapsuleScrollbarMath.lastStartingIndex(100, 20))
        assertEquals(0, CapsuleScrollbarMath.indexForFraction(1f, 0))
    }

    @Test fun allTouchPositionsAreClamped() {
        assertEquals(0, CapsuleScrollbarMath.indexForFraction(-0.5f, 180))
        assertEquals(180, CapsuleScrollbarMath.indexForFraction(2.5f, 180))
        assertEquals(0, CapsuleScrollbarMath.indexForFraction(Float.NaN, 180))
        assertEquals(0, CapsuleScrollbarMath.indexForFraction(Float.POSITIVE_INFINITY, 180))
        assertEquals(0f, CapsuleScrollbarMath.thumbTop(Float.NaN, 700f, 72f), 0f)
        assertEquals(0f, CapsuleScrollbarMath.thumbTop(1f, 20f, 72f), 0f)
    }

    @Test fun thumbAndListIndexProgressStayInSameDirection() {
        val total = CapsuleScrollbarMath.lastStartingIndex(150, 12)
        val top = CapsuleScrollbarMath.fractionForTouch(36f, 700f, 72f)
        val bottom = CapsuleScrollbarMath.fractionForTouch(664f, 700f, 72f)
        assertEquals(0, CapsuleScrollbarMath.indexForFraction(top, total))
        assertEquals(total, CapsuleScrollbarMath.indexForFraction(bottom, total))
        assertTrue(CapsuleScrollbarMath.fractionForIndex(50, 20, 40, total) >
            CapsuleScrollbarMath.fractionForIndex(49, 20, 40, total))
    }

    @Test fun tabReselectCannotStealActiveDrag() {
        assertTrue(mayProgrammaticallyScroll(false))
        assertTrue(!mayProgrammaticallyScroll(true))
    }
}
