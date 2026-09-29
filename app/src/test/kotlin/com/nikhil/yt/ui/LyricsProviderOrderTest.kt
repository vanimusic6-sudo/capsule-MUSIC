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
        val stored = "YOUTUBE,BETTER_LYRICS,DEEZER"
        val resolved = LyricsProviderOrder.resolve(stored)
        assertEquals(listOf("YOUTUBE", "BETTER_LYRICS", "DEEZER"), resolved.take(3))
    }

    /**
     * Plain YouTube metadata has to stay last: it nearly always returns *something*, so anything
     * below it would effectively never be reached.
     */
    @Test fun theFallbackIsLastByDefault() {
        val defaults = LyricsProviderOrder.supportedProviders
        assertEquals("YOUTUBE", defaults.last())
    }

    @Test fun theNewProvidersAreReachable() {
        val defaults = LyricsProviderOrder.supportedProviders
        assertTrue("LyricsPlus must be in the order", "LYRICS_PLUS" in defaults)
        assertTrue("Paxsenix Apple Music must be in the order", "PAXSENIX_APPLE_MUSIC" in defaults)
        assertTrue("Deezer must be in the order", "DEEZER" in defaults)
    }

    /** Removed providers must not survive in anybody's stored order after an upgrade. */
    @Test fun providersThatNoLongerExistAreDropped() {
        val resolved =
            LyricsProviderOrder.resolve(
                "SIMPMUSIC,KUGOU,LRCLIB,YOUTUBE_SUBTITLE,MUSIXMATCH,PAXSENIX_SPOTIFY,BETTER_LYRICS",
            )
        assertTrue("a removed provider came back: $resolved", "KUGOU" !in resolved)
        assertTrue("a removed provider came back: $resolved", "SIMPMUSIC" !in resolved)
        assertTrue("a removed provider came back: $resolved", "LRCLIB" !in resolved)
        assertTrue("a removed provider came back: $resolved", "YOUTUBE_SUBTITLE" !in resolved)
        assertTrue("a removed provider came back: $resolved", "MUSIXMATCH" !in resolved)
        assertTrue("a removed provider came back: $resolved", "PAXSENIX_SPOTIFY" !in resolved)
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
        val resolved =
            LyricsProviderOrder.resolve(
                " paxsenix_apple_music , PAXSENIX_APPLE_MUSIC,better_lyrics ",
            )
        assertEquals(
            listOf("PAXSENIX_APPLE_MUSIC", "BETTER_LYRICS"),
            resolved.take(2),
        )
    }

    @Test fun legacyPaxsenixExpandsInPlace() {
        val resolved = LyricsProviderOrder.resolve("YOUTUBE,PAXSENIX,BETTER_LYRICS")
        assertEquals(
            listOf(
                "YOUTUBE",
                "PAXSENIX_APPLE_MUSIC",
                "BETTER_LYRICS",
            ),
            resolved.take(3),
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
        val order = LyricsProviderOrder.supportedProviders.reversed()
        assertEquals(order, LyricsProviderOrder.resolve(LyricsProviderOrder.encode(order)))
    }
}
