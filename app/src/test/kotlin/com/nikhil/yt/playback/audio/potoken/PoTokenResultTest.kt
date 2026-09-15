package com.nikhil.yt.playback.audio.potoken

import org.junit.Assert.assertEquals
import org.junit.Test

class PoTokenResultTest {
    @Test
    fun botGuardFactoryKeepsPlayerAndGvsTokenScopes() {
        val result =
            PoTokenResult.fromBotGuard(
                videoIdPoToken = "video-id-token",
                visitorDataPoToken = "visitor-data-token",
            )

        assertEquals("video-id-token", result.playerRequestPoToken)
        assertEquals("visitor-data-token", result.streamingDataPoToken)
    }
}
