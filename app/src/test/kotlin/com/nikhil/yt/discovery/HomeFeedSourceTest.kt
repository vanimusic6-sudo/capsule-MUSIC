package com.nikhil.yt.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedSourceTest {
    @Test fun homeLanesExposeRealSeparateCatalogRows() {
        val kinds = ExperimentalCatalog.HomeLaneKind.entries
        assertTrue(ExperimentalCatalog.HomeLaneKind.TRENDING in kinds)
        assertTrue(ExperimentalCatalog.HomeLaneKind.NEW in kinds)
        assertTrue(ExperimentalCatalog.HomeLaneKind.DISCOVER in kinds)
        assertTrue(ExperimentalCatalog.HomeLaneKind.FOR_YOU in kinds)
    }

    @Test fun userCanChooseIndependentSources() {
        val chosen = linkedSetOf(HomeFeedSource.AUDIUS, HomeFeedSource.WIKIMEDIA)
        assertEquals(chosen, HomeFeedSource.parse(HomeFeedSource.encode(chosen)))
        assertFalse(HomeFeedSource.YOUTUBE in chosen)
    }

    @Test fun defaultsPreserveExistingYoutubeHome() {
        val sources = HomeFeedSource.parse(HomeFeedSource.DEFAULT)
        assertTrue(HomeFeedSource.YOUTUBE in sources)
        assertEquals(setOf(HomeFeedSource.YOUTUBE), sources)
        assertTrue(HomeFeedSource.ARCHIVE in HomeFeedSource.entries)
    }

    @Test fun archiveCanBeSelectedWithoutChangingYoutubeAudio() {
        val chosen = setOf(HomeFeedSource.ARCHIVE)
        assertEquals(chosen, HomeFeedSource.parse(HomeFeedSource.encode(chosen)))
    }

    @Test fun unrecognizedOrEmptyPreferenceKeepsHomeAccessible() {
        assertEquals(setOf(HomeFeedSource.YOUTUBE), HomeFeedSource.parse("unknown"))
        assertEquals(setOf(HomeFeedSource.YOUTUBE), HomeFeedSource.parse(""))
    }
}
