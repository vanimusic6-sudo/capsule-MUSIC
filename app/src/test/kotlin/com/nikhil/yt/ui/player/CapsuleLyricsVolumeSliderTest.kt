package com.nikhil.yt.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleLyricsVolumeSliderTest {
    @Test fun marksFollowTheFillAndAreNotCappedAtSeven() {
        val count = { filled: Float ->
            capsuleLyricsVolumeDashCount(
                activeWidthPx = filled,
                firstX = 10f,
                dashWidth = 5f,
                stride = 12f,
                endInset = 8f,
            )
        }
        assertEquals(0, count(0f))
        assertEquals(0, count(22f))
        assertEquals(1, count(23f))
        assertEquals(7, count(95f))
        assertEquals(14, count(180f))
        assertEquals(31, count(395f))
    }

    @Test fun invalidGeometryNeverDrawsMarks() {
        assertEquals(0, capsuleLyricsVolumeDashCount(Float.NaN, 10f, 5f, 12f, 8f))
        assertEquals(0, capsuleLyricsVolumeDashCount(400f, 10f, 5f, 0f, 8f))
    }
}
