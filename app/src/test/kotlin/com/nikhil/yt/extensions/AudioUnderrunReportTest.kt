package com.nikhil.yt.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioUnderrunReportTest {
    @Test
    fun `the sink going dry mid-song is a fault`() {
        // The two believable reports in the capture, against the playback they happened inside.
        assertFalse(isAudioResumeArtefact(elapsedSinceLastFeedMs = 2627, playingForMs = 34_520))
        assertFalse(isAudioResumeArtefact(elapsedSinceLastFeedMs = 4150, playingForMs = 32_140))
    }

    @Test
    fun `a gap reaching back past the start of playback is the resume, not a fault`() {
        // Twenty-one minutes of "underrun" reported two seconds after playback resumed.
        assertTrue(isAudioResumeArtefact(elapsedSinceLastFeedMs = 1_285_866, playingForMs = 2_000))
        assertTrue(isAudioResumeArtefact(elapsedSinceLastFeedMs = 382_442, playingForMs = 500))
        assertTrue(isAudioResumeArtefact(elapsedSinceLastFeedMs = 28_710, playingForMs = 1_200))
    }

    @Test
    fun `a long gap is still a fault when playback was running for longer`() {
        assertFalse(
            "a podcast playing for an hour can genuinely stall for half a minute",
            isAudioResumeArtefact(elapsedSinceLastFeedMs = 30_000, playingForMs = 3_600_000),
        )
    }

    @Test
    fun `a report that races its own resume is read as the resume`() {
        assertTrue(isAudioResumeArtefact(elapsedSinceLastFeedMs = 5_000, playingForMs = 0))
        assertTrue(isAudioResumeArtefact(elapsedSinceLastFeedMs = 300, playingForMs = 0))
        assertFalse("but not beyond the slack", isAudioResumeArtefact(200, playingForMs = 0))
    }

    @Test
    fun `nothing is raised as a fault when there is nothing to compare against`() {
        assertTrue(isAudioResumeArtefact(elapsedSinceLastFeedMs = 4_150, playingForMs = null))
    }

    @Test
    fun `a report with no gap at all says nothing`() {
        assertTrue(isAudioResumeArtefact(elapsedSinceLastFeedMs = 0, playingForMs = 10_000))
        assertTrue(isAudioResumeArtefact(elapsedSinceLastFeedMs = -1, playingForMs = 10_000))
    }
}
