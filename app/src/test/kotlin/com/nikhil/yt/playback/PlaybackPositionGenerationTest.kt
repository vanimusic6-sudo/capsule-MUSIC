package com.nikhil.yt.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPositionGenerationTest {
    @Test
    fun unchangedGenerationRemainsCurrent() {
        val generation = PlaybackPositionGeneration()
        val snapshot = generation.snapshot()

        assertTrue(generation.isCurrent(snapshot))
    }

    @Test
    fun discontinuityInvalidatesOlderSnapshot() {
        val generation = PlaybackPositionGeneration()
        val snapshot = generation.snapshot()

        generation.markDiscontinuity()

        assertFalse(generation.isCurrent(snapshot))
        assertTrue(generation.isCurrent(generation.snapshot()))
    }

    @Test
    fun everyDiscontinuityInvalidatesPreviousSnapshot() {
        val generation = PlaybackPositionGeneration()
        val first = generation.snapshot()
        generation.markDiscontinuity()
        val second = generation.snapshot()
        generation.markDiscontinuity()

        assertFalse(generation.isCurrent(first))
        assertFalse(generation.isCurrent(second))
        assertTrue(generation.isCurrent(generation.snapshot()))
    }
}
