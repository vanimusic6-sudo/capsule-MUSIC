package com.nikhil.yt.innertube.video

import org.junit.Assert.*
import org.junit.Test

class PipeAudioStreamTest {
    private val id = "abcdefghijk"
    private val now = 1_000_000L
    private fun stream(itag: Int = 251, mime: String = "audio/webm", codec: String = "opus", bitrate: Int = 150_000) =
        PipeAudioStream(id, "https://rr1.googlevideo.com/videoplayback?itag=$itag&clen=1000&expire=2000",
            itag, mime, codec, bitrate, 1000L, 48_000, 1000L, 2_000_000L)

    @Test fun validatesExactAudioContractAndRejectsForeignOrRangeBoundUrls() {
        val good = stream()
        assertTrue(good.valid(id, now))
        assertFalse(good.valid("differentid", now))
        assertFalse(good.valid(id, 1_970_000L))
        assertFalse(good.copy(url = good.url.replace("googlevideo.com", "googlevideo.com.evil.test")).valid(id, now))
        assertFalse(good.copy(url = good.url + "&range=0-10").valid(id, now))
        assertFalse(good.copy(length = 999L).valid(id, now))
        assertFalse(good.copy(itag = 140).valid(id, now))
        assertFalse(good.copy(codec = "avc1.123").valid(id, now))
    }

    @Test fun honorsExplicitAacAndLowQualityWithoutVideoStreams() {
        val opus = stream()
        val aac = stream(140, "audio/mp4", "mp4a.40.2", 128_000)
        assertTrue(aac.valid(id, now))
        assertEquals(opus, selectPipeAudio(listOf(opus, aac), PipeAudioQuality.HIGH))
        assertEquals(aac, selectPipeAudio(listOf(opus, aac), PipeAudioQuality.LOW))
        assertEquals(aac, selectPipeAudio(listOf(opus, aac), PipeAudioQuality.AAC))
        assertNull(selectPipeAudio(listOf(opus), PipeAudioQuality.AAC))
    }
}
