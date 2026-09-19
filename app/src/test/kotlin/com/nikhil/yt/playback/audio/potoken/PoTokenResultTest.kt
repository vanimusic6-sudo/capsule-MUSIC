package com.nikhil.yt.playback.audio.potoken

import org.junit.Assert.assertEquals
import org.junit.Test

class PoTokenResultTest {
    @Test
    fun botGuardFactoryMapsInnerTubeXBindingScopes() {
        val result =
            PoTokenResult.fromBotGuard(
                videoIdPoToken = "video-id-token",
                visitorDataPoToken = "visitor-data-token",
            )

        // InnerTubeX sends VISITOR_DATA-bound proof with /player and VIDEO_ID-bound
        // proof with the resulting GoogleVideo URL. Reversing these can still yield
        // streamingData from /player but makes GVS reject the signed URL with 403.
        assertEquals("visitor-data-token", result.playerRequestPoToken)
        assertEquals("video-id-token", result.streamingDataPoToken)
    }
}
