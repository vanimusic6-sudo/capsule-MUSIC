package com.nikhil.yt.ui

import com.nikhil.yt.lyrics.NetEaseSongMatch
import com.nikhil.yt.lyrics.rankNetEaseSongs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking the right recording out of a catalogue's answers.
 *
 * A title and an artist say nothing about which of six versions came back, so this is where wrong
 * lyrics get in: a remix or a live take matches the name perfectly and is a different song. The
 * real search for "Hurt" by Oliver Tree returns exactly that — the album cut, a single, and an
 * NGHTMRE remix, all with the right name and artist and only their length to tell them apart.
 */
class NetEaseMatchingTest {
    private data class Song(
        override val title: String,
        override val artist: String,
        override val durationMs: Long,
    ) : NetEaseSongMatch

    /** The three results the live search actually returned. */
    private val albumCut = Song("Hurt", "Oliver Tree", 145_147)
    private val single = Song("Hurt", "Oliver Tree", 145_558)
    private val remix = Song("Hurt (NGHTMRE Remix)", "Oliver Tree NGHTMRE", 226_206)

    @Test fun lengthDecidesBetweenIdenticallyNamedRecordings() {
        val ranked = rankNetEaseSongs(listOf(remix, single, albumCut), "Hurt", "Oliver Tree", 145)
        assertEquals(albumCut, ranked.first())
    }

    @Test fun aRemixNobodyAskedForIsNotOffered() {
        val ranked = rankNetEaseSongs(listOf(remix, albumCut), "Hurt", "Oliver Tree", 145)
        assertEquals(albumCut, ranked.first())
        // It is eighty seconds longer, so it is a different recording and is not a candidate at
        // all — which is better than being a candidate that happens to rank second.
        assertTrue("the remix must not be offered: $ranked", remix !in ranked)
    }

    @Test fun aRemixThatWasAskedForIsNotPenalised() {
        val ranked =
            rankNetEaseSongs(listOf(albumCut, remix), "Hurt (NGHTMRE Remix)", "Oliver Tree", 226)
        assertEquals(remix, ranked.first())
    }

    /** A length that is minutes out is a different recording whatever it is called. */
    @Test fun somethingOfTheWrongLengthIsDiscarded() {
        val wrong = Song("Hurt", "Oliver Tree", 600_000)
        val ranked = rankNetEaseSongs(listOf(wrong), "Hurt", "Oliver Tree", 145)
        assertTrue("a ten minute 'Hurt' is not this song: $ranked", ranked.isEmpty())
    }

    @Test fun aDifferentArtistWithTheSameTitleLosesToTheRightOne() {
        val impostor = Song("Hurt", "Johnny Cash", 218_000)
        val ranked = rankNetEaseSongs(listOf(impostor, albumCut), "Hurt", "Oliver Tree", 145)
        assertEquals(albumCut, ranked.first())
    }

    @Test fun nothingWorthTryingIsBetterThanTheWrongSong() {
        val unrelated = Song("Completely Different", "Someone Else", 300_000)
        assertTrue(rankNetEaseSongs(listOf(unrelated), "Hurt", "Oliver Tree", 145).isEmpty())
        assertTrue(rankNetEaseSongs(emptyList<Song>(), "Hurt", "Oliver Tree", 145).isEmpty())
    }

    /** Durations arrive in milliseconds and the request is in seconds; mixing them loses every match. */
    @Test fun secondsAreComparedAgainstMilliseconds() {
        val ranked = rankNetEaseSongs(listOf(albumCut), "Hurt", "Oliver Tree", 145)
        assertEquals(listOf(albumCut), ranked)
        val asIfSeconds = rankNetEaseSongs(listOf(albumCut), "Hurt", "Oliver Tree", 145_000)
        assertTrue("treating the request as milliseconds must not match", asIfSeconds.isEmpty())
    }
}
