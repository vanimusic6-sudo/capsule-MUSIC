package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.selectBestAudioFormat
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import org.junit.Assert.*
import org.junit.Test

class AacFormatTest {
    @Test fun normalTiersAcceptAacWhenItIsTheOnlyAudioFormat() {
        val aac = Format(itag = 140, url = "https://edge.googlevideo.com/aac", mimeType = "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 128000)
        for (quality in listOf(AudioQuality.AUTO, AudioQuality.HIGH, AudioQuality.LOW)) {
            assertEquals(140, selectBestAudioFormat(listOf(aac), quality)?.itag)
        }
    }

    @Test fun normalHighQualityChoosesAacOrOpusByQualityInsteadOfExcludingAac() {
        val aac = Format(itag = 140, url = "https://edge.googlevideo.com/aac", mimeType = "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 256000)
        val opus = Format(itag = 251, url = "https://edge.googlevideo.com/opus", mimeType = "audio/webm; codecs=\"opus\"", bitrate = 160000)
        assertEquals(140, selectBestAudioFormat(listOf(opus, aac), AudioQuality.HIGH)?.itag)
        assertEquals(251, selectBestAudioFormat(listOf(opus, aac.copy(bitrate = 128000)), AudioQuality.HIGH)?.itag)
    }

    @Test fun aacSelectionChoosesAudioMp4AndNeverReturnsOpusOrAVideo() {
        val aac = Format(itag = 140, url = "https://edge.googlevideo.com/aac", mimeType = "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 128000)
        val opus = Format(itag = 251, url = "https://edge.googlevideo.com/opus", mimeType = "audio/webm; codecs=\"opus\"", bitrate = 160000)
        val video = Format(itag = 18, url = "https://edge.googlevideo.com/video", mimeType = "video/mp4", bitrate = 1000000, width = 640)
        assertEquals(140, selectBestAudioFormat(listOf(opus, video, aac), AudioQuality.MP4)?.itag)
        assertNull(selectBestAudioFormat(listOf(opus, video), AudioQuality.MP4))
    }
}
