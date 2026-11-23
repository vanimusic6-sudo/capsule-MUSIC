package com.nikhil.yt.ui

import com.nikhil.yt.constants.LyricsProviderOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LyricsProviderOrderTest {
    private val seven = listOf(
        LyricsProviderOrder.BETTER_LYRICS_PORTATO,
        LyricsProviderOrder.BETTER_LYRICS,
        LyricsProviderOrder.LRCLIB,
        LyricsProviderOrder.YOUTUBE_SYNCED,
        LyricsProviderOrder.KUGOU,
        LyricsProviderOrder.BINIMUM,
        LyricsProviderOrder.YOUTUBE,
    )

    @Test fun exactlySevenProvidersInRequestedPriority() {
        assertEquals(seven, LyricsProviderOrder.supportedProviders)
        assertEquals(seven, LyricsProviderOrder.resolve(null))
        assertEquals(seven, LyricsProviderOrder.resolve(""))
        assertEquals(7, seven.distinct().size)
    }

    @Test fun oldFiveAndSevenSourceSettingsMigrate() {
        val five = "BETTER_LYRICS_PORTATO,SIMPMUSIC,LYRICS_PLUS,LRCLIB,YOUTUBE"
        val oldSeven = "SIMPMUSIC,PAXSENIX_APPLE_MUSIC,BETTER_LYRICS_PORTATO," +
            "LYRICS_PLUS,LRCLIB,KUGOU,YOUTUBE"
        assertEquals(seven, LyricsProviderOrder.resolve(five))
        assertEquals(seven, LyricsProviderOrder.resolve(oldSeven))
    }

    @Test fun retiredSourcesAreAbsentAndPlainYoutubeStaysLast() {
        assertFalse(seven.any { it in setOf("SIMPMUSIC", "LYRICS_PLUS", "NETEASE", "LYPRIO") })
        assertEquals(LyricsProviderOrder.YOUTUBE, seven.last())
    }

    @Test fun customSixSourceOrderSurvivesSerialization() {
        val custom = seven.reversed()
        assertEquals(custom, LyricsProviderOrder.resolve(LyricsProviderOrder.encode(custom)))
    }

    @Test fun implicitOldLrcLibDoesNotOverrideTheNewDefaults() {
        assertEquals(seven, LyricsProviderOrder.resolve(null, "LRCLIB"))
        assertEquals(seven, LyricsProviderOrder.resolve(null, "PAXSENIX_APPLE_MUSIC"))
    }
}
