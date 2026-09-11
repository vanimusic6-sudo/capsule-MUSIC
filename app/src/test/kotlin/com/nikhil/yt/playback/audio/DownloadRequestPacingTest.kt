package com.nikhil.yt.playback.audio

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class DownloadRequestPacingTest {
    @Test fun seededSequenceStaysBoundedAndVaries() {
        val pacing = DownloadRequestPacing(Random(49))
        val delays = List(1_000) { pacing.nextSpacingMs() }
        assertTrue(delays.all { it >= DownloadRequestPacing.MIN_SPACING_MS })
        assertTrue(delays.all { it <= DownloadRequestPacing.MAX_SPACING_MS })
        assertTrue(delays.distinct().size > 1)
    }

    @Test fun seededGeneratorIsReproducible() {
        val first = DownloadRequestPacing(Random(49))
        val second = DownloadRequestPacing(Random(49))
        assertEquals(List(20) { first.nextSpacingMs() }, List(20) { second.nextSpacingMs() })
    }
}
