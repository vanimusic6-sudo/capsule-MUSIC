package com.nikhil.yt.db

import org.junit.Assert.*
import org.junit.Test

class PlaylistMergeTest {
    @Test fun disjointSongsAndIntentionalRepeatedOccurrencesArePreserved() {
        val existing = listOf("a", "b", "a")
        val incoming = listOf("c", "a", "a", "a", "c", "b")
        val merged = existing + missingPlaylistOccurrences(existing, incoming).map(incoming::get)
        assertEquals(listOf("a", "b", "a", "c", "a", "c"), merged)
        assertTrue(missingPlaylistOccurrences(merged, incoming).isEmpty())
    }

    @Test fun identicalCopiesDoNotMultiplySongs() {
        assertTrue(missingPlaylistOccurrences(listOf("a", "a"), listOf("a", "a")).isEmpty())
    }

    @Test fun historicLibraryMembershipAndRemotePlaylistIdentityHaveExplicitMappings() {
        assertEquals("`createDate`", legacyLibraryValue("song", "inLibrary", setOf("id", "createDate")))
        assertEquals("`create_date`", legacyLibraryValue("song", "inLibrary", setOf("create_date")))
        assertEquals("`lastUpdateTime`", legacyLibraryValue("album", "bookmarkedAt", setOf("lastUpdateTime")))
        assertNotNull(legacyLibraryValue("playlist", "browseId", setOf("id")))
        assertNull(legacyLibraryValue("song", "inLibrary", setOf("id", "title")))
    }
}
