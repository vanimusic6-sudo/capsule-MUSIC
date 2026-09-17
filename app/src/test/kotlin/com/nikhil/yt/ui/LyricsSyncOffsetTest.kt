package com.nikhil.yt.ui

import com.nikhil.yt.ui.menu.OFFSET_LIMIT_MS
import com.nikhil.yt.ui.menu.clampOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sync correction is bounded, on the way in as well as on the way out.
 *
 * Past a couple of seconds a file is not offset, it is the wrong file, and an unbounded nudge just
 * lets someone push the lyrics out of sight with no obvious way back. Values also arrive from
 * restored backups and older builds, which is why reading is clamped and not only pressing.
 */
class LyricsSyncOffsetTest {
    @Test fun anOrdinaryCorrectionIsLeftAlone() {
        assertEquals(0, clampOffset(0))
        assertEquals(500, clampOffset(500))
        assertEquals(-1_200, clampOffset(-1_200))
    }

    @Test fun anythingBeyondTheLimitIsPulledBackToIt() {
        assertEquals(OFFSET_LIMIT_MS, clampOffset(OFFSET_LIMIT_MS + 1))
        assertEquals(OFFSET_LIMIT_MS, clampOffset(60_000))
        assertEquals(-OFFSET_LIMIT_MS, clampOffset(-60_000))
        assertEquals(-OFFSET_LIMIT_MS, clampOffset(Int.MIN_VALUE))
        assertEquals(OFFSET_LIMIT_MS, clampOffset(Int.MAX_VALUE))
    }

    /** The limit is a limit on both sides; a lopsided one would be a bug nobody would notice. */
    @Test fun theLimitIsSymmetric() {
        assertEquals(-clampOffset(OFFSET_LIMIT_MS), clampOffset(-OFFSET_LIMIT_MS))
    }
}
