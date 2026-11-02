package com.nikhil.yt.playback.video.sabr

import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real HTTP POST -> UMP frames -> per-itag absolute-offset disk buffers.
 * The fake CDN is an OkHttp interceptor, not a mock of SabrVideoSession.
 */
class SabrVideoTransportTest {
    private fun umpVar(v: Int): ByteArray = when {
        v < 128 -> byteArrayOf(v.toByte())
        v < 16_384 -> byteArrayOf((0x80 or (v and 0x3f)).toByte(), (v / 64).toByte())
        else -> error("Fixture exceeds 2-byte UMP size")
    }

    private fun part(type: Int, payload: ByteArray): ByteArray =
        umpVar(type) + umpVar(payload.size) + payload

    private fun trackHeader(id: Int, itag: Int, length: Int): ByteArray = SabrProto.concat(
        SabrProto.vField(1, id.toLong()),
        SabrProto.vField(3, itag.toLong()),
        SabrProto.vField(6, 0L),
        SabrProto.vField(8, 0L),
        SabrProto.vField(9, 1L),
        SabrProto.vField(14, length.toLong()),
        SabrProto.bField(15, SabrProto.concat(
            SabrProto.vField(1, 0L), SabrProto.vField(2, 10L), SabrProto.vField(3, 1L),
        )),
    )

    private fun videoConfig(vLen: Long, aLen: Long) = SabrVideoConfig(
        sabrUrl = "https://rr1.googlevideo.com/videoplayback?expire=1999999999",
        ustreamerConfig = byteArrayOf(1, 2, 3, 4),
        videoFormat = SabrMessages.Format(136, 100L, vLen),
        audioFormat = SabrMessages.Format(140, 200L, aLen),
        poToken = byteArrayOf(),
        clientInfo = SabrMessages.ClientInfo(101, "1.2.0"),
        userAgent = "VisionOS",
        nTransform = { it },
        durationMs = 10_000L,
    )

    private fun buffer(bytes: Long, name: String) = SabrBuffer(
        bytes, File.createTempFile(name, ".part").apply { deleteOnExit() },
    )

    @Test fun fullDualTrackTransportPostsRequestAndReassemblesExactBytes() {
        val v = byteArrayOf(10, 11, 12, 13, 14, 15)
        val a = byteArrayOf(71, 72, 73)
        val payload = SabrProto.concat(
            part(SabrUmp.MEDIA_HEADER, trackHeader(1, 136, v.size)),
            part(SabrUmp.MEDIA_HEADER, trackHeader(2, 140, a.size)),
            part(SabrUmp.MEDIA, umpVar(1) + v),
            part(SabrUmp.MEDIA, umpVar(2) + a),
        )
        var posts = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            posts++
            assertEquals("POST", chain.request().method)
            assertTrue(chain.request().body!!.contentLength() > 16)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(payload.toResponseBody("application/x-protobuf".toMediaType()))
                .build()
        }.build()
        val video = buffer(v.size.toLong(), "sabrv")
        val audio = buffer(a.size.toLong(), "sabra")
        try {
            SabrVideoSession(videoConfig(v.size.toLong(), a.size.toLong()), http, video, audio).run()
            assertEquals(1, posts)
            assertTrue(video.completeFromZero())
            assertTrue(audio.completeFromZero())
            val outV = ByteArray(v.size)
            val outA = ByteArray(a.size)
            assertEquals(v.size, video.readCovered(0, outV, 0, outV.size))
            assertEquals(a.size, audio.readCovered(0, outA, 0, outA.size))
            assertArrayEquals(v, outV)
            assertArrayEquals(a, outA)
        } finally {
            video.release(true)
            audio.release(true)
        }
    }

    @Test fun http403IsTerminalAndDoesNotLoopOnSameSignedUrl() {
        var posts = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            posts++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(403).message("Forbidden")
                .body(byteArrayOf().toResponseBody())
                .build()
        }.build()
        val video = buffer(100L, "sabrv")
        val audio = buffer(50L, "sabra")
        try {
            SabrVideoSession(videoConfig(100L, 50L), http, video, audio).run()
            assertEquals(1, posts)
            assertTrue(video.failed())
            assertTrue(audio.failed())
        } finally {
            video.release(true)
            audio.release(true)
        }
    }
}
