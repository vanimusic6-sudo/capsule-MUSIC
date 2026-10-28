package com.nikhil.yt.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailToolbarRevealTest {
    @Test fun photoDissolvesAcrossLongEvenDistanceUntilTitleCrossesToolbar() {
        assertEquals(0f, detailHeaderFractions(300f, 1f).photoDissolve, 0f)
        assertEquals(0f, detailHeaderFractions(260f, 1f).photoDissolve, 0f)
        assertTrue(detailHeaderFractions(180f, 1f).photoDissolve > 0f)
        assertTrue(detailHeaderFractions(180f, 1f).photoDissolve < detailHeaderFractions(50f, 1f).photoDissolve)
        assertEquals(1f, detailHeaderFractions(-26f, 1f).photoDissolve, 0f)
        assertEquals(1f, detailHeaderFractions(-200f, 1f).photoDissolve, 0f)
    }

    @Test fun samePhysicalTitleThresholdInDifferentDensities() {
        for (density in listOf(1f, 1.75f, 2.75f, 3.5f)) {
            val a = detailHeaderFractions(125f * density, density)
            val b = detailHeaderFractions(125f, 1f)
            assertEquals(b.photoDissolve, a.photoDissolve, 0.0001f)
            assertEquals(b.sourceTitle, a.sourceTitle, 0.0001f)
            assertEquals(b.pinnedTitle, a.pinnedTitle, 0.0001f)
        }
    }

    @Test fun originalAndPinnedTitlesNeverVisibleTogether() {
        for (offset in -220..340) {
            val m = detailHeaderFractions(offset.toFloat(), 1f)
            assertTrue("Double title at distance $offset: $m",
                m.sourceTitle < 0.00001f || m.pinnedTitle < 0.00001f)
            assertTrue(m.sourceTitle in 0f..1f && m.pinnedTitle in 0f..1f)
            assertTrue(m.photoDissolve in 0f..1f)
        }
        assertEquals(1f, detailHeaderFractions(74f, 1f).sourceTitle, 0f)
        assertEquals(0f, detailHeaderFractions(-24f, 1f).sourceTitle, 0f)
        assertEquals(0f, detailHeaderFractions(-24f, 1f).pinnedTitle, 0f)
        assertEquals(1f, detailHeaderFractions(-70f, 1f).pinnedTitle, 0f)
    }

    @Test fun noStepAtPhaseBoundariesOrFirstFrame() {
        val before = detailHeaderFractions(-23.99f, 1f)
        val after = detailHeaderFractions(-24.01f, 1f)
        assertTrue(before.sourceTitle < 0.0001f)
        assertTrue(after.pinnedTitle < 0.0001f)
        assertEquals(0f, detailHeaderFractions(Float.POSITIVE_INFINITY, 1f).photoDissolve, 0f)
        assertEquals(1f, detailHeaderFractions(Float.NEGATIVE_INFINITY, 1f).pinnedTitle, 0f)
    }
}
