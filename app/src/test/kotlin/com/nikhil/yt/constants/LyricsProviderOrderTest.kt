package com.nikhil.yt.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LyricsProviderOrderTest {
    @Test fun sixProvidersUseRequestedOrder() {
        assertEquals(
            listOf("BETTER_LYRICS_PORTATO", "BETTER_LYRICS", "LRCLIB",
                "YOUTUBE_SYNCED", "LYPRIO", "YOUTUBE"),
            LyricsProviderOrder.resolve(null),
        )
    }

    @Test fun oldFiveSourcePriorityMigratesWithoutRetiredProviders() {
        assertEquals(
            LyricsProviderOrder.supportedProviders,
            LyricsProviderOrder.resolve("BETTER_LYRICS_PORTATO,SIMPMUSIC,LYRICS_PLUS,LRCLIB,YOUTUBE"),
        )
        assertFalse(LyricsProviderOrder.resolve("SIMPMUSIC,YOUTUBE").contains("SIMPMUSIC"))
    }

    @Test fun aReorderedNewSetSurvivesRoundTrip() {
        val requested = listOf("YOUTUBE_SYNCED", "LRCLIB", "BETTER_LYRICS", "YOUTUBE")
        val actual = LyricsProviderOrder.resolve(requested.joinToString(","))
        assertEquals(requested, actual.take(4))
        assertEquals(LyricsProviderOrder.supportedProviders.toSet(), actual.toSet())
        assertEquals(actual, LyricsProviderOrder.resolve(LyricsProviderOrder.encode(actual)))
    }

    @Test fun oldYoutubeIdStaysPlainAndSyncedGetsSeparateId() {
        assertEquals("YOUTUBE", LyricsProviderOrder.YOUTUBE)
        assertEquals("YOUTUBE_SYNCED", LyricsProviderOrder.YOUTUBE_SYNCED)
    }
}
