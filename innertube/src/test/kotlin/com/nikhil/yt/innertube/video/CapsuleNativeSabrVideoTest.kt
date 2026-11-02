package com.nikhil.yt.innertube.video

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleNativeSabrVideoTest {
    private val id = "abcdefghijk"

    private fun response(url: String = "https://rr1.googlevideo.com/videoplayback?expire=1999999999"): JsonObject =
        Json.parseToJsonElement(
            """
            {
              "playabilityStatus":{"status":"OK"},
              "videoDetails":{"videoId":"$id","lengthSeconds":"240"},
              "playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{
                "videoPlaybackUstreamerConfig":"AQIDBAUGBwg="}}},
              "streamingData":{"serverAbrStreamingUrl":"$url","adaptiveFormats":[
                {"itag":134,"mimeType":"video/mp4; codecs=\"avc1.4d401e\"","height":360,
                 "width":640,"fps":30,"bitrate":500000,"contentLength":"18000000",
                 "lastModified":"1700000000000000","approxDurationMs":"240000"},
                {"itag":136,"mimeType":"video/mp4; codecs=\"avc1.4d401f\"","height":720,
                 "width":1280,"fps":30,"bitrate":1500000,"contentLength":"48000000",
                 "lastModified":"1700000000000000","approxDurationMs":"240000"},
                {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
                 "bitrate":128000,"contentLength":"3840000","lastModified":"1700000000000000"}]}}
            }
            """.trimIndent(),
        ) as JsonObject

    @Test fun nativeExtractorSelectsDualTrack720pWithoutDirectVideoUrl() {
        val selected = selectNativeSabrVideo(
            response(), id, CapsuleNewPipeQuality.P720,
            clientName = 101, clientVersion = "1.2.0", userAgent = "VisionOS",
            nowMs = 1_800_000_000_000L,
        )
        assertNotNull(selected)
        assertEquals(CapsuleVideoDelivery.SABR, selected!!.delivery)
        assertEquals(136, selected.videoItag)
        assertEquals(140, selected.audioItag)
        assertEquals(720, selected.height)
        assertEquals(48_000_000L, selected.sabrSpec!!.videoLength)
        assertEquals(3_840_000L, selected.sabrSpec!!.audioLength)
    }

    @Test fun qualitySelectionPinsTheExactLowerVideoItag() {
        val selected = selectNativeSabrVideo(
            response(), id, CapsuleNewPipeQuality.P360,
            101, "1.2.0", "VisionOS", nowMs = 1_800_000_000_000L,
        )!!
        assertEquals(134, selected.sabrSpec!!.videoItag)
    }

    @Test fun invalidHostnameAndMismatchedClipCannotBeProbed() {
        assertNull(selectNativeSabrVideo(response("https://attacker.test/stream"),
            id, CapsuleNewPipeQuality.AUTO, 101, "1.2.0", "VisionOS",
            nowMs = 1_800_000_000_000L))
        assertNull(selectNativeSabrVideo(response(),
            "an0therid11", CapsuleNewPipeQuality.AUTO, 101, "1.2.0", "VisionOS",
            nowMs = 1_800_000_000_000L))
    }

    @Test fun specRejectsOversizeSessionAndInvalidVideoLength() {
        val spec = selectNativeSabrVideo(response(), id, CapsuleNewPipeQuality.AUTO,
            101, "1.2.0", "VisionOS", nowMs = 1_800_000_000_000L)!!.sabrSpec!!
        assertTrue(spec.isValid())
        assertFalse(spec.copy(videoLength = -1L).isValid())
        assertFalse(spec.copy(clientVersion = "inject\\nAuthorization").isValid())
        assertFalse(spec.copy(ustreamerConfigBase64 = "AAAA".repeat(20_000)).isValid())
    }
}
