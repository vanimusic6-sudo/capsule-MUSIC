package com.nikhil.yt.playback

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wait nobody could have noticed is not news.
 *
 * A capture carried four "loader blocked" lines in three seconds for one track, waiting 30, 14,
 * 9 and 3 milliseconds — the loader picking up a URL it already had. All four were written at
 * info and all four said "blocked". Labelling the ordinary path as trouble is how a reader stops
 * trusting the lines that are.
 */
class LoaderWaitReportingTest {
    private fun worthReporting(waitedMs: Long) = waitedMs >= LOADER_WAIT_WORTH_REPORTING_MS

    @Test fun `the four waits from the capture are not worth an info line`() {
        for (waited in listOf(30L, 14L, 9L, 3L)) {
            assertTrue("a ${waited}ms wait was reported as trouble", !worthReporting(waited))
        }
    }

    @Test fun `a wait long enough to be heard is still reported`() {
        // The one in the same capture that genuinely delayed the start of a track.
        assertTrue(worthReporting(2_537L))
        assertTrue(worthReporting(393L))
    }

    @Test fun `the threshold sits where a delay stops being invisible`() {
        assertTrue(LOADER_WAIT_WORTH_REPORTING_MS in 100L..500L)
        assertTrue(!worthReporting(LOADER_WAIT_WORTH_REPORTING_MS - 1))
        assertTrue(worthReporting(LOADER_WAIT_WORTH_REPORTING_MS))
    }
}
