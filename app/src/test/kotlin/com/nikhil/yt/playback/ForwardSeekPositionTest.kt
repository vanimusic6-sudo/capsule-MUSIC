package com.nikhil.yt.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardSeekPositionTest {
    @Test
    fun knownDurationClampsToTrackEnd() {
        assertEquals(
            60_000L,
            forwardSeekPositionMs(
                currentPositionMs = 55_000L,
                durationMs = 60_000L,
            ),
        )
    }

    @Test
    fun unknownDurationStillMovesForward() {
        assertEquals(
            35_000L,
            forwardSeekPositionMs(
                currentPositionMs = 25_000L,
                durationMs = C.TIME_UNSET,
            ),
        )
    }

    @Test
    fun negativeCurrentPositionStartsFromZero() {
        assertEquals(
            10_000L,
            forwardSeekPositionMs(
                currentPositionMs = -500L,
                durationMs = C.TIME_UNSET,
            ),
        )
    }

    @Test
    fun additionSaturatesInsteadOfOverflowing() {
        assertEquals(
            Long.MAX_VALUE,
            forwardSeekPositionMs(
                currentPositionMs = Long.MAX_VALUE - 5L,
                durationMs = C.TIME_UNSET,
            ),
        )
    }

    @Test
    fun negativeSeekDeltaCannotMoveBackward() {
        assertEquals(
            25_000L,
            forwardSeekPositionMs(
                currentPositionMs = 25_000L,
                durationMs = C.TIME_UNSET,
                seekDeltaMs = -10_000L,
            ),
        )
    }
}
