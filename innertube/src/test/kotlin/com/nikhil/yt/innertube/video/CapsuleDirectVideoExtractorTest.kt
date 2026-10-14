package com.nikhil.yt.innertube.video

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CapsuleDirectVideoExtractorTest {
    @Test fun rejectedGuestIsEvictedAndBotVerdictSurvivesTheDirectRoute() {
        val sessions = CapsuleVideoGuestSessions { 0L }
        sessions.beginSession("process:0")
        sessions.put("101", "rejected-visitor")
        val blocked = buildJsonObject {
            put("playabilityStatus", buildJsonObject {
                put("status", "LOGIN_REQUIRED")
                put("reason", "Sign in to confirm you're not a bot")
            })
        }
        val failure = runCatching {
            requireDirectVideo(blocked, "abcdefghijk", CapsuleNewPipeQuality.AUTO,
                onRejectedSession = { sessions.remove("101") })
        }.exceptionOrNull()!!
        assertNull(sessions["101"])
        assertEquals(CapsuleNewPipeFailure.BOT_BLOCKED, CapsuleNewPipeExtractor.classify(failure))
        assertEquals("PLAYER_LOGIN_REQUIRED:BOT_CHECK", CapsuleNewPipeExtractor.routeDiagnostic(failure))
    }

    @Test fun resetGenerationAndProcessRestartDropTheGuestButOrdinaryRequestsReuseIt() {
        val sessions = CapsuleVideoGuestSessions { 0L }
        sessions.beginSession("first-process:0")
        sessions.put("101", "old-visitor")
        sessions.beginSession("first-process:0")
        assertEquals("old-visitor", sessions["101"])
        sessions.beginSession("first-process:1")
        assertNull(sessions["101"])
        sessions.put("101", "new-visitor")
        sessions.beginSession("second-process:0")
        assertNull(sessions["101"])
    }

    @Test fun ageRestrictionDoesNotDiscardAHealthyGuestOrBecomeABotBan() {
        var discarded = false
        val data = buildJsonObject {
            put("playabilityStatus", buildJsonObject {
                put("status", "LOGIN_REQUIRED"); put("reason", "Sign in to confirm your age")
            })
        }
        val failure = runCatching {
            requireDirectVideo(data, "abcdefghijk", CapsuleNewPipeQuality.AUTO,
                onRejectedSession = { discarded = true })
        }.exceptionOrNull()!!
        assertFalse(discarded)
        assertEquals(CapsuleNewPipeFailure.UNAVAILABLE, CapsuleNewPipeExtractor.classify(failure))
    }

    @Test fun rejectedFormatsExplainFallbackWithoutExposingSignedUrls() {
        val data = response(listOf(format(360, 18, true, "&n=secret-challenge&sig=secret-signature")))
        val failure = runCatching {
            requireDirectVideo(data, "abcdefghijk", CapsuleNewPipeQuality.AUTO, nowMs = now)
        }.exceptionOrNull()!!
        val diagnostic = CapsuleNewPipeExtractor.routeDiagnostic(failure)
        assertTrue(diagnostic.startsWith("NO_COMPATIBLE_STREAM:formats=1,cipher=0,n=1"))
        assertFalse(diagnostic.contains("secret"))
        assertFalse(diagnostic.contains("googlevideo"))
        assertEquals(CapsuleNewPipeFailure.EXTRACTOR, CapsuleNewPipeExtractor.classify(failure))
    }

    @Test fun directSuccessStillSelectsTheExistingPlayableStream() {
        val data = response(listOf(format(360, 18, true)))
        assertEquals(select(data), requireDirectVideo(data, "abcdefghijk", CapsuleNewPipeQuality.AUTO, nowMs = now,
            onRejectedSession = { fail("A healthy session must be reused") }))
    }

    private val now = 1_000_000L
    private fun format(height: Int, itag: Int, audio: Boolean = false, suffix: String = "") = buildJsonObject {
        put("url", "https://rr1.googlevideo.com/videoplayback?expire=2000&itag=$itag$suffix")
        put("height", height); put("width", 1280); put("itag", itag)
        put("mimeType", if (height == 0) "audio/mp4" else "video/mp4")
        put("bitrate", 128000); put("qualityLabel", "${height}p")
        if (audio) put("audioChannels", 2)
    }
    private fun response(muxed: List<JsonObject>, adaptive: List<JsonObject> = emptyList(), id: String = "abcdefghijk") = buildJsonObject {
        put("playabilityStatus", buildJsonObject { put("status", "OK") })
        put("videoDetails", buildJsonObject { put("videoId", id) })
        put("streamingData", buildJsonObject { put("formats", JsonArray(muxed)); put("adaptiveFormats", JsonArray(adaptive)) })
    }
    private fun select(data: JsonObject, quality: CapsuleNewPipeQuality = CapsuleNewPipeQuality.AUTO, muxedOnly: Boolean = false) =
        selectDirectVideo(data, "abcdefghijk", quality, muxedOnly, now)
    @Test fun autoAndExplicit720KeepTheBestQualityWithMatchingAudio() {
        val data = response(listOf(format(360, 18, true)), listOf(format(720, 136), format(0, 140)))
        assertEquals(136, select(data)!!.videoItag)
        val hd = select(data, CapsuleNewPipeQuality.P720)!!
        assertEquals(136, hd.videoItag); assertEquals(140, hd.audioItag)
        assertEquals(18, select(data, CapsuleNewPipeQuality.P720, true)!!.videoItag)
    }
    @Test fun cannotExceed720OrReturnSilentAdaptiveClip() {
        assertNull(select(response(emptyList(), listOf(format(1080, 137), format(0, 140))), CapsuleNewPipeQuality.P720))
        assertNull(select(response(emptyList(), listOf(format(720, 136))), CapsuleNewPipeQuality.P720))
    }
    @Test fun nChallengeBakedRangeExpiredAndWrongIdGoToMaintainedExtractor() {
        assertNull(select(response(listOf(format(360, 18, true, "&n=unsolved")))))
        assertNull(select(response(listOf(format(360, 18, true, "&range=0-1023")))))
        assertNull(select(response(listOf(format(360, 18, true)), id = "differentID")))
        val expired = format(360, 18, true).toMutableMap().apply { put("url", JsonPrimitive("https://rr1.googlevideo.com/videoplayback?expire=1001")) }
        assertNull(select(response(listOf(JsonObject(expired)))))
    }
    @Test fun hlsFromSamePlayerResponseAvoidsRepeatedExtractionAndRetainsQualityCeiling() {
        val base = response(listOf(format(360, 18, true, "&n=unsolved")))
        val data = JsonObject(base.toMutableMap().apply {
            put("streamingData", JsonObject(base["streamingData"]!!.jsonObject.toMutableMap().apply {
                put("hlsManifestUrl", JsonPrimitive("https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/2000/file/index.m3u8"))
            }))
        })
        val resolved = select(data, CapsuleNewPipeQuality.P480)!!
        assertEquals(CapsuleVideoDelivery.HLS, resolved.delivery)
        assertEquals(480, resolved.maxHeight)
        assertEquals(2_000_000L, resolved.expiresAtMs)
        assertNull(resolved.audioUrl)
        // A requested muxed progressive recovery must not repeat failed HLS.
        assertNull(select(data, CapsuleNewPipeQuality.P480, muxedOnly = true))
    }
    @Test fun hlsRejectsExpiredUntrustedOrUnsolvedManifestUrls() {
        val prefix = "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/"
        assertNull(selectDirectHlsManifest(prefix + "1001/file/index.m3u8", "abcdefghijk", CapsuleNewPipeQuality.AUTO, now))
        assertNull(selectDirectHlsManifest("https://evil.test/api/manifest/hls_playlist/expire/2000/file/index.m3u8", "abcdefghijk", CapsuleNewPipeQuality.AUTO, now))
        assertNull(selectDirectHlsManifest(prefix + "2000/file/index.m3u8?n=unsolved", "abcdefghijk", CapsuleNewPipeQuality.AUTO, now))
        assertNull(selectDirectHlsManifest(prefix + "2000/file/index.m3u8?range=0-1", "abcdefghijk", CapsuleNewPipeQuality.AUTO, now))
    }
    @Test fun autoKeepsSegmentedHdRatherThanSilentlyDroppingToMuxed360() {
        val base = response(listOf(format(360, 18, true)))
        val data = JsonObject(base.toMutableMap().apply {
            put("streamingData", JsonObject(base["streamingData"]!!.jsonObject.toMutableMap().apply {
                put("hlsManifestUrl", JsonPrimitive("https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/2000/file/index.m3u8"))
            }))
        })
        assertEquals(CapsuleVideoDelivery.HLS, select(data)!!.delivery)
        assertEquals(CapsuleVideoDelivery.HLS, select(data, CapsuleNewPipeQuality.P720)!!.delivery)
    }
    @Test fun reelRequiresExactIdAndAMuxedSoundtrackWithoutCipherOrOversizedFormats() {
        fun reel(player: JsonObject) = buildJsonObject { put("playerResponse", player) }
        fun choose(player: JsonObject) = selectAndroidReelVideo(reel(player), "abcdefghijk", CapsuleNewPipeQuality.AUTO, now)
        val valid = choose(response(listOf(format(360, 18, true))))!!
        assertEquals(18, valid.videoItag)
        assertNull(valid.audioUrl)
        assertNull(choose(response(listOf(format(360, 18, true)), id = "differentID")))
        assertNull(choose(JsonObject(response(listOf(format(360, 18, true))).toMutableMap().apply { remove("videoDetails") })))
        assertNull(choose(response(emptyList(), listOf(format(720, 136), format(0, 140)))))
        assertNull(choose(response(listOf(format(1080, 37, true)))))
        assertNull(choose(response(listOf(format(360, 18, true, "&n=unsolved")))))
    }
}
