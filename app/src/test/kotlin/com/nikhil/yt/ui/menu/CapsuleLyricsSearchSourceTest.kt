package com.nikhil.yt.ui.menu

import com.nikhil.yt.constants.LyricsProviderOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleLyricsSearchSourceTest {
    @Test fun pickerOffersEveryCurrentProviderWithDistinctNames() {
        val titles = LyricsProviderOrder.supportedProviders.map(::lyricsSearchSourceTitle)
        assertEquals(7, titles.size)
        assertEquals(7, titles.distinct().size)
        assertTrue(titles.none { it.isBlank() || it == "Все источники" })
    }

    @Test fun allSourcesArePresentedAsAnExplicitChoice() {
        assertEquals("Все источники", lyricsSearchSourceTitle(null))
        assertTrue(lyricsSearchSourceDescription(null).contains("всех"))
    }

    @Test fun youtubeTimedAndUntimedSourcesStayDistinct() {
        assertEquals("YouTube Sync", lyricsSearchSourceTitle(LyricsProviderOrder.YOUTUBE_SYNCED))
        assertEquals("YouTube Plain", lyricsSearchSourceTitle(LyricsProviderOrder.YOUTUBE))
    }
}
