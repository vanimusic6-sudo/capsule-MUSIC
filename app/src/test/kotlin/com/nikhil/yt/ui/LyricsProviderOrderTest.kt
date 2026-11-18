package com.nikhil.yt.ui

import com.nikhil.yt.constants.LyricsProviderOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsProviderOrderTest {
    @Test fun noPreferenceUsesIndependentSourceDefaults() {
        val default = LyricsProviderOrder.supportedProviders
        assertEquals(default, LyricsProviderOrder.resolve(null))
        assertEquals(default, LyricsProviderOrder.resolve(""))
        assertEquals(LyricsProviderOrder.YOUTUBE, default.last())
        assertEquals(default.size, default.distinct().size)
    }

    @Test fun theOldLrcLibDefaultCannotHijackCapsulesNewSourceOrder() {
        // Old Velune installs had LRCLIB as a default single-provider preference.
        // This is not the same as a deliberate custom order.
        assertEquals(
            LyricsProviderOrder.supportedProviders,
            LyricsProviderOrder.resolve(raw = null, legacyPreferred = "LRCLIB"),
        )
        assertEquals(
            LyricsProviderOrder.LRCLIB,
            LyricsProviderOrder.resolve(raw = "LRCLIB,SIMPMUSIC").first(),
        )
    }

    @Test fun olderUntouchedPreferencesMigrateToNewDefaults() {
        assertEquals(
            LyricsProviderOrder.supportedProviders,
            LyricsProviderOrder.resolve("PAXSENIX_APPLE_MUSIC,BETTER_LYRICS,NETEASE,LYRICS_PLUS,YOUTUBE"),
        )
        assertEquals(
            LyricsProviderOrder.supportedProviders,
            LyricsProviderOrder.resolve(
                "SIMPMUSIC,PAXSENIX_APPLE_MUSIC,BETTER_LYRICS_PORTATO,BETTER_LYRICS,LYRICS_PLUS,NETEASE,YOUTUBE",
            ),
        )
    }

    @Test fun preservesCustomOrderEvenWhenRemovedSourcesWereBetween() {
        assertEquals(
            listOf(LyricsProviderOrder.YOUTUBE, LyricsProviderOrder.PAXSENIX_APPLE_MUSIC),
            LyricsProviderOrder.resolve("YOUTUBE,NETEASE,PAXSENIX_APPLE_MUSIC").take(2),
        )
    }

    @Test fun containsNewAndIndependentSources() {
        val defaults = LyricsProviderOrder.supportedProviders
        assertTrue(LyricsProviderOrder.LRCLIB in defaults)
        assertTrue(LyricsProviderOrder.KUGOU in defaults)
        assertTrue(LyricsProviderOrder.SIMPMUSIC in defaults)
        assertTrue(LyricsProviderOrder.BETTER_LYRICS_PORTATO in defaults)
        assertTrue(LyricsProviderOrder.LYRICS_PLUS in defaults)
    }

    @Test fun obsoleteCatalogsDoNotReturn() {
        val resolved = LyricsProviderOrder.resolve(
            "NETEASE,BETTER_LYRICS,YOUTUBE_SUBTITLE,MUSIXMATCH,DEEZER,PAXSENIX_SPOTIFY",
        )
        assertEquals(LyricsProviderOrder.supportedProviders, resolved)
        assertTrue("NETEASE" !in resolved)
        assertTrue("BETTER_LYRICS" !in resolved)
    }

    @Test fun duplicatesCaseAndLegacyAliasRemainSafe() {
        val resolved = LyricsProviderOrder.resolve(
            " paxsenix, PAXSENIX_APPLE_MUSIC,lrclib,LRCLIB, SIMPMUSIC ",
        )
        assertEquals(
            listOf(
                LyricsProviderOrder.PAXSENIX_APPLE_MUSIC,
                LyricsProviderOrder.LRCLIB,
                LyricsProviderOrder.SIMPMUSIC,
            ),
            resolved.take(3),
        )
    }

    @Test fun unsupportedLegacyPreferenceDoesNotBecomeFirst() {
        assertEquals(
            LyricsProviderOrder.supportedProviders,
            LyricsProviderOrder.resolve(null, legacyPreferred = "NETEASE"),
        )
        assertEquals(
            LyricsProviderOrder.YOUTUBE,
            LyricsProviderOrder.resolve("YOUTUBE", legacyPreferred = "PAXSENIX_APPLE_MUSIC").first(),
        )
    }

    @Test fun allSourcesAreReachableFromPartialRankings() {
        val ranked = LyricsProviderOrder.resolve("LRCLIB,YOUTUBE")
        assertEquals(LyricsProviderOrder.LRCLIB, ranked.first())
        assertEquals(LyricsProviderOrder.supportedProviders.toSet(), ranked.toSet())
    }

    @Test fun encodeRoundTrip() {
        val reversed = LyricsProviderOrder.supportedProviders.reversed()
        assertEquals(reversed, LyricsProviderOrder.resolve(LyricsProviderOrder.encode(reversed)))
    }
}
