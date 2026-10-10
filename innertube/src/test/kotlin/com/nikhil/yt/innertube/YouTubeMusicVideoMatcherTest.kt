package com.nikhil.yt.innertube

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeMusicVideoMatcherTest {
    @Test
    fun strongOfficialVideoPasses() {
        val score =
            YouTubeMusicVideoMatcher.scoreCandidate(
                sourceTitle = "Blinding Lights",
                sourceTitleNorm = YouTubeMusicVideoMatcher.normalizeTitle("Blinding Lights"),
                sourceArtistNorms = listOf(YouTubeMusicVideoMatcher.normalizeText("The Weeknd")),
                candidateTitle = "The Weeknd - Blinding Lights (Official Video)",
                secondaryText = "The Weeknd • 4:21",
                sourceDurationSeconds = 261,
            )

        assertTrue(requireNotNull(score) >= YouTubeMusicVideoMatcher.STRONG_MATCH_SCORE)
    }

    @Test
    fun liveVariantIsRejectedWhenSourceIsNotLive() {
        assertNull(
            YouTubeMusicVideoMatcher.scoreCandidate(
                sourceTitle = "Song",
                sourceTitleNorm = YouTubeMusicVideoMatcher.normalizeTitle("Song"),
                sourceArtistNorms = listOf("artist"),
                candidateTitle = "Artist - Song (Live)",
                secondaryText = "Artist • 3:30",
                sourceDurationSeconds = 210,
            ),
        )
    }

    @Test
    fun nightcoreVariantIsRejected() {
        assertNull(
            YouTubeMusicVideoMatcher.scoreCandidate(
                sourceTitle = "Song",
                sourceTitleNorm = YouTubeMusicVideoMatcher.normalizeTitle("Song"),
                sourceArtistNorms = listOf("artist"),
                candidateTitle = "Artist - Song Nightcore",
                secondaryText = "Artist • 3:30",
                sourceDurationSeconds = 210,
            ),
        )
    }

    @Test
    fun englishLyricsVideoIsNotAnOfficialClip() {
        assertNull(
            YouTubeMusicVideoMatcher.scoreCandidate(
                sourceTitle = "Blinding Lights",
                sourceTitleNorm = YouTubeMusicVideoMatcher.normalizeTitle("Blinding Lights"),
                sourceArtistNorms = listOf("the weeknd"),
                candidateTitle = "The Weeknd - Blinding Lights (Official Lyric Video)",
                secondaryText = "The Weeknd • 4:21",
                sourceDurationSeconds = 261,
            ),
        )
    }

    @Test
    fun russianTextVideoIsNotAnOfficialClip() {
        assertNull(
            YouTubeMusicVideoMatcher.scoreCandidate(
                sourceTitle = "Новый день",
                sourceTitleNorm = YouTubeMusicVideoMatcher.normalizeTitle("Новый день"),
                sourceArtistNorms = listOf("исполнитель"),
                candidateTitle = "Исполнитель - Новый день (текст песни)",
                secondaryText = "Исполнитель • 3:40",
                sourceDurationSeconds = 220,
            ),
        )
    }

    @Test
    fun lyricsTitleInSourceStillCannotChooseLyricUpload() {
        assertNull(
            YouTubeMusicVideoMatcher.scoreCandidate(
                sourceTitle = "Song (Lyrics)",
                sourceTitleNorm = YouTubeMusicVideoMatcher.normalizeTitle("Song (Lyrics)"),
                sourceArtistNorms = listOf("artist"),
                candidateTitle = "Artist - Song (Official Lyrics Video)",
                secondaryText = "Artist • 3:30",
                sourceDurationSeconds = 210,
            ),
        )
    }

    @Test
    fun subtitleOnlyVideoIsNotAnOfficialClip() {
        assertNull(
            YouTubeMusicVideoMatcher.scoreCandidate(
                sourceTitle = "Song",
                sourceTitleNorm = YouTubeMusicVideoMatcher.normalizeTitle("Song"),
                sourceArtistNorms = listOf("artist"),
                candidateTitle = "Artist - Song (Subtitled)",
                secondaryText = "Artist • 3:40",
                sourceDurationSeconds = 220,
            ),
        )
    }

    @Test
    fun durationParserHandlesMinutesAndHours() {
        assertEquals(245, YouTubeMusicVideoMatcher.extractDurationSeconds("4:05"))
        assertEquals(3723, YouTubeMusicVideoMatcher.extractDurationSeconds("1:02:03"))
    }
}
