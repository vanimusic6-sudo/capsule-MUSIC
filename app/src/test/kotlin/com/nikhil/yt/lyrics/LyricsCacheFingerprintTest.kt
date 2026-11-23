package com.nikhil.yt.lyrics

import org.junit.Assert.*
import org.junit.Test

class LyricsCacheFingerprintTest {
    @Test fun metadataBoundariesCannotAlias() {
        assertNotEquals(lyricsCacheFingerprint(listOf("ab", "c")),
            lyricsCacheFingerprint(listOf("a", "bc")))
    }

    @Test fun priorityAndPreferencesInvalidateCachedFallback() {
        val sourceOrder = listOf("track", "artist", "album", "180", "false", "QQ", "KuGou")
        val changedOrder = listOf("track", "artist", "album", "180", "false", "KuGou", "QQ")
        val changedMode = listOf("track", "artist", "album", "180", "true", "QQ", "KuGou")
        assertNotEquals(lyricsCacheFingerprint(sourceOrder), lyricsCacheFingerprint(changedOrder))
        assertNotEquals(lyricsCacheFingerprint(sourceOrder), lyricsCacheFingerprint(changedMode))
        assertEquals(lyricsCacheFingerprint(sourceOrder), lyricsCacheFingerprint(sourceOrder.toList()))
    }
}
