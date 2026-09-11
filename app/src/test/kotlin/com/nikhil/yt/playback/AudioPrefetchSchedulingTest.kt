package com.nikhil.yt.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPrefetchSchedulingTest {
    @Test
    fun longTrackWaitsUntilJustInTimeLeadWindow() {
        assertEquals(
            135_000L,
            audioPrefetchWaitMs(
                durationMs = 180_000L,
                positionMs = 0L,
                isPlaying = true,
            ),
        )
    }

    @Test
    fun shortTrackStillWaitsForCurrentPlaybackToWarm() {
        assertEquals(
            3_000L,
            audioPrefetchWaitMs(
                durationMs = 30_000L,
                positionMs = 0L,
                isPlaying = true,
            ),
        )
    }

    @Test
    fun pausedOrBufferingTrackOnlySchedulesARecheck() {
        assertEquals(
            AUDIO_PREFETCH_RECHECK_MS,
            audioPrefetchWaitMs(
                durationMs = 180_000L,
                positionMs = 120_000L,
                isPlaying = false,
            ),
        )
    }

    @Test
    fun readyTrackInsideLeadWindowCanPrefetchImmediately() {
        assertEquals(
            0L,
            audioPrefetchWaitMs(
                durationMs = 180_000L,
                positionMs = 150_000L,
                isPlaying = true,
            ),
        )
    }

    @Test
    fun unknownDurationStillWaitsForCurrentPlaybackWarmup() {
        assertEquals(
            2_000L,
            audioPrefetchWaitMs(
                durationMs = C.TIME_UNSET,
                positionMs = 1_000L,
                isPlaying = true,
            ),
        )
    }
}
