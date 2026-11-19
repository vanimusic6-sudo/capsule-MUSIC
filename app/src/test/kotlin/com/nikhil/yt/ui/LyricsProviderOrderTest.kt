package com.nikhil.yt.ui

import com.nikhil.yt.constants.LyricsProviderOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsProviderOrderTest {
    private val five = listOf(
        LyricsProviderOrder.BETTER_LYRICS_PORTATO,
        LyricsProviderOrder.SIMPMUSIC,
        LyricsProviderOrder.LYRICS_PLUS,
        LyricsProviderOrder.LRCLIB,
        LyricsProviderOrder.YOUTUBE,
    )

    @Test fun exactlyFiveProvidersAndYouTubeFallbackLast() {
        assertEquals(five, LyricsProviderOrder.supportedProviders)
        assertEquals(five, LyricsProviderOrder.resolve(null))
        assertEquals(five, LyricsProviderOrder.resolve(""))
        assertEquals(5, five.distinct().size)
    }

    @Test fun defaultsFromSevenSourceBuildMigrateWithoutOldCatalogs() {
        val old = "SIMPMUSIC,PAXSENIX_APPLE_MUSIC,BETTER_LYRICS_PORTATO," +
            "LYRICS_PLUS,LRCLIB,KUGOU,YOUTUBE"
        assertEquals(five, LyricsProviderOrder.resolve(old))
        assertEquals(five, LyricsProviderOrder.resolve(
            "PAXSENIX_APPLE_MUSIC,BETTER_LYRICS,NETEASE,LYRICS_PLUS,YOUTUBE",
        ))
        assertEquals(five, LyricsProviderOrder.resolve(
            "SIMPMUSIC,PAXSENIX_APPLE_MUSIC,BETTER_LYRICS_PORTATO,BETTER_LYRICS," +
                "LYRICS_PLUS,NETEASE,YOUTUBE",
        ))
    }

    @Test fun removedCatalogsNeverReachManualOrAutomaticSearch() {
        val old = "PAXSENIX,NETEASE,KUGOU,BETTER_LYRICS,YOUTUBE_SUBTITLE,DEEZER"
        assertEquals(five, LyricsProviderOrder.resolve(old))
        assertFalse(LyricsProviderOrder.supportedProviders.any {
            it.contains("PAXSENIX") || it.contains("KUGOU") || it.contains("NETEASE")
        })
    }

    @Test fun customOrderKeepsRemainingSupportedChoices() {
        val chosen = LyricsProviderOrder.resolve(
            " simpmusic, PAXSENIX_APPLE_MUSIC, lrcLib, KUGOU, LRCLIB, BETTER_LYRICS_PORTATO",
        )
        assertEquals(listOf(
            LyricsProviderOrder.SIMPMUSIC,
            LyricsProviderOrder.LRCLIB,
            LyricsProviderOrder.BETTER_LYRICS_PORTATO,
        ), chosen.take(3))
        assertEquals(five.toSet(), chosen.toSet())
    }

    @Test fun staleImplicitLrcLibDefaultDoesNotOverrideCapsule() {
        assertEquals(five, LyricsProviderOrder.resolve(null, "LRCLIB"))
        assertEquals(LyricsProviderOrder.LRCLIB,
            LyricsProviderOrder.resolve("LRCLIB,SIMPMUSIC").first())
        assertEquals(five, LyricsProviderOrder.resolve(null, "PAXSENIX_APPLE_MUSIC"))
    }

    @Test fun encodingRoundTripsCustomFiveSourceOrder() {
        val custom = five.reversed()
        assertEquals(custom, LyricsProviderOrder.resolve(LyricsProviderOrder.encode(custom)))
    }
}
