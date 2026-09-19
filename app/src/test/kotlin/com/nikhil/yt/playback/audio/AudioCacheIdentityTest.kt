package com.nikhil.yt.playback.audio

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import com.nikhil.yt.innertube.models.response.PlayerResponse
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AudioCacheIdentityTest {
    private fun playback(itag: Int = 140) = CapsuleAudioEngine.PlaybackData(
        null, null, null,
        PlayerResponse.StreamingData.Format(itag = itag, mimeType = "audio/mp4", bitrate = 128000, quality = "", contentLength = 100),
        "https://example.googlevideo.com/audio", 60,
    )

    @Test fun differentEncodingsNeverShareBytesAndMidStreamSwitchRequiresNewSource() {
        val mp4 = AudioCacheIdentity.key("track", playback(140))
        val opus = AudioCacheIdentity.key("track", playback(251))
        assertNotEquals(mp4, opus)
        val source = AudioStreamContract()
        source.bind(mp4)
        source.bind(mp4) // URL refresh with unchanged encoding is safe.
        assertThrows(AudioFormatChangedException::class.java) { source.bind(opus) }
    }

    @Test fun unknownPartialDownloadIsRemovedBeforeResuming() {
        val fixture = CacheFixture()
        fixture.bytes["track"] = 50
        AudioCacheIdentity.prepareDownload(fixture.cache, "track", "format-a", 100)
        assertFalse(fixture.bytes.containsKey("track"))
        fixture.bytes["track"] = 50
        AudioCacheIdentity.prepareDownload(fixture.cache, "track", "format-a", 100)
        assertEquals(50L, fixture.bytes["track"])
        AudioCacheIdentity.prepareDownload(fixture.cache, "track", "format-b", 100)
        assertFalse(fixture.bytes.containsKey("track"))
    }

    @Test fun completeLegacyDownloadsAreRecognizedWithoutAPlayerRequest() {
        val fixture = CacheFixture()
        fixture.bytes["track"] = 100
        AudioCacheIdentity.setLength(fixture.cache, "track", 100)
        val downloader = ResolvingAudioDownloader("track", fixture.cache, { error("Must stay offline") }, { error("Must stay offline") })
        var completed = false
        downloader.download { length, bytes, percent ->
            completed = length == 100L && bytes == 100L && percent == 100f
        }
        assertTrue(completed)
        assertEquals(100L, fixture.bytes["track"])
    }

    @Test fun cacheListingAndRemovalHandleEveryFormatOfOneSong() {
        val fixture = CacheFixture()
        val key = AudioCacheIdentity.key("track", playback())
        fixture.bytes[key] = 100
        fixture.bytes["track"] = 30
        fixture.bytes["other"] = 20
        AudioCacheIdentity.setLength(fixture.cache, key, 100)
        assertEquals(key, AudioCacheIdentity.completeKey(fixture.cache, "track"))
        AudioCacheIdentity.remove(fixture.cache, "track")
        assertEquals(setOf("other"), fixture.bytes.keys)
    }

    @Test fun failedResolveCanBeRetriedByDownloadManager() {
        val fixture = CacheFixture()
        var requests = 0
        val downloader = ResolvingAudioDownloader("track", fixture.cache, {
            requests++
            throw java.io.IOException("Temporary connection failure")
        }, { error("No stream resolved") })
        repeat(2) {
            assertThrows(java.io.IOException::class.java) { downloader.download(null) }
        }
        assertEquals(2, requests)
    }

    @Test fun changedPreferenceDoesNotDeleteACompleteDownload() {
        val fixture = CacheFixture()
        fixture.bytes["track"] = 100
        AudioCacheIdentity.setLength(fixture.cache, "track", 100)
        AudioCacheIdentity.prepareDownload(fixture.cache, "track", "another-format", 200)
        assertEquals(100L, fixture.bytes["track"])
        assertTrue(AudioCacheIdentity.isComplete(fixture.cache, "track"))
    }

    private class CacheFixture {
        val bytes = mutableMapOf<String, Long>()
        private val metadata = mutableMapOf<String, DefaultContentMetadata>()
        val cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, method, args ->
            val key = args?.firstOrNull() as? String
            when (method.name) {
                "getKeys" -> bytes.keys.toSet()
                "getContentMetadata" -> metadata[key] ?: DefaultContentMetadata()
                "applyContentMetadataMutations" -> {
                    metadata[key!!] = (metadata[key] ?: DefaultContentMetadata())
                        .copyWithMutationsApplied(args!![1] as ContentMetadataMutations)
                    null
                }
                "isCached" -> (bytes[key] ?: 0) >= (args!![1] as Long) + (args[2] as Long)
                "removeResource" -> { bytes.remove(key); metadata.remove(key); null }
                else -> error("Unexpected Cache call: ${method.name}")
            }
        } as Cache
    }
}
