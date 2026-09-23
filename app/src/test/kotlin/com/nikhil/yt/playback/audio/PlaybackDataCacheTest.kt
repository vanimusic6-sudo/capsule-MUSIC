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
    @Test fun changedSessionQualityOrRouteCannotReuseAnOldUrl() {
        var context = "account-a/high/wifi"
        val cache = PlaybackDataCache(nowMs = { 0L }, currentContext = { context })
        val startedIn = context
        cache.put("track", playback(), startedIn)
        context = "account-b/low/mobile"
        assertNull(cache.get("track"))
        // An obsolete request completing after the change must not relabel its URL as new.
        cache.put("track", playback(), startedIn)
        assertNull(cache.get("track"))
        cache.put("track", playback(), context)
        assertNotNull(cache.get("track"))
    }

    @Test fun stalePrefetchedGenerationIsDroppedBeforePlayback() {
        var now = 0L
        val cache = PlaybackDataCache(nowMs = { now })
        val data = playback()

        cache.put("prefetched", data, prefetched = true)
        now = 1_001L
        assertNull(
            cache.getForPlayback(
                mediaId = "prefetched",
                maxPrefetchedAgeMs = 1_000L,
            ),
        )

        // Foreground priority is not proof of playback: a rapid-skip item can resolve its
        // URL and be abandoned before the CDN receives a request. Its untested URL also ages out.
        cache.put("foreground", data, prefetched = false)
        now = 2_002L
        assertNull(
            cache.getForPlayback(
                mediaId = "foreground",
                maxPrefetchedAgeMs = 1_000L,
            ),
        )
    }

    @Test fun confirmedAudioKeepsAWorkingUrlBeyondTheUnverifiedWindow() {
        var now = 0L
        val cache = PlaybackDataCache(nowMs = { now })
        val data = playback()
        cache.put("playing", data, prefetched = false)
        cache.markDeliveredAudioBytes("playing", data.streamUrl)
        now = 2_000L
        assertSame(data, cache.getForPlayback("playing", maxPrefetchedAgeMs = 1_000L))
    }

    @Test fun oldCdnBytesCannotAccidentallyValidateANewUrlForTheSameTrack() {
        var now = 0L
        val cache = PlaybackDataCache(nowMs = { now })
        val old = playback()
        val renewed = playback().copy(streamUrl = "https://other.googlevideo.com/audio?sig=new")
        cache.put("playing", old)
        cache.put("playing", renewed)
        cache.markDeliveredAudioBytes("playing", old.streamUrl)
        now = 1_001L
        assertNull(cache.getForPlayback("playing", maxPrefetchedAgeMs = 1_000L))
    }

}
