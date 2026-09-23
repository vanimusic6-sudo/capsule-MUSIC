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

/**
 * A decoder cannot starve for data that is already decoded and waiting in front of it.
 *
 * Comparing the gap with how long playback had been running only catches a pause that happened
 * before playback got going. Pause a track twenty minutes in, resume it, and the gap is shorter
 * than the running time, so the report passes the old test — a capture raised seven that way,
 * naming gaps of up to 271 seconds while up to 102 seconds of audio sat buffered.
 */
class AudioUnderrunBufferTest {
    @Test
    fun `the seven false reports from the capture are recognised`() {
        // gap, playing for, buffered ahead — read off the capture that prompted this.
        val reports =
            listOf(
                Triple(346_143L, 396_694L, 33_581L),
                Triple(104_548L, 118_308L, 102_101L),
                Triple(140_867L, 145_082L, 96_481L),
                Triple(143_097L, 163_143L, 71_041L),
                Triple(8_553L, 21_594L, 56_701L),
                Triple(271_139L, 273_758L, 52_761L),
            )

        for ((gap, playingFor, buffered) in reports) {
            assertTrue(
                "gap=$gap playingFor=$playingFor buffered=$buffered was called a fault",
                isAudioResumeArtefact(gap, playingFor, buffered),
            )
        }
    }

    @Test
    fun `the one report with a dry buffer is still a fault`() {
        // The only one of the seven the sink could actually have starved on.
        assertFalse(
            isAudioResumeArtefact(
                elapsedSinceLastFeedMs = 453_358,
                playingForMs = 486_539,
                bufferedAheadMs = 0,
            ),
        )
    }

    @Test
    fun `a genuine mid-song stall with a drained buffer is still raised`() {
        assertFalse(
            isAudioResumeArtefact(
                elapsedSinceLastFeedMs = 2_627,
                playingForMs = 34_520,
                bufferedAheadMs = 800,
            ),
        )
    }

    @Test
    fun `the threshold is clear of the output buffer it has to beat`() {
        // The sink's own output buffer is 1.5 s; anything at or under that cannot rule out a
        // starve, so the threshold has to sit well above it.
        assertTrue(AUDIO_UNDERRUN_STARVED_BUFFER_MS > 1_500L)
        assertFalse(
            isAudioResumeArtefact(
                elapsedSinceLastFeedMs = 3_000,
                playingForMs = 60_000,
                bufferedAheadMs = AUDIO_UNDERRUN_STARVED_BUFFER_MS - 1,
            ),
        )
        assertTrue(
            isAudioResumeArtefact(
                elapsedSinceLastFeedMs = 3_000,
                playingForMs = 60_000,
                bufferedAheadMs = AUDIO_UNDERRUN_STARVED_BUFFER_MS,
            ),
        )
    }

    @Test
    fun `the old rule still stands on its own for a report before playback began`() {
        // No buffer reading can rescue a report that has nothing to compare against.
        assertTrue(
            isAudioResumeArtefact(
                elapsedSinceLastFeedMs = 1_285_866,
                playingForMs = null,
                bufferedAheadMs = 0,
            ),
        )
    }
}
