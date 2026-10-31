package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.sabr.SabrChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleSabrAudioProbeTest {
    private val init = SabrChunk(
        data = byteArrayOf(1, 2, 3, 4),
        startRange = 0,
        isInitialization = true,
        sequenceNumber = null,
        startMs = null,
        durationMs = null,
    )
    private fun media(startRange: Long) = SabrChunk(
        data = byteArrayOf(5, 6, 7),
        startRange = startRange,
        isInitialization = false,
        sequenceNumber = 0,
        startMs = 0,
        durationMs = 1_000,
    )

    @Test fun reportsContiguousInitializationAndFirstAudioMedia() {
        val result = assessSabrAudioProbe(listOf(init, media(4)), itag = 140)
        assertTrue(result.hasInitialization)
        assertTrue(result.mediaRangeFollowsInitialization)
        assertEquals(3, result.firstMediaBytes)
        assertEquals(140, result.codecItag)
    }

    @Test fun detectsGapInsteadOfClaimingSeekableAudio() {
        val result = assessSabrAudioProbe(listOf(init, media(2048)), itag = 251)
        assertFalse(result.mediaRangeFollowsInitialization)
    }

    @Test fun rejectsAProtocolEnvelopeWithoutMediaPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            assessSabrAudioProbe(listOf(init), itag = 140)
        }
        assertThrows(IllegalArgumentException::class.java) {
            assessSabrAudioProbe(listOf(media(0)), itag = 140)
        }
    }
}
