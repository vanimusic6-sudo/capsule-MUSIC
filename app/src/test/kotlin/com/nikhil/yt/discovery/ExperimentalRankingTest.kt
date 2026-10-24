package com.nikhil.yt.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalRankingTest {
    private fun track(
        source: ExperimentalCatalog.Source,
        number: Int,
        artist: String = "Artist-$number",
        length: Int = 183,
    ) = ExperimentalCatalog.Track(
        source = source,
        id = "$number",
        title = "Song-$number",
        artist = artist,
        artwork = null,
        streamUrl = "https://example.org/song-$number.mp3",
        sourcePage = "https://example.org/song-$number",
        durationSeconds = length,
    )

    @Test fun recommendationOrderIsStableDuringDay() {
        val tracks = (0..60).map { i ->
            track(ExperimentalCatalog.Source.entries[i % 2], i)
        }
        assertEquals(
            ExperimentalRanking.select(tracks, dayKey = 42L),
            ExperimentalRanking.select(tracks, dayKey = 42L),
        )
    }

    @Test fun popularProviderCannotDrownOutOtherSources() {
        val all = (1..80).map { track(ExperimentalCatalog.Source.AUDIUS, it) } +
            (81..160).map { track(ExperimentalCatalog.Source.OPENVERSE, it) }
        val selection = ExperimentalRanking.select(all, dayKey = 67, maximum = 18)
        assertEquals(18, selection.size)
        assertTrue(selection.count { it.source == ExperimentalCatalog.Source.AUDIUS } in 1..11)
        assertTrue(selection.count { it.source == ExperimentalCatalog.Source.OPENVERSE } in 1..10)
    }

    @Test fun skipsInvalidAudioAndOverRepeatedArtists() {
        val tracks = listOf(
            track(ExperimentalCatalog.Source.AUDIUS, 1, "Same"),
            track(ExperimentalCatalog.Source.AUDIUS, 2, "Same"),
            track(ExperimentalCatalog.Source.AUDIUS, 3, "Same"),
            track(ExperimentalCatalog.Source.OPENVERSE, 4, "Other", length = 3600),
            track(ExperimentalCatalog.Source.OPENVERSE, 5, "Third"),
        )
        val result = ExperimentalRanking.select(tracks, dayKey = 99)
        assertEquals(3, result.size)
        assertEquals(2, result.count { it.artist == "Same" })
        assertFalse(result.any { it.id == "4" })
    }

    @Test fun picksPlayableMusicBeforeEffectsAndPreferTaste() {
        val familiar = track(ExperimentalCatalog.Source.AUDIUS, 1, artist = "KnownArtist")
        val effect = track(ExperimentalCatalog.Source.OPENVERSE, 2).copy(
            title = "Alarm clock sound effect", audioCategory = "sound_effect",
        )
        val other = track(ExperimentalCatalog.Source.AUDIUS, 3, artist = "NewArtist")
        val ranked = ExperimentalRanking.select(
            listOf(effect, other, familiar), dayKey = 9L, tasteArtists = listOf("KnownArtist"),
        )
        assertEquals("KnownArtist", ranked.first().artist)
        assertFalse(ranked.any { it.id == "2" })
    }

    @Test fun audiusEngagementActuallyInfluencesDiscoveryRanking() {
        val ordinary = track(ExperimentalCatalog.Source.AUDIUS, 1)
        val fanFavorite = track(ExperimentalCatalog.Source.AUDIUS, 2).copy(
            playCount = 25_000, favoriteCount = 240, repostCount = 32,
        )
        val ranked = ExperimentalRanking.select(listOf(ordinary, fanFavorite), dayKey = 21L)
        assertEquals(fanFavorite.mediaId, ranked.first().mediaId)
    }

    @Test fun unavailableRatingsAreNeverInventedForOpenverse() {
        val audio = track(ExperimentalCatalog.Source.OPENVERSE, 3)
        assertEquals(0L, audio.playCount)
        assertEquals(0L, audio.favoriteCount)
        assertEquals(0L, audio.repostCount)
    }

    @Test fun missingSourceDoesNotLeaveAnEmptyShelf() {
        val pool = (0..35).map { track(ExperimentalCatalog.Source.AUDIUS, it) }
        assertEquals(18, ExperimentalRanking.select(pool, dayKey = 7).size)
        assertTrue(ExperimentalRanking.select(pool, dayKey = 7, maximum = 0).isEmpty())
    }
}
