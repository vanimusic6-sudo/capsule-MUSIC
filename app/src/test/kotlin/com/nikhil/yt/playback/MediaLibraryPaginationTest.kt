package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test

/** All Media3 browse arguments are controller-supplied, not safe Int arithmetic. */
class MediaLibraryPaginationTest {
    @Test fun negativeZeroAndHugePagesNeverAllocateOrWrapAround() {
        assertNull(mediaBrowserOffset(-1, 100))
        assertNull(mediaBrowserOffset(0, 0))
        assertNull(mediaBrowserOffset(0, -1))
        assertNull(mediaBrowserOffset(0, MEDIA_BROWSER_MAX_PAGE_SIZE + 1))
        assertNull(mediaBrowserOffset(Int.MAX_VALUE, 200))
        assertEquals(0, mediaBrowserOffset(0, 100))
        assertEquals(100, mediaBrowserOffset(1, 100))
    }

    @Test fun childrenUseRequestedPageRatherThanReturningTheWholeLibrary() {
        val library = (0 until 620).toList()
        assertEquals((0 until 200).toList(), mediaBrowserPage(library, 0, 200))
        assertEquals((200 until 400).toList(), mediaBrowserPage(library, 1, 200))
        assertEquals((600 until 620).toList(), mediaBrowserPage(library, 3, 200))
        assertTrue(mediaBrowserPage(library, 4, 200).isEmpty())
        assertTrue(mediaBrowserPage(library, Int.MAX_VALUE, 200).isEmpty())
    }

    @Test fun pageBoundariesNeverOverflowNearIntegerLimit() {
        assertEquals(2147483600, mediaBrowserOffset(10737418, 200))
        assertTrue(mediaBrowserPage(listOf("a", "b"), 10737418, 200).isEmpty())
    }
}
