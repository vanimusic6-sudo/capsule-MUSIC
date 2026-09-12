package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackCacheManagerTest {
    @Test
    fun disabledSmartTrimmerHasNoLimit() {
        assertNull(configuredPlayerCacheLimitBytes(enabled = false, maxSongCacheSizeMb = 1024))
    }

    @Test
    fun unlimitedAndInvalidSizesHaveNoLimit() {
        assertNull(configuredPlayerCacheLimitBytes(enabled = true, maxSongCacheSizeMb = -1))
        assertNull(configuredPlayerCacheLimitBytes(enabled = true, maxSongCacheSizeMb = 0))
    }

    @Test
    fun configuredMegabytesConvertToBytes() {
        assertEquals(
            1024L * 1024L * 1024L,
            configuredPlayerCacheLimitBytes(enabled = true, maxSongCacheSizeMb = 1024),
        )
    }
}
