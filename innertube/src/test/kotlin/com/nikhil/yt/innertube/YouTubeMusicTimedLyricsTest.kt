package com.nikhil.yt.innertube

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class YouTubeMusicTimedLyricsTest {
    @Test fun extractsEndpointFromNestedWatchNextLyricsTab() {
        val response = Json.parseToJsonElement(
            """{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{"endpoint":{"browseEndpoint":{"browseId":"MPLYt_valid_123"}}}}]}}}}}"""
        )
        assertEquals("MPLYt_valid_123", YouTubeMusicTimedLyrics.findLyricsBrowseId(response))
    }

    @Test fun convertsAndroidMusicTimedLyricsToValidLrc() {
        val response = Json.parseToJsonElement(
            """{"contents":{"sectionListRenderer":{"contents":[{"musicDescriptionShelfRenderer":{"timedLyricsData":[{"lyricLine":"♪","cueRange":{"startTimeMilliseconds":"0","endTimeMilliseconds":"1200"}},{"lyricLine":"First line","cueRange":{"startTimeMilliseconds":"13520","endTimeMilliseconds":"16240"}},{"lyricLine":"Second line","cueRange":{"startTimeMilliseconds":"60890","endTimeMilliseconds":"66670"}}]}}]}}}"""
        )
        assertEquals("[00:00.000]♪\n[00:13.520]First line\n[01:00.890]Second line",
            YouTubeMusicTimedLyrics.toSynchronizedLrc(response))
    }

    @Test fun timedLinesWithStartButNoEndAreStillSynchronized() {
        val json = Json.parseToJsonElement(
            """{"timedLyricsData":[{"lyricLine":"Start only","cueRange":{"startTimeMilliseconds":"11200"}}]}"""
        )
        assertEquals("[00:11.200]Start only", YouTubeMusicTimedLyrics.toSynchronizedLrc(json))
    }

    @Test fun neverInventsTimesForPlainLyricsOrMalformedCueRanges() {
        assertNull(YouTubeMusicTimedLyrics.toSynchronizedLrc(
            Json.parseToJsonElement("""{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"Hello"}]}}}""")))
        assertNull(YouTubeMusicTimedLyrics.toSynchronizedLrc(
            Json.parseToJsonElement("""{"timedLyricsData":[{"lyricLine":"Hi","cueRange":{"startTimeMilliseconds":"not-a-timestamp","endTimeMilliseconds":"0"}}]}""")))
    }

    @Test fun duplicateTimestampsAndEmbeddedLineBreaksCannotMakeFakeLrcRows() {
        val response = Json.parseToJsonElement(
            """{"timedLyricsData":[{"lyricLine":"hello\\nthere","cueRange":{"startTimeMilliseconds":"2500","endTimeMilliseconds":"3000"}},{"lyricLine":"hello\\nthere","cueRange":{"startTimeMilliseconds":"2500","endTimeMilliseconds":"3000"}}]}"""
        )
        val result = requireNotNull(YouTubeMusicTimedLyrics.toSynchronizedLrc(response))
        assertEquals(1, result.lines().size)
        assertTrue(result.startsWith("[00:02.500]"))
    }
}
