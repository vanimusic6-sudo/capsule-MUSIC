package com.nikhil.yt.playback.audio

import org.junit.Assert.*
import org.junit.Test

class TrackClientFailureCacheTest {
    @Test fun failedProfileDoesNotAffectAnotherTrackOrDownloadNamespace() {
        val playback = TrackClientFailureCache()
        val downloads = TrackClientFailureCache()
        playback.record("track-a", "WEB_REMIX", 100)
        assertEquals(setOf("WEB_REMIX"), playback.clients("track-a", 101))
        assertTrue(playback.clients("track-b", 101).isEmpty())
        assertTrue(downloads.clients("track-a", 101).isEmpty())
    }
    @Test fun readingDoesNotExtendTheFailureLifetime() {
        val cache = TrackClientFailureCache(ttlMs = 100)
        cache.record("a", "old", 100)
        assertEquals(setOf("old"), cache.clients("a", 199))
        assertTrue(cache.clients("a", 200).isEmpty())
    }
    @Test fun aNewFailureDoesNotResurrectExpiredProfiles() {
        val cache = TrackClientFailureCache(ttlMs = 100)
        cache.record("a", "old", 100)
        cache.record("a", "new", 201)
        assertEquals(setOf("new"), cache.clients("a", 202))
    }
    @Test fun boundedHistoryRetainsTheRecentlyUsedTrack() {
        val cache = TrackClientFailureCache(capacity = 2)
        cache.record("a", "first", 100)
        cache.record("b", "second", 101)
        cache.clients("a", 102)
        cache.record("c", "third", 103)
        assertEquals(setOf("first"), cache.clients("a", 104))
        assertTrue(cache.clients("b", 104).isEmpty())
        assertEquals(setOf("third"), cache.clients("c", 104))
    }
    @Test fun clearingOneTrackAndClockRollbackCannotLeakOldVerdicts() {
        val cache = TrackClientFailureCache()
        cache.record("a", "first", 100)
        cache.record("b", "second", 100)
        cache.remove("a")
        assertTrue(cache.clients("a", 101).isEmpty())
        assertTrue(cache.clients("b", 99).isEmpty())
        cache.record("c", "third", 102)
        cache.clear()
        assertTrue(cache.clients("c", 103).isEmpty())
    }
    @Test fun callersCannotMutateTheStoredClientSet() {
        val cache = TrackClientFailureCache()
        cache.record("a", "first", 100)
        cache.record("a", "second", 101)
        val snapshot = cache.clients("a", 102)
        if (snapshot is MutableSet<String>) runCatching { snapshot.add("foreign") }
        assertEquals(setOf("first", "second"), cache.clients("a", 103))
    }
}
