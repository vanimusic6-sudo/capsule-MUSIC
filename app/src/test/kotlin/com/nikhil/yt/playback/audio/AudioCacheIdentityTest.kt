package com.nikhil.yt.playback.audio

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import com.nikhil.yt.innertube.models.response.PlayerResponse
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.async

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

    @Test fun successfulAudioWaitsForCoverPersistenceBeforeReportingCompletion() {
        val fixture = CacheFixture()
        fixture.bytes["track"] = 100
        AudioCacheIdentity.setLength(fixture.cache, "track", 100)
        var saved = false
        val downloader = ResolvingAudioDownloader("track", fixture.cache, { error("Already cached") },
            { error("Already cached") }, saveArtwork = { kotlinx.coroutines.delay(20); saved = true })
        var complete = false
        downloader.download { _, _, percent ->
            if (percent == 100f) { assertTrue(saved); complete = true }
        }
        assertTrue(complete)
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

    private fun fakeDownload(action: (androidx.media3.exoplayer.offline.Downloader.ProgressListener?) -> Unit) =
        object : androidx.media3.exoplayer.offline.Downloader {
            override fun download(listener: androidx.media3.exoplayer.offline.Downloader.ProgressListener?) = action(listener)
            override fun cancel() = Unit
            override fun remove() = Unit
        }

    @Test fun truncatedEofCannotPublishSuccessOnThisOrTheNextRetry() {
        val fixture = CacheFixture()
        var completions = 0
        val downloader = ResolvingAudioDownloader("track", fixture.cache, { playback() }, { error("unused") },
            createDownloader = {
                fakeDownload { listener ->
                    fixture.bytes["track"] = 50
                    AudioCacheIdentity.setLength(fixture.cache, "track", 50)
                    listener?.onProgress(50, 50, 100f)
                }
            })
        repeat(2) {
            assertThrows(java.io.IOException::class.java) {
                downloader.download { _, _, percent -> if (percent == 100f) completions++ }
            }
        }
        assertEquals(0, completions)
        assertFalse(AudioCacheIdentity.isComplete(fixture.cache, "track"))
    }

    @Test fun transientFailureReusesResolvedUrlAndPartialBytes() {
        val fixture = CacheFixture()
        var resolves = 0
        var transfers = 0
        val downloader = ResolvingAudioDownloader("track", fixture.cache, { resolves++; playback() }, { error("unused") },
            createDownloader = {
                fakeDownload {
                    transfers++
                    if (transfers == 1) {
                        fixture.bytes["track"] = 50
                        throw java.io.IOException("socket reset")
                    }
                    assertEquals(50L, fixture.bytes["track"])
                    fixture.bytes["track"] = 100
                }
            })
        assertThrows(java.io.IOException::class.java) { downloader.download(null) }
        downloader.download(null)
        assertEquals(1, resolves)
        assertTrue(AudioCacheIdentity.isComplete(fixture.cache, "track"))
    }

    @Test fun compatibleClientFallbackResumesBytesAndIsBounded() {
        val fixture = CacheFixture()
        var resolves = 0
        var transfers = 0
        val downloader = ResolvingAudioDownloader("track", fixture.cache, { resolves++; playback() }, { error("unused") },
            recover = { _, _ -> true },
            createDownloader = {
                fakeDownload {
                    transfers++
                    if (transfers == 1) fixture.bytes["track"] = 50
                    else assertEquals(50L, fixture.bytes["track"])
                    throw java.io.IOException("client rejected")
                }
            })
        repeat(2) { assertThrows(java.io.IOException::class.java) { downloader.download(null) } }
        // Three resolutions in the entire downloader lifetime, even across Media3 retries.
        assertEquals(3, resolves)
    }

    @Test fun changedEncodingDiscardsPartialBytesBeforeClientFallbackWrites() {
        val fixture = CacheFixture()
        var resolves = 0
        val downloader = ResolvingAudioDownloader("track", fixture.cache, { playback(if (++resolves == 1) 140 else 251) },
            { error("unused") }, recover = { _, _ -> true }, createDownloader = { data ->
                fakeDownload {
                    if (data.format.itag == 140) {
                        fixture.bytes["track"] = 50
                        throw java.io.IOException("client rejected")
                    }
                    assertNull(fixture.bytes["track"])
                    fixture.bytes["track"] = 100
                }
            })
        downloader.download(null)
        assertEquals(2, resolves)
        assertTrue(AudioCacheIdentity.isComplete(fixture.cache, "track"))
    }

    @Test fun cancelledDownloadDoesNotResolveOrReportCompletion() {
        val fixture = CacheFixture()
        val downloader = ResolvingAudioDownloader("track", fixture.cache, { error("cancelled") }, { error("cancelled") })
        downloader.cancel()
        assertThrows(InterruptedException::class.java) { downloader.download(null) }
    }

    @Test fun cancellationDuringResolveInterruptsTheOwningCoroutine() = kotlinx.coroutines.runBlocking {
        val fixture = CacheFixture()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val downloader = ResolvingAudioDownloader("track", fixture.cache, {
            entered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }, { error("cancelled") })
        val result = async(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { downloader.download(null) }
        }
        kotlinx.coroutines.withTimeout(5000) { entered.await() }
        downloader.cancel()
        assertTrue(kotlinx.coroutines.withTimeout(5000) { result.await() }.exceptionOrNull() is InterruptedException)
    }

    @Test fun legacyFilesUseTheirKnownRoomLengthAndStillRequireRealBytes() {
        val fixture = CacheFixture()
        fixture.bytes["track"] = 100
        assertFalse(AudioCacheIdentity.isComplete(fixture.cache, "track"))
        assertTrue(AudioCacheIdentity.isComplete(fixture.cache, "track", legacyLength = 100))
        fixture.files.getValue("track").delete()
        assertFalse(AudioCacheIdentity.isComplete(fixture.cache, "track", legacyLength = 100))
    }

    @Test fun completeIndexCannotHideAMissingOrTruncatedFile() {
        val fixture = CacheFixture()
        fixture.bytes["track"] = 100
        AudioCacheIdentity.setLength(fixture.cache, "track", 100)
        assertTrue(AudioCacheIdentity.isComplete(fixture.cache, "track"))
        val file = fixture.files.getValue("track")
        java.io.RandomAccessFile(file, "rw").use { it.setLength(20) }
        assertFalse(AudioCacheIdentity.isComplete(fixture.cache, "track"))
        assertThrows(java.io.IOException::class.java) {
            AudioCacheIdentity.requireCompleteDownload(fixture.cache, "track", 100)
        }
        file.delete()
        assertFalse(AudioCacheIdentity.isComplete(fixture.cache, "track"))
    }

    private class CacheFixture {
        val bytes = mutableMapOf<String, Long>()
        private val metadata = mutableMapOf<String, DefaultContentMetadata>()
        val files = mutableMapOf<String, java.io.File>()
        val cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, method, args ->
            val key = args?.firstOrNull() as? String
            when (method.name) {
                "getKeys" -> bytes.keys.toSet()
                "getCachedSpans" -> java.util.TreeSet<androidx.media3.datasource.cache.CacheSpan>().apply {
                    val length = bytes[key] ?: 0L
                    if (length > 0L) {
                        val file = files.getOrPut(key!!) {
                            java.io.File.createTempFile("capsule-cache", ".bin").apply {
                                deleteOnExit(); java.io.RandomAccessFile(this, "rw").use { it.setLength(length) }
                            }
                        }
                        add(androidx.media3.datasource.cache.CacheSpan(key, 0, length, 0, file))
                    }
                }
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
