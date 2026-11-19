package com.nikhil.yt.innertube

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail

class YouTubeMusicVideoMatcherTest {
    @Test fun exactOfficialClipIsAcceptedWithIncompletePlaylistMetadata() {
        val result = YouTubeMusicVideoMatcher.scoreCandidate("Playlist title", "playlist title", listOf("playlist author"),
            "Real artist - Real song", "Real artist • 3:30", 210, sameOfficialVideo = true)
        assertTrue(requireNotNull(result) >= YouTubeMusicVideoMatcher.STRONG_MATCH_SCORE)
        assertNull(YouTubeMusicVideoMatcher.scoreCandidate("Playlist title", "playlist title", listOf("playlist author"),
            "Real artist - Real song", "Real artist • 3:30", 210))
    }

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
    fun missingOmvTypeOnVideoSearchStillAcceptsCredibleOfficialClip() {
        val match = YouTubeMusicVideoMatcher.scoreCandidate(
            sourceTitle = "Blinding Lights",
            sourceTitleNorm = "blinding lights",
            sourceArtistNorms = listOf("the weeknd"),
            candidateTitle = "The Weeknd - Blinding Lights (Official Video)",
            secondaryText = "The Weeknd • 4:21",
            sourceDurationSeconds = 261,
            omvVerified = false,
        )
        assertTrue("Missing renderer metadata is not proof of a missing clip", match != null)
    }

    @Test
    fun untypedSameTitleWrongArtistNeverPasses() {
        assertNull(YouTubeMusicVideoMatcher.scoreCandidate(
            sourceTitle = "Home", sourceTitleNorm = "home",
            sourceArtistNorms = listOf("artist one"),
            candidateTitle = "Home (Official Video)", secondaryText = "Artist Two • 3:30",
            sourceDurationSeconds = 210, omvVerified = false,
        ))
    }

    @Test
    fun videoSearchCanTrustExactArtistTitleWhenOmvAndRuntimeAreMissing() {
        assertTrue(YouTubeMusicVideoMatcher.scoreCandidate(
            sourceTitle = "Blinding Lights", sourceTitleNorm = "blinding lights",
            sourceArtistNorms = listOf("the weeknd"),
            candidateTitle = "The Weeknd - Blinding Lights",
            secondaryText = "The Weeknd", sourceDurationSeconds = 261,
            omvVerified = false,
        ) != null)
        assertNull(YouTubeMusicVideoMatcher.scoreCandidate(
            sourceTitle = "Blinding Lights", sourceTitleNorm = "blinding lights",
            sourceArtistNorms = listOf("the weeknd"),
            candidateTitle = "Summer Cover - The Weeknd - Blinding Lights",
            secondaryText = "The Weeknd", sourceDurationSeconds = 261,
            omvVerified = false,
        ))
    }

    @Test
    fun sameTitleWithWrongArtistIsRejectedEvenWhenOmvLabelIsPresent() {
        assertNull(YouTubeMusicVideoMatcher.scoreCandidate(
            sourceTitle = "Home", sourceTitleNorm = "home",
            sourceArtistNorms = listOf("artist one"),
            candidateTitle = "Home (Official Music Video)",
            secondaryText = "Artist Two • 3:30",
            sourceDurationSeconds = 210,
        ))
    }

    @Test
    fun artistNameMustBeAWholePhraseNotAFragment() {
        assertNull(YouTubeMusicVideoMatcher.scoreCandidate(
            sourceTitle = "Falling", sourceTitleNorm = "falling",
            sourceArtistNorms = listOf("ade"),
            candidateTitle = "Falling (Official Music Video)",
            secondaryText = "Arcade Fire • 3:42",
            sourceDurationSeconds = 222,
        ))
    }

    @Test
    fun unknownArtistNeverChoosesAnotherSongWithSameTitle() {
        assertNull(YouTubeMusicVideoMatcher.scoreCandidate(
            sourceTitle = "Home",
            sourceTitleNorm = "home",
            sourceArtistNorms = emptyList(),
            candidateTitle = "Home (Official Music Video)",
            secondaryText = "Unknown Official • 3:30",
            sourceDurationSeconds = 210,
        ))
        assertTrue(requireNotNull(YouTubeMusicVideoMatcher.scoreCandidate(
            sourceTitle = "Home",
            sourceTitleNorm = "home",
            sourceArtistNorms = emptyList(),
            candidateTitle = "Anything",
            secondaryText = "",
            sourceDurationSeconds = 210,
            sameOfficialVideo = true,
        )) >= YouTubeMusicVideoMatcher.STRONG_MATCH_SCORE)
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
    fun cancelledOfficialVideoSearchPropagatesCancellation() = runBlocking {
        YouTubeMusicVideoLinkResolver.clearCache()
        try {
            YouTubeMusicVideoLinkResolver.resolve(
                sourceMediaId = "abcdefghijk",
                title = "Track",
                artists = listOf("Artist"),
                durationSeconds = 180,
                beforeMetadataRequest = { throw CancellationException("song changed") },
            )
            fail("Cancelled search must throw rather than return Result.failure")
        } catch (_: CancellationException) {
            // The owner can now start the next song without stale completion.
        } finally {
            YouTubeMusicVideoLinkResolver.clearCache()
        }
    }

    @Test
    fun weakLegacyOmvCacheCannotRestoreIncorrectVideo() {
        YouTubeMusicVideoLinkResolver.clearCache()
        try {
            val now = System.currentTimeMillis()
            YouTubeMusicVideoLinkResolver.importCache(listOf(
                YouTubeMusicVideoLinkResolver.CacheRow(
                    sourceMediaId = "abcdefghijk", videoId = "lmnopqrstuv",
                    title = "Home (Official Video)", score = 220,
                    expiresAtMs = now + 86_400_000L,
                ),
                YouTubeMusicVideoLinkResolver.CacheRow(
                    sourceMediaId = "mnopqrstu01", videoId = "abcdefghijk",
                    title = "Valid strong official clip", score = 260,
                    expiresAtMs = now + 86_400_000L,
                ),
            ))
            assertEquals(1, YouTubeMusicVideoLinkResolver.exportCache().size)
            assertEquals("mnopqrstu01",
                YouTubeMusicVideoLinkResolver.exportCache().single().sourceMediaId)
        } finally {
            YouTubeMusicVideoLinkResolver.clearCache()
        }
    }

    @Test
    fun durationParserHandlesMinutesAndHours() {
        assertEquals(245, YouTubeMusicVideoMatcher.extractDurationSeconds("4:05"))
        assertEquals(3723, YouTubeMusicVideoMatcher.extractDurationSeconds("1:02:03"))
    }
}
