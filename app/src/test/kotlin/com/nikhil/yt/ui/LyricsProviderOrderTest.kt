package com.nikhil.yt.ui

import com.nikhil.yt.constants.LyricsProviderOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lyrics provider ranking, and what it does with a list it does not recognise.
 *
 * A stored order outlives the version that wrote it: a provider gets removed, another gets added,
 * a backup comes back from an older build. Every one of those hands this a list that is wrong in
 * some way, and none of them may end with somebody having fewer sources than the app has.
 */
class LyricsProviderOrderTest {
    @Test fun nothingStoredGivesTheDefaultOrder() {
        assertEquals(LyricsProviderOrder.supportedProviders, LyricsProviderOrder.resolve(null))
        assertEquals(LyricsProviderOrder.supportedProviders, LyricsProviderOrder.resolve(""))
    }

    @Test fun aStoredOrderIsKept() {
        val stored = "YOUTUBE,BETTER_LYRICS,LRCLIB,YOUTUBE_SUBTITLE"
        assertEquals(
            listOf("YOUTUBE", "BETTER_LYRICS", "LRCLIB", "YOUTUBE_SUBTITLE"),
            LyricsProviderOrder.resolve(stored),
        )
    }

    /** SimpMusic and KuGou were removed; their ids must not survive in anybody's stored order. */
    @Test fun providersThatNoLongerExistAreDropped() {
        val resolved = LyricsProviderOrder.resolve("SIMPMUSIC,KUGOU,BETTER_LYRICS,LRCLIB")
        assertTrue("a removed provider came back: $resolved", "KUGOU" !in resolved)
        assertTrue("a removed provider came back: $resolved", "SIMPMUSIC" !in resolved)
        assertEquals("BETTER_LYRICS", resolved.first())
    }

    /** A provider added in a later version has to appear, or it can never be reached at all. */
    @Test fun aPartialOrderIsToppedUpRatherThanTruncated() {
        val resolved = LyricsProviderOrder.resolve("YOUTUBE")
        assertEquals("YOUTUBE", resolved.first())
        assertEquals(
            "every provider must be reachable",
            LyricsProviderOrder.supportedProviders.toSet(),
            resolved.toSet(),
        )
    }

    @Test fun duplicatesAndWhitespaceAndCaseAreTolerated() {
        assertEquals(
            listOf("LRCLIB", "BETTER_LYRICS", "YOUTUBE_SUBTITLE", "YOUTUBE"),
            LyricsProviderOrder.resolve(" lrclib , LRCLIB,better_lyrics "),
        )
    }

    /** Upgrading must not throw away the single choice somebody had already made. */
    @Test fun theOldPreferredProviderSeedsTheOrderOnce() {
        val resolved = LyricsProviderOrder.resolve(raw = null, legacyPreferred = "BETTER_LYRICS")
        assertEquals("BETTER_LYRICS", resolved.first())
        assertEquals(LyricsProviderOrder.supportedProviders.toSet(), resolved.toSet())
    }

    @Test fun aStoredOrderOutranksTheOldPreference() {
        val resolved = LyricsProviderOrder.resolve(raw = "YOUTUBE", legacyPreferred = "BETTER_LYRICS")
        assertEquals("YOUTUBE", resolved.first())
    }

    /** A removed provider as the legacy choice must not leave the order seeded with nothing. */
    @Test fun anOldPreferenceForARemovedProviderIsIgnored() {
        assertEquals(
            LyricsProviderOrder.supportedProviders,
            LyricsProviderOrder.resolve(raw = null, legacyPreferred = "KUGOU"),
        )
    }

    @Test fun encodingRoundTrips() {
        val order = listOf("YOUTUBE", "LRCLIB", "YOUTUBE_SUBTITLE", "BETTER_LYRICS")
        assertEquals(order, LyricsProviderOrder.resolve(LyricsProviderOrder.encode(order)))
    }
}
