package com.nikhil.yt.playback.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleVideoIpcBoundaryTest {
    @Test fun acceptsNormalOfficialCdnUrls() {
        assertTrue(CapsuleVideoIpc.isSafeStreamUrl(
            "https://rr5---sn-abcdef.googlevideo.com/videoplayback?expire=123&sig=abc"
        ))
        assertTrue(CapsuleVideoIpc.isSafeStreamUrl(
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/xyz"
        ))
    }

    @Test fun rejectsFileContentHttpAndLocalUrls() {
        for (url in listOf(
            "file:///data/data/com.nikhil.yt/shared_prefs/settings.xml",
            "content://com.nikhil.yt.FileProvider/private",
            "http://example.org/video.mp4",
            "https://user:pass@video.example.com/file",
            "https://localhost/video.mp4",
            "https://127.0.0.1/video.mp4",
            "https://192.168.1.1/video.mp4",
            "https://video.example.com:8080/video.mp4",
            "https://example.org/" + "a".repeat(17_000)
        )) assertFalse(url, CapsuleVideoIpc.isSafeStreamUrl(url))
    }
}
