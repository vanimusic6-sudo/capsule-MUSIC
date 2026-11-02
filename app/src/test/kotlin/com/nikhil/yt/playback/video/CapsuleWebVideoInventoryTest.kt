package com.nikhil.yt.playback.video

import android.app.Application
import com.metrolist.innertubex.extraction.ExtractedStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class CapsuleWebVideoInventoryTest {
    private val base = ExtractedStream(
        videoId = "abcdefghijk", audioUrl = "https://rr.googlevideo.com/videoplayback?expire=4102444800&pot=audio",
        headers = mapOf("Origin" to "https://music.youtube.com"), loudnessDb = null,
        expiresAt = kotlin.time.Instant.parse("2100-01-01T00:00:00Z"), contentLengthBytes = 1000L,
        itag = 140, mimeType = "audio/mp4", codecs = "mp4a.40.2", bitrate = 128000,
        sampleRate = 44100, clientName = "WEB_REMIX", profileId = "WEB_REMIX__po",
        requireBoundedRange = true, rangeChunkSizeBytes = 1_048_576L,
        videoUrl = "https://rr.googlevideo.com/videoplayback?expire=4102444800&pot=video&cpn=nonce&n=solved720",
        videoWidth = 1280, videoHeight = 720, videoItag = 136,
    )

    private fun inventory(id: String = base.videoId): CapsuleWebVideoInventory =
        CapsuleWebVideoInventory.fromPlayerResponse(buildJsonObject {
            putJsonObject("videoDetails") { put("videoId", id) }
            putJsonObject("streamingData") {
                putJsonArray("adaptiveFormats") {
                    for ((itag, height) in listOf(134 to 360, 135 to 480, 136 to 720)) add(buildJsonObject {
                        put("itag", itag); put("height", height); put("width", height * 16 / 9)
                        put("bitrate", height * 1000); put("contentLength", "10000")
                        put("mimeType", "video/mp4; codecs=\"avc1.4d401f\"")
                        put("url", "https://rr.googlevideo.com/videoplayback?expire=4102444700&n=raw$height&itag=$itag")
                    })
                }
            }
        })!!

    @Test fun eachManualResolutionChoosesItsOwnItagAndPreservesClipTokensAndAudio() = runTest {
        val inventory = inventory()
        for ((height, itag) in listOf(360 to 134, 480 to 135, 720 to 136)) {
            var processed = 0
            val result = selectWebVideoQuality(base, inventory, height) {
                processed++
                it.copy(url = it.url!!.replace("raw$height", "solved$height"))
            }
            assertEquals(itag, result.videoItag)
            assertEquals(height, result.videoHeight)
            assertTrue(result.videoUrl!!.contains("n=solved$height"))
            assertFalse(result.videoUrl!!.contains("raw$height"))
            assertTrue(result.videoUrl!!.contains("pot=video"))
            assertTrue(result.videoUrl!!.contains("cpn=nonce"))
            assertEquals(base.audioUrl, result.audioUrl)
            assertEquals(base.headers, result.headers)
            assertEquals(if (height == 720) 0 else 1, processed)
            if (height != 720) assertTrue(result.expiresAt!! < base.expiresAt!!)
        }
    }

    @Test fun missingIntermediateResolutionUsesTheNearestLowerFormatNotAnOversizedStream() = runTest {
        val result = selectWebVideoQuality(base, inventory(), 600) { it.copy(url = it.url!!.replace("raw480", "solved480")) }
        assertEquals(480, result.videoHeight)
        assertNull(inventory().select(240))
    }

    @Test fun anotherClipsInventoryNeverSuppliesUrlsOrTokens() = runTest {
        var processed = 0
        val result = selectWebVideoQuality(base, inventory("differentid"), 720) { processed++; it }
        assertSame(base, result)
        assertEquals(0, processed)
    }

    @Test fun unsolvedOrUnsafeAlternativeIsRejectedWithoutReusingThe720Signature() = runTest {
        try {
            selectWebVideoQuality(base, inventory(), 360) { it.copy(url = null) }
            fail("Unsolved variant accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            selectWebVideoQuality(base, inventory(), 360) { it.copy(url = "https://127.0.0.1/private") }
            fail("Unsafe variant accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
