package com.nikhil.yt.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedSourceTest {
    @Test fun userCanChooseIndependentSources() {
        val chosen = linkedSetOf(HomeFeedSource.AUDIUS, HomeFeedSource.WIKIMEDIA)
        assertEquals(chosen, HomeFeedSource.parse(HomeFeedSource.encode(chosen)))
        assertFalse(HomeFeedSource.YOUTUBE in chosen)
    }

    @Test fun defaultsPreserveExistingYoutubeHome() {
        val sources = HomeFeedSource.parse(HomeFeedSource.DEFAULT)
        assertTrue(HomeFeedSource.YOUTUBE in sources)
        assertEquals(HomeFeedSource.entries.toSet(), sources)
    }

    @Test fun unrecognizedOrEmptyPreferenceKeepsHomeAccessible() {
        assertEquals(setOf(HomeFeedSource.YOUTUBE), HomeFeedSource.parse("unknown"))
        assertEquals(setOf(HomeFeedSource.YOUTUBE), HomeFeedSource.parse(""))
    }
}
