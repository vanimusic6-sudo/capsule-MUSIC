package com.nikhil.yt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentalLyricsTest {
    private fun breaks(entries: List<LyricsEntry>, duration: Long = 0L) =
        withInstrumentalBreaks(entries, duration).filter { it.isInstrumental }

    @Test
    fun `intro uses archive tune timing and a five second minimum`() {
        assertTrue(breaks(listOf(LyricsEntry(5_999, "First"))).isEmpty())
        val note = breaks(listOf(LyricsEntry(6_000, "First"))).single()
        assertEquals(1_000L, note.time)
        assertEquals(5_000L, note.durationMs)
    }

    @Test
    fun `word timed break starts after the last vocal including background words`() {
        val previous = LyricsEntry(1_000, "Voice", words = listOf(
            WordTimestamp("Main", 1.0, 4.0),
            WordTimestamp("Background", 2.0, 6.0, isBackground = true),
        ))
        val next = LyricsEntry(13_000, "Next")
        val rows = withInstrumentalBreaks(listOf(previous, next))
        val note = rows.single { it.isInstrumental }
        assertEquals(6_000L, note.time)
        assertEquals(7_000L, note.durationMs)
        assertSame(previous, rows.first())
        assertSame(next, rows.last())
    }

    @Test
    fun `timestamped empty LRC line marks a middle instrumental break`() {
        val parsed = LyricsUtils.parseLyrics("[00:01.00]First\n[00:08.00]\n[00:15.00]Next")
        assertEquals(3, parsed.size)
        val rows = withInstrumentalBreaks(parsed)
        val note = rows.single { it.isInstrumental }
        assertEquals(8_000L, note.time)
        assertEquals(7_000L, note.durationMs)
        assertEquals(3, rows.size)
    }

    @Test
    fun `long line alone is not mistaken for an instrumental`() {
        assertTrue(breaks(listOf(LyricsEntry(1_000, "Long vocal"), LyricsEntry(30_000, "Next"))).isEmpty())
        assertTrue(breaks(listOf(
            LyricsEntry(1_000, "Sustained", durationMs = 25_000),
            LyricsEntry(30_000, "Next"),
        )).isEmpty())
    }

    @Test
    fun `overlapping TTML agents must all finish before a break`() {
        val note = breaks(listOf(
            LyricsEntry(1_000, "Lead", durationMs = 18_000),
            LyricsEntry(3_000, "Second voice", durationMs = 1_000),
            LyricsEntry(25_000, "Next"),
        )).single()
        assertEquals(19_000L, note.time)
        assertEquals(6_000L, note.durationMs)
    }

    @Test
    fun `outro respects word end and is absent without song duration`() {
        val last = LyricsEntry(1_000, "Last", words = listOf(WordTimestamp("Last", 1.0, 7.0)))
        assertTrue(breaks(listOf(last)).isEmpty())
        val note = breaks(listOf(last), 20_000).single()
        assertEquals(7_000L, note.time)
        assertEquals(13_000L, note.durationMs)
        assertTrue(breaks(listOf(last), 11_999).isEmpty())
    }

    @Test
    fun `line only outro has archive tune vocal tail`() {
        val note = breaks(listOf(LyricsEntry(1_000, "Last")), 20_000).single()
        assertEquals(3_500L, note.time)
        assertEquals(16_500L, note.durationMs)
    }

    @Test
    fun `metadata lines do not become timed lyrics and short empty rows stay hidden`() {
        assertTrue(LyricsUtils.parseLyrics("[ar:Artist]\n[ti:Song]").isEmpty())
        val rows = withInstrumentalBreaks(listOf(
            LyricsEntry(1_000, "First"), LyricsEntry(2_000, ""), LyricsEntry(3_000, "Next"),
        ))
        assertEquals(2, rows.size)
        assertFalse(rows.any { it.isInstrumental })
    }

    @Test
    fun `fill is clamped and follows backward seeks`() {
        assertEquals(0f, instrumentalFillFraction(0, 10_000, 10_000), 0f)
        assertEquals(0f, instrumentalFillFraction(10_000, 10_000, 10_000), 0f)
        assertEquals(0.5f, instrumentalFillFraction(15_000, 10_000, 10_000), 0f)
        assertEquals(1f, instrumentalFillFraction(20_000, 10_000, 10_000), 0f)
        assertEquals(1f, instrumentalFillFraction(50_000, 10_000, 10_000), 0f)
        assertEquals(0.2f, instrumentalFillFraction(12_000, 10_000, 10_000), 0f)
        assertEquals(0f, instrumentalFillFraction(15_000, 10_000, 0), 0f)
    }
    @Test
    fun `note appears at pause start and disappears at the next vocal`() {
        assertFalse(isInstrumentalInterval(9_700, 10_000, 5_000)) // 300ms lyric lead
        assertFalse(isInstrumentalInterval(9_999, 10_000, 5_000))
        assertTrue(isInstrumentalInterval(10_000, 10_000, 5_000))
        assertTrue(isInstrumentalInterval(14_999, 10_000, 5_000))
        assertFalse(isInstrumentalInterval(15_000, 10_000, 5_000))
        assertFalse(isInstrumentalInterval(50_000, 10_000, 5_000))
        assertFalse(isInstrumentalInterval(12_000, 10_000, 0))
        assertTrue(isInstrumentalInterval(12_000, 10_000, 5_000)) // backward seek
    }

}
