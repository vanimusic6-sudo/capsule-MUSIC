package com.nikhil.yt.playback.video

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VideoRejectedLinkMemoryTest {
    @Test fun rejectsOnlyTheExactLinkForABoundedInterval() {
        var now = 0L
        val memory = VideoRejectedLinkMemory { now }
        val url = "https://rr.googlevideo.com/videoplayback?sig=old"
        memory.reject(url)
        assertTrue(memory.isRejected(url))
        assertFalse(memory.isRejected(url.replace("old", "new")))
        now = 600_001L
        assertFalse(memory.isRejected(url))
    }

    @Test fun retiredLinkDoesNotRetryInsideMedia3() {
        assertTrue(isTerminalVideoLoadFailure(IOException("wrapper", VideoRetiredLinkException())))
        assertFalse(isTerminalVideoLoadFailure(IOException("temporary reset")))
    }
}
