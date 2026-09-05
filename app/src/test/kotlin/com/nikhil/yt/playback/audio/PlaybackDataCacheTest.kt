package com.nikhil.yt.playback.audio

import com.nikhil.yt.innertube.models.response.PlayerResponse
import org.junit.Assert.*
import org.junit.Test

class PlaybackDataCacheTest {
    private fun playback() = CapsuleAudioEngine.PlaybackData(
        audioConfig = PlayerResponse.PlayerConfig.AudioConfig(loudnessDb = 8.0),
        videoDetails = PlayerResponse.VideoDetails("track", title = "Track", lengthSeconds = "180"),
        playbackTracking = PlayerResponse.PlaybackTracking(
            videostatsPlaybackUrl = PlayerResponse.PlaybackTracking.VideostatsPlaybackUrl("https://www.youtube.com/tracking"),
        ),
        format = PlayerResponse.StreamingData.Format(
            itag = 140, mimeType = "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 128000, quality = "medium",
        ),
        streamUrl = "https://rr.example.googlevideo.com/videoplayback?c=WEB_REMIX",
        streamExpiresInSeconds = 60,
        streamHeaders = mapOf("User-Agent" to "extractor-agent", "Origin" to "https://music.youtube.com"),
    )

    @Test fun aPrefetchedTrackRetainsNormalizationHeadersAndHistory() {
        val cache = PlaybackDataCache(nowMs = { 0L })
        val data = playback()
        cache.put("track", data)
        val result = requireNotNull(cache.get("track"))
        assertSame(data, result)
        assertEquals(8.0, result.audioConfig!!.loudnessDb!!, 0.0)
        assertEquals("extractor-agent", result.streamHeaders["User-Agent"])
        assertEquals(data.playbackTracking, result.playbackTracking)
    }

    @Test fun nearlyExpiredUrlsAreNotReusedAndCacheIsBounded() {
        var now = 0L
        val cache = PlaybackDataCache(capacity = 2, nowMs = { now })
        cache.put("one", playback())
        cache.put("two", playback())
        assertNotNull(cache.get("one"))
        cache.put("three", playback())
        assertNull(cache.get("two"))
        now = 55_000L
        assertNull(cache.get("one"))
        assertNull(cache.get("three"))
    }
}
