package com.nikhil.yt.playback.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoStreamCacheTest {
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
