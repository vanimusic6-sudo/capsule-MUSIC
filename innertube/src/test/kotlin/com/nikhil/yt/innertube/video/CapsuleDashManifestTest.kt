package com.nikhil.yt.innertube.video

import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class CapsuleDashManifestTest {
    private fun video(height: Int = 720, fps: Int = 60, itag: Int = 298) = CapsuleDashTrack(
        "https://rr1.googlevideo.com/videoplayback?expire=9999999999&itag=$itag", itag, "video/mp4", "avc1.640020",
        2_500_000, 0, 739, 740, 4095, 1280, height, fps)
    private val audio = video(0, 0, 140).copy(mimeType = "audio/mp4", codecs = "mp4a.40.2", bitrate = 128000)
    private fun resolve(videos: List<CapsuleDashTrack>, audios: List<CapsuleDashTrack> = listOf(audio), duration: Long = 200_000L) =
        indexedDashVideo("abcdefghijk", duration, videos, audios, CapsuleNewPipeQuality.AUTO, Long.MAX_VALUE)

    @Test fun bestHeightAndNativeFrameRateSurviveWithEscapedUrlsAndRealIndexes() {
        val result = resolve(listOf(video(360, 30, 134), video(720, 30, 136), video(), video(1080, 60, 299)))!!
        assertEquals(CapsuleVideoDelivery.DASH, result.delivery)
        assertEquals(720, result.height); assertEquals(298, result.videoItag); assertEquals(140, result.audioItag)
        val xml = result.dashManifest!!
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val reps = document.getElementsByTagName("Representation")
        assertEquals(3, reps.length) // two native 720 representations, one soundtrack
        assertTrue(xml.contains("frameRate=\"60\"")); assertFalse(xml.contains("height=\"1080\""))
        assertTrue(xml.contains("indexRange=\"740-4095\""))
        assertEquals(video().url, document.getElementsByTagName("BaseURL").item(0).textContent)
    }
    @Test fun malformedRangesSilentVideoAndUntrustedUrlsFallBackRatherThanGuessing() {
        assertNull(resolve(listOf(video().copy(indexStart = 500))))
        assertNull(resolve(listOf(video().copy(indexEnd = 1_000_000))))
        assertNull(resolve(listOf(video().copy(url = "https://evil.test/file"))))
        assertNull(resolve(listOf(video().copy(width = 0))))
        assertNull(resolve(listOf(video()), emptyList()))
        assertNull(resolve(listOf(video()), duration = 0))
    }
    @Test fun directPlayerBuildsDashBeforeHlsAndMuxedWithoutExtraNetworkCalls() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement("""
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abcdefghijk","lengthSeconds":"200"},
             "streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/9999999999/file/index.m3u8",
             "formats":[{"url":"https://rr1.googlevideo.com/file?expire=9999999999","height":360,"width":640,"itag":18,"audioChannels":2,"mimeType":"video/mp4"}],
             "adaptiveFormats":[
              {"url":"https://rr1.googlevideo.com/file?expire=9999999999&itag=298","height":720,"width":1280,"fps":60,"itag":298,"mimeType":"video/mp4; codecs=\"avc1.640020\"","bitrate":2500000,"initRange":{"start":"0","end":"739"},"indexRange":{"start":"740","end":"4095"}},
              {"url":"https://rr1.googlevideo.com/file?expire=9999999999&itag=140","itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,"initRange":{"start":"0","end":"739"},"indexRange":{"start":"740","end":"4095"}}]}}
        """.trimIndent()) as kotlinx.serialization.json.JsonObject
        val result = selectDirectVideo(json, "abcdefghijk", CapsuleNewPipeQuality.AUTO)!!
        assertEquals(CapsuleVideoDelivery.DASH, result.delivery)
        assertEquals(298, result.videoItag)
        assertEquals(60, Regex("frameRate=\"(\\d+)\"").find(result.dashManifest!!)!!.groupValues[1].toInt())
        assertEquals(CapsuleVideoDelivery.PROGRESSIVE, selectDirectVideo(json, "abcdefghijk", CapsuleNewPipeQuality.AUTO, true)!!.delivery)
    }
}
