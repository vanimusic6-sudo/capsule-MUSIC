package com.nikhil.yt.ui

import com.nikhil.yt.constants.LyricsProviderOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LyricsProviderOrderTest {
    private val six = listOf(
        LyricsProviderOrder.BETTER_LYRICS_PORTATO,
        LyricsProviderOrder.BETTER_LYRICS,
        LyricsProviderOrder.LRCLIB,
        LyricsProviderOrder.YOUTUBE_SYNCED,
        LyricsProviderOrder.LYPRIO,
        LyricsProviderOrder.YOUTUBE,
    )

    @Test fun exactlySixProvidersInRequestedPriority() {
        assertEquals(six, LyricsProviderOrder.supportedProviders)
        assertEquals(six, LyricsProviderOrder.resolve(null))
        assertEquals(six, LyricsProviderOrder.resolve(""))
        assertEquals(6, six.distinct().size)
    }

    @Test fun oldFiveAndSevenSourceSettingsMigrate() {
        val five = "BETTER_LYRICS_PORTATO,SIMPMUSIC,LYRICS_PLUS,LRCLIB,YOUTUBE"
        val seven = "SIMPMUSIC,PAXSENIX_APPLE_MUSIC,BETTER_LYRICS_PORTATO," +
            "LYRICS_PLUS,LRCLIB,KUGOU,YOUTUBE"
        assertEquals(six, LyricsProviderOrder.resolve(five))
        assertEquals(six, LyricsProviderOrder.resolve(seven))
    }

    @Test fun retiredSourcesAreAbsentAndPlainYoutubeStaysLast() {
        assertFalse(six.any { it in setOf("SIMPMUSIC", "LYRICS_PLUS", "NETEASE", "KUGOU") })
        assertEquals(LyricsProviderOrder.YOUTUBE, six.last())
    }

    @Test fun customSixSourceOrderSurvivesSerialization() {
        val custom = six.reversed()
        assertEquals(custom, LyricsProviderOrder.resolve(LyricsProviderOrder.encode(custom)))
    }

    @Test fun implicitOldLrcLibDoesNotOverrideTheNewDefaults() {
        assertEquals(six, LyricsProviderOrder.resolve(null, "LRCLIB"))
        assertEquals(six, LyricsProviderOrder.resolve(null, "PAXSENIX_APPLE_MUSIC"))
    }
}
