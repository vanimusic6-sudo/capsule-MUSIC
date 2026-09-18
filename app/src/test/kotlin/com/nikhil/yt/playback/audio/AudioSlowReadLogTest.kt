package com.nikhil.yt.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A throttled stream must not crowd its own session out of the export.
 *
 * One capture of an hour-long episode carried ninety-two slow reads, and the export holds a couple
 * of thousand lines in total -- so the very session worth capturing is the one that pushes its own
 * beginning out of the window. The first few reads establish the pattern and every tenth after
 * that tracks it; each line carries the running count and the totals, and the closing line carries
 * the final tally, so the thinning loses nothing that was being read from them.
 */
class AudioSlowReadLogTest {
    private fun printed(slowReads: Int): Int =
        (1..slowReads).count { n ->
            n <= SLOW_READS_LOGGED_IN_FULL || n % SLOW_READ_LOG_INTERVAL == 0
        }

    @Test
    fun theFirstFewAlwaysPrintSoAPatternIsVisibleAtOnce() {
        assertEquals(5, printed(5))
    }

    @Test
    fun aThrottledEpisodeNoLongerFillsTheExport() {
        // The capture that prompted this.
        assertEquals(14, printed(92))
        assertTrue("ninety-two lines is what pushed the session out", printed(92) < 20)
    }

    @Test
    fun aStreamThatIsMerelySlowOnceIsStillReported() {
        assertEquals(1, printed(1))
    }
}
