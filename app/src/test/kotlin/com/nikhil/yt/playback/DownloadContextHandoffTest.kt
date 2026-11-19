package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test

class DownloadContextHandoffTest {
    @Test fun oneNewAttemptAfterNetworkSwitch() {
        assertTrue(canRetryDownloadAfterContextChange(0, 2))
        assertFalse(canRetryDownloadAfterContextChange(1, 2))
        assertFalse(canRetryDownloadAfterContextChange(0, 1))
        assertFalse(canRetryDownloadAfterContextChange(-1, 2))
        assertFalse(canRetryDownloadAfterContextChange(0, 0))
    }
}
