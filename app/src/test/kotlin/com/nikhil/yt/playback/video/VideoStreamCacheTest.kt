package com.nikhil.yt.playback.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoStreamCacheTest {
    @Test fun accountOrNetworkChangeRetiresCachedAndInFlightLinks() {
        var context = "first"
        val cache = VideoStreamCache<String>(2, { context }) { true }
        val generation = cache.generation()
        org.junit.Assert.assertTrue(cache.put("a", "key", "old", generation))
        context = "second"
        org.junit.Assert.assertNull(cache.latest("a"))
        org.junit.Assert.assertFalse(cache.put("a", "key", "late", generation))
        org.junit.Assert.assertNull(cache["key"])
    }

    @Test fun reusedKeyNeverLeaksAnotherVideosStream() {
        val cache = VideoStreamCache<Int>(3) { true }
        cache.put("track-one", "shared", 101)
        cache.put("track-two", "shared", 202)
        assertNull("Old video must not resolve another video's stream", cache.latest("track-one"))
        assertEquals(202, cache.latest("track-two"))
        assertEquals(202, cache["shared"])
    }

    @Test fun evictsTheLeastRecentlyUsedEntryAndItsIndex() {
        val cache = VideoStreamCache<Int>(2) { true }
        cache.put("a", "a:hd", 1)
        cache.put("b", "b:hd", 2)
        assertEquals(1, cache["a:hd"])
        cache.put("c", "c:hd", 3)
        assertNull(cache["b:hd"])
        assertNull(cache.latest("b"))
        assertEquals(1, cache.latest("a"))
        assertEquals(3, cache.latest("c"))
    }

    @Test fun cachedVideoQualityHitUpdatesSelectedFormat() {
        val cache = VideoStreamCache<Int>(3) { true }
        cache.put("v", "v:360", 360)
        cache.put("v", "v:720", 720)
        assertEquals(360, cache["v:360"])
        assertEquals(360, cache.latest("v"))
        assertEquals(720, cache["v:720"])
        assertEquals(720, cache.latest("v"))
    }

    @Test fun expiredLatestQualityFallsBackToAnotherStillFreshStream() {
        var deadline = 0
        val cache = VideoStreamCache<Int>(3) { it > deadline }
        cache.put("clip", "clip:720", 100)
        cache.put("clip", "clip:360", 50)
        assertEquals(50, cache.latest("clip"))

        deadline = 60
        assertEquals(100, cache.latest("clip"))
        assertNull(cache["clip:360"])
        assertEquals(100, cache.latest("clip"))
        deadline = 120
        assertNull(cache.latest("clip"))
    }

    @Test fun invalidationRemovesEveryQualityForOneVideo() {
        val cache = VideoStreamCache<Int>(4) { true }
        cache.put("a", "a:hd", 1)
        cache.put("a", "a:sd", 2)
        cache.put("b", "b:hd", 3)
        cache.invalidate("a")
        assertNull(cache["a:hd"])
        assertNull(cache["a:sd"])
        assertNull(cache.latest("a"))
        assertEquals(3, cache.latest("b"))
    }

    @Test fun expiryDoesNotLeaveAStaleLatestPointer() {
        var deadline = 0
        val cache = VideoStreamCache<Int>(2) { it > deadline }
        cache.put("a", "a:old", 1)
        cache.put("a", "a:new", 3)
        deadline = 1
        assertNull(cache["a:old"])
        assertEquals(3, cache.latest("a"))
        deadline = 4
        cache.put("b", "b:new", 5)
        assertNull(cache.latest("a"))
        assertEquals(5, cache.latest("b"))
    }
}
