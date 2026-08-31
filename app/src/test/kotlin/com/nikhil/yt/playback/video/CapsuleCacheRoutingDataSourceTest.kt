package com.nikhil.yt.playback.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleCacheRoutingDataSourceTest {
    @Test
    fun videoKeysRouteToVideoCache() {
        assertTrue(
            CapsuleCacheRoutingDataSource.isCapsuleVideoKey(
                key = "${CAPSULE_VIDEO_CACHE_PREFIX}abc",
                uriScheme = "https",
            ),
        )
        assertTrue(
            CapsuleCacheRoutingDataSource.isCapsuleVideoKey(
                key = "${CAPSULE_VIDEO_STREAM_CACHE_PREFIX}video:abc:1",
                uriScheme = "https",
            ),
        )
    }

    @Test
    fun normalAudioStaysInAudioCache() {
        assertFalse(
            CapsuleCacheRoutingDataSource.isCapsuleVideoKey(
                key = "dQw4w9WgXcQ",
                uriScheme = "https",
            ),
        )
    }

    @Test
    fun capsuleVideoSchemeRoutesWithoutKey() {
        assertTrue(
            CapsuleCacheRoutingDataSource.isCapsuleVideoKey(
                key = null,
                uriScheme = CAPSULE_VIDEO_SCHEME,
            ),
        )
    }
}
