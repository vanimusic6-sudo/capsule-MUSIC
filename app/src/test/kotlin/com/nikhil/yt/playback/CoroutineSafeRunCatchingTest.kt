package com.nikhil.yt.playback

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class CoroutineSafeRunCatchingTest {
    @Test
    fun stalePlaybackCancellationEscapesRunCatching() {
        assertThrows(CancellationException::class.java) {
            runCatching {
                throw CancellationException("Track is no longer near playback")
            }
        }
    }
}
