package com.nikhil.yt.playback.video

import com.nikhil.yt.innertube.video.CapsuleVideoDelivery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class CapsuleWebVideoIndexTest {
    private fun response(videoId: String = "abcdefghijk", indexEnd: Int = 999) = Json.parseToJsonElement("""
        {"videoDetails":{"videoId":"$videoId","lengthSeconds":"1800"},"streamingData":{"adaptiveFormats":[
        {"itag":136,"mimeType":"video/mp4; codecs=\"avc1.4d401f\"","width":1280,"height":720,"fps":30,"bitrate":700000,
         "initRange":{"start":"0","end":"699"},"indexRange":{"start":"700","end":"$indexEnd"},"url":"https://unselected.example/unsafe"},
        {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,
         "initRange":{"start":"0","end":"599"},"indexRange":{"start":"600","end":"799"}}
        ]}}
    """).jsonObject

    private fun source() = CapsuleResolvedVideo(
        videoId = "abcdefghijk", videoUrl = "https://rr.googlevideo.com/videoplayback?pot=selected&sig=video",
        audioUrl = "https://rr.googlevideo.com/videoplayback?pot=selected&sig=audio", qualityLabel = "720p",
        width = 1280, height = 720, videoItag = 136, audioItag = 140, expiresAtMs = Long.MAX_VALUE,
        requestHeaders = mapOf("Origin" to "https://music.youtube.com"),
    )

    @Test fun longClipUsesRealIndexesAndOnlyTheSelectedSignedUrls() {
        val source = source()
        val indexed = CapsuleWebVideoIndex.fromPlayerResponse(response())!!.indexed(source)!!
        assertEquals(CapsuleVideoDelivery.DASH, indexed.delivery)
        val xml = indexed.dashManifest!!
        assertTrue(xml.contains("PT1800.0S"))
        assertTrue(xml.contains("indexRange=\"700-999\""))
        assertTrue(xml.contains("indexRange=\"600-799\""))
        assertTrue(xml.contains("pot=selected&amp;sig=video"))
        assertFalse(xml.contains("unselected.example"))
        assertEquals(source.requestHeaders, indexed.requestHeaders)
        assertEquals(source.videoUrl, indexed.videoUrl)
        assertEquals(source.audioUrl, indexed.audioUrl)
    }

    @Test fun mismatchedResponseMissingFormatOrInvalidIndexLeavesProgressiveFallback() {
        assertNull(CapsuleWebVideoIndex.fromPlayerResponse(response("differentid"))!!.indexed(source()))
        assertNull(CapsuleWebVideoIndex.fromPlayerResponse(response())!!.indexed(source().copy(audioItag = 251)))
        assertNull(CapsuleWebVideoIndex.fromPlayerResponse(response(indexEnd = 650))!!.indexed(source()))
        assertNull(CapsuleWebVideoIndex.fromPlayerResponse(Json.parseToJsonElement("{}").jsonObject))
    }
}
