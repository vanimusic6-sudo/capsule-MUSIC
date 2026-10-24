package com.nikhil.yt.playback.audio

import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioDownloadUrlPolicyTest {
    @Test fun acceptsSignedHttpsMediaWithoutRewritingItsQuery() {
        AudioDownloadUrlPolicy.requireYouTubeMedia("https://rr1---sn-test.googlevideo.com/videoplayback?sig=sample&range=0-100")
    }
    @Test fun rejectsUntrustedHostsAndCredentialOrSchemeConfusion() {
        listOf("http://rr1.googlevideo.com/media", "file:///tmp/media", "https://googlevideo.com.evil.example/media",
            "https://googlevideo.com@evil.example/media", "https://user:password@rr1.googlevideo.com/media", "https://127.0.0.1/media")
            .forEach { address -> assertThrows(IOException::class.java) { AudioDownloadUrlPolicy.requireYouTubeMedia(address) } }
    }
    @Test fun externalDownloadsKeepPublisherHostsButRequireHttpsAtEveryRedirect() {
        AudioDownloadUrlPolicy.requireHttps("https://creator.example/audio.mp3")
        assertThrows(IOException::class.java) { AudioDownloadUrlPolicy.requireHttps("http://creator.example/audio.mp3") }
    }
}
