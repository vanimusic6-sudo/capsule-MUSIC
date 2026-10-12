@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import com.nikhil.yt.innertube.video.CapsuleVideoDelivery
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
class CapsuleVideoIndexedDashTest {
    private val url = "https://rr1.googlevideo.com/file?expire=9999999999&itag=136"
    private val audio = "https://rr1.googlevideo.com/file?expire=9999999999&itag=140"
    private val xml = """
      <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" minBufferTime="PT1S" mediaPresentationDuration="PT200S"><Period>
      <AdaptationSet id="1" contentType="video"><Representation id="136" mimeType="video/mp4" codecs="avc1.64001f" bandwidth="2000000" width="1280" height="720" frameRate="60">
      <BaseURL>${url.replace("&", "&amp;")}</BaseURL><SegmentBase indexRange="24-79"><Initialization range="0-23"/></SegmentBase></Representation></AdaptationSet>
      <AdaptationSet id="2" contentType="audio"><Representation id="140" mimeType="audio/mp4" codecs="mp4a.40.2" bandwidth="128000">
      <BaseURL>${audio.replace("&", "&amp;")}</BaseURL><SegmentBase indexRange="24-79"><Initialization range="0-23"/></SegmentBase></Representation></AdaptationSet>
      </Period></MPD>
    """.trimIndent()
    private val item = MediaItem.Builder().setMediaId("song").setUri("capsule-video://play/abcdefghijk").build()
    private fun resolved(manifest: String = xml) = YouTubeVideoResolver.ResolvedVideo("song", "abcdefghijk", url,
        YouTubeVideoResolver.StreamFormat(136, 1280, 720, "720p"), audio,
        YouTubeVideoResolver.StreamFormat(140, null, null, null), Long.MAX_VALUE,
        delivery = CapsuleVideoDelivery.DASH, dashManifest = manifest)

    @Test fun sideloadedDashConstructionDoesNotOpenAnyNetworkManifest() {
        val factory = CapsuleVideoDirectMediaSourceFactory(DataSource.Factory { error("Must not open transport to obtain local MPD") }) { null }
        assertTrue(factory.createMediaSource(item.buildUpon().setTag(resolved()).build()) is DashMediaSource)
    }
    @Test(expected = IllegalStateException::class)
    fun malformedIndexIsRejectedBeforeAnyMediaRequest() {
        CapsuleVideoDirectMediaSourceFactory(DataSource.Factory { error("Unexpected network") }) { null }
            .createMediaSource(item.buildUpon().setTag(resolved(xml.replace("24-79", "10-79"))).build())
    }
    @Test fun nativeSidxReadsOnlyTheSmallIndexAndLocatesTheMiddleSegment() {
        // ftyp + version-0 SIDX. Media3 reads 80 bytes, not the first 9 MB of video.
        val bytes = ByteBuffer.allocate(80).apply {
            putInt(24); putInt(0x66747970); putInt(0x69736f6d); putInt(0); putInt(0x69736f6d); putInt(0x69736f32)
            putInt(56); putInt(0x73696478); putInt(0); putInt(1); putInt(1000); putInt(0); putInt(0)
            putShort(0); putShort(2)
            putInt(9_000_000); putInt(100_000); putInt(0x90000000.toInt())
            putInt(8_000_000); putInt(100_000); putInt(0x90000000.toInt())
        }.array()
        val source = RecordingSource(bytes)
        val rep = DashManifestParser().parse(item.localConfiguration!!.uri, xml.byteInputStream()).getPeriod(0).adaptationSets[0].representations[0]
        val index = checkNotNull(DashUtil.loadChunkIndex(source, C.TRACK_TYPE_VIDEO, rep))
        val middle = index.getChunkIndex(150_000_000L)
        assertEquals(1, middle)
        assertEquals(9_000_080L, index.offsets[middle])
        assertTrue(source.opened.all { it.position + it.length <= 80L })
    }
    @Test fun rangeAndCacheIdentitySurviveSignedUrlRefreshAndRouteHeaders() {
        val source = RecordingSource(ByteArray(80))
        val factory = CapsuleVideoTransportFactory(DataSource.Factory { source }, "anonymous-video-UA", mapOf(url to "video:abcdefghijk:136"))
        val wrapper = factory.createDataSource()
        val request = DataSpec.Builder().setUri(url).setPosition(24).setLength(56).setHttpRequestHeaders(mapOf("X-Test" to "kept")).build()
        assertEquals(56L, wrapper.open(request)); wrapper.close()
        val actual = source.opened.single()
        assertEquals(24L, actual.position); assertEquals(56L, actual.length)
        assertEquals("video:abcdefghijk:136", actual.key)
        assertEquals("anonymous-video-UA", actual.httpRequestHeaders["User-Agent"])
        assertEquals("kept", actual.httpRequestHeaders["X-Test"])
    }
    private class RecordingSource(private val bytes: ByteArray) : DataSource {
        val opened = mutableListOf<DataSpec>()
        private var at = 0; private var end = 0
        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec; at = dataSpec.position.toInt()
            end = minOf(bytes.size, at + dataSpec.length.toInt())
            return (end - at).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at == end) return C.RESULT_END_OF_INPUT
            val n = minOf(length, end - at); bytes.copyInto(buffer, offset, at, at + n); at += n; return n
        }
        override fun getUri(): Uri = Uri.parse("https://rr1.googlevideo.com/file")
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun close() {}
    }
}
