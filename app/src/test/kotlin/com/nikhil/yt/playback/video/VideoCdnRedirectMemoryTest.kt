package com.nikhil.yt.playback.video

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class VideoCdnRedirectMemoryTest {
    @Test fun shortcutIsBoundToNetworkAndExpires() {
        var now = 0L
        val memory = VideoCdnRedirectMemory { now }
        val origin = "https://rr1.googlevideo.com/videoplayback?sig=1".toHttpUrl()
        val target = "https://rr2.googlevideo.com/videoplayback?sig=2".toHttpUrl()
        memory.remember(1L, origin, target)
        assertEquals(target, memory.get(1L, origin))
        assertNull(memory.get(2L, origin))
        assertNull(memory.get(null, origin))
        now = 60_001L
        assertNull(memory.get(1L, origin))
    }

    @Test fun rejectsUnsafeAndRangeBoundDestinations() {
        val memory = VideoCdnRedirectMemory { 0L }
        val origin = "https://rr1.googlevideo.com/videoplayback?sig=1".toHttpUrl()
        memory.remember(1L, origin, "https://localhost/private".toHttpUrl())
        assertNull(memory.get(1L, origin))
        memory.remember(1L, origin, "https://rr2.googlevideo.com/videoplayback?range=0-99".toHttpUrl())
        assertNull(memory.get(1L, origin))
    }
}
