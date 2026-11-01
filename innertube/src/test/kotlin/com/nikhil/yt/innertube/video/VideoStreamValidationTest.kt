package com.nikhil.yt.innertube.video

import org.junit.Assert.*
import org.junit.Test

class VideoStreamValidationTest {
    @Test fun supportedCdnUrlsRetainOpaqueSignaturesWithoutNetworkLookups() {
        for (url in listOf(
            "https://rr5---sn-a5mekn6e.googlevideo.com/videoplayback?sig=a%2Fb%3D&n=opaque",
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/xyz",
            "https://redirector.googlevideo.com:443/videoplayback?expire=9999999999",
        )) assertTrue(url, VideoStreamValidation.isSupportedUrl(url))
    }
    @Test fun localAddressesNumericAliasesAndLookalikeHostsAreRejected() {
        for (url in listOf(
            "http://rr.googlevideo.com/video", "file:///private", "content://private/file",
            "https://localhost/video", "https://172.16.0.1/video", "https://192.168.1.1/video",
            "https://2130706433/video", "https://0x7f000001/video", "https://[::1]/video",
            "https://[fd00::1]/video", "https://[fe80::1]/video", "https://[::ffff:127.0.0.1]/video",
            "https://googlevideo.com.attacker.example/video", "https://badgooglevideo.com/video",
            "https://rr.googlevideo.com@attacker.example/video", "https://user@rr.googlevideo.com/video",
            "https://rr.googlevideo.com:8080/video", "https://rr.googlevideo.com/video#fragment",
            "https://rr..googlevideo.com/video", "https://rr.googlevideo.com/" + "a".repeat(17_000),
        )) assertFalse(url, VideoStreamValidation.isSupportedUrl(url))
    }
    private fun indexed() : CapsuleNewPipeResolved {
        val video = CapsuleDashTrack("https://rr.googlevideo.com/video?sig=a&itag=136", 136,
            "video/mp4", "avc1.4d401f", 1_000_000, 0, 99, 100, 199, 1280, 720, 30)
        val audio = video.copy(url = "https://rr.googlevideo.com/audio?itag=140", itag = 140,
            mimeType = "audio/mp4", codecs = "mp4a.40.2", width = 0, height = 0)
        return requireNotNull(indexedDashVideo("abcdefghijk", 180_000, listOf(video), listOf(audio),
            CapsuleNewPipeQuality.AUTO, Long.MAX_VALUE))
    }
    @Test fun generatedIndexedDashPassesTheSameContractAsEveryOtherRoute() {
        val result = indexed()
        assertTrue(VideoStreamValidation.accepts(result.videoUrl, result.audioUrl,
            result.dashManifest, result.userAgent, result.delivery))
    }
    @Test fun malformedExternalOrEntityBearingManifestsCannotReachMedia3() {
        val result = indexed()
        val valid = requireNotNull(result.dashManifest)
        val invalid = listOf(null, "broken", "<MPD/>",
            valid.replace("https://rr.googlevideo.com/video", "https://172.16.0.1/private"),
            valid.replace("<MPD", "<!DOCTYPE MPD [<!ENTITY remote SYSTEM 'https://localhost/private'>]><MPD"),
            valid.replace("<Period>", "<Period><Location>https://attacker.example/mpd</Location>"),
            valid.replace("<Representation ", "<Representation href='https://attacker.example/segment' "),
            "a".repeat(VideoStreamValidation.MAX_MANIFEST_LENGTH + 1),
        )
        for (manifest in invalid) assertFalse(VideoStreamValidation.accepts(result.videoUrl, result.audioUrl,
            manifest, result.userAgent, result.delivery))
    }
    @Test fun headerInjectionAndInsufficientUrlLifetimeAreRejected() {
        assertFalse(VideoStreamValidation.accepts("https://rr.googlevideo.com/video", null, null, "ua\r\nCookie: secret"))
        assertFalse(VideoStreamValidation.accepts("https://rr.googlevideo.com/video", null, null, "x".repeat(1025)))
        assertFalse(VideoStreamValidation.isFresh(45_100, 100))
        assertTrue(VideoStreamValidation.isFresh(45_101, 100))
        assertFalse(VideoStreamValidation.isFresh(Long.MIN_VALUE, 100))
    }
    @Test fun totalPacketSizeIsCheckedBeforeParsingAnOtherwiseBoundedManifest() {
        val result = indexed()
        val padded = requireNotNull(result.dashManifest).replace("<Period>", "<Period>" + " ".repeat(VideoStreamValidation.MAX_TOTAL_TEXT_LENGTH))
        assertTrue(padded.length < VideoStreamValidation.MAX_MANIFEST_LENGTH)
        assertFalse(VideoStreamValidation.accepts(result.videoUrl, result.audioUrl, padded, null, result.delivery))
        assertFalse(VideoStreamValidation.accepts(result.videoUrl, null, null, null,
            qualityLabel = "x".repeat(VideoStreamValidation.MAX_QUALITY_LABEL_LENGTH + 1)))
    }

}
