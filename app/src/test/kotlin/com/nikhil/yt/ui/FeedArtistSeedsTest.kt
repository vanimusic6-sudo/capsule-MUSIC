package com.nikhil.yt.ui

import com.nikhil.yt.db.entities.Artist
import com.nikhil.yt.db.entities.ArtistEntity
import com.nikhil.yt.viewmodels.feedArtistSeeds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The artists someone picks in the welcome flow have to reach the feed.
 *
 * They used to be subscribed and then change nothing: the "more like" rows were seeded from most
 * played alone, and on a fresh install nothing has been played, so the first feed knew nothing
 * about the choice that had just been made.
 */
class FeedArtistSeedsTest {
    private fun artist(id: String) =
        Artist(artist = ArtistEntity(id = id, name = id), songCount = 0)

    private fun ids(seeds: List<Artist>) = seeds.map { it.id }

    @Test fun aFreshInstallIsSeededEntirelyByWhoWasFollowed() {
        val seeds = feedArtistSeeds(played = emptyList(), followed = listOf(artist("a"), artist("b")), limit = 3)
        assertEquals(listOf("a", "b"), ids(seeds))
    }

    @Test fun listeningOutranksFollowing() {
        val seeds =
            feedArtistSeeds(
                played = listOf(artist("played1"), artist("played2"), artist("played3")),
                followed = listOf(artist("followed")),
                limit = 3,
            )
        assertEquals(listOf("played1", "played2", "played3"), ids(seeds))
    }

    @Test fun followingFillsWhateverListeningLeaves() {
        val seeds =
            feedArtistSeeds(
                played = listOf(artist("played")),
                followed = listOf(artist("followed1"), artist("followed2"), artist("followed3")),
                limit = 3,
            )
        assertEquals(listOf("played", "followed1", "followed2"), ids(seeds))
    }

    /** Otherwise one artist takes two of the three seeds and the feed gets narrower, not wider. */
    @Test fun anArtistBothPlayedAndFollowedCountsOnce() {
        val seeds =
            feedArtistSeeds(
                played = listOf(artist("both")),
                followed = listOf(artist("both"), artist("other")),
                limit = 3,
            )
        assertEquals(listOf("both", "other"), ids(seeds))
    }

    @Test fun nothingAnywhereSeedsNothing() {
        assertEquals(emptyList<String>(), ids(feedArtistSeeds(emptyList(), emptyList(), 3)))
        assertEquals(emptyList<String>(), ids(feedArtistSeeds(listOf(artist("a")), emptyList(), 0)))
    }
}
