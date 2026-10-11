@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import android.net.Uri
import androidx.media3.common.ParserException
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CapsuleHlsPlaylistParserFactoryTest {
    private fun master(vararg heights: Int) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n")
        append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Original\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio.m3u8\"\n")
        heights.forEach { height ->
            append("#EXT-X-STREAM-INF:BANDWIDTH=${height * 2000},RESOLUTION=1280x$height,CODECS=\"avc1.4d401f,mp4a.40.2\",AUDIO=\"a\"\n")
            append("video-$height.m3u8\n")
        }
    }
    private fun parse(text: String, cap: Int = 720) = CapsuleHlsPlaylistParserFactory(cap)
        .createPlaylistParser().parse(Uri.parse("https://manifest.googlevideo.com/master.m3u8"), text.byteInputStream())

    @Test fun dropsOversizedRenditionsBeforeLoadingAndPreservesOriginalAudio() {
        val playlist = parse(master(360, 720, 1080)) as HlsMultivariantPlaylist
        assertEquals(listOf(360, 720), playlist.variants.map { it.format.height })
        assertEquals(1, playlist.audios.size)
        assertTrue(playlist.audios.single().url.toString().endsWith("audio.m3u8"))
        val sd = parse(master(360, 720, 1080), 480) as HlsMultivariantPlaylist
        assertEquals(listOf(360), sd.variants.map { it.format.height })
    }
    @Test(expected = ParserException::class)
    fun cannotSilentlyExceedCapWhenOnly1080IsAvailable() { parse(master(1080)) }

    @Test(expected = ParserException::class)
    fun directMediaPlaylistWithoutVerifiableResolutionIsRejected() {
        parse("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nsegment.ts\n#EXT-X-ENDLIST\n")
    }
    @Test fun childMediaSegmentsRemainNativeHlsAfterMasterFiltering() {
        val factory = CapsuleHlsPlaylistParserFactory(720)
        val playlist = parse(master(360, 1080)) as HlsMultivariantPlaylist
        val child = factory.createPlaylistParser(playlist, null).parse(
            Uri.parse("https://manifest.googlevideo.com/video-360.m3u8"),
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nsegment.ts\n#EXT-X-ENDLIST\n".byteInputStream(),
        )
        assertTrue(child is androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist)
    }
}
