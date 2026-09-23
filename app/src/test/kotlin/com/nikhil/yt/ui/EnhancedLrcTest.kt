package com.nikhil.yt.ui

import com.nikhil.yt.lyrics.LyricsUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Word timings carried by enhanced LRC.
 *
 * Until now only TTML had anywhere to put these, so a provider could return perfectly word-timed
 * lyrics and have every timing discarded on the way in — which is most of the distance between
 * lyrics that merely keep up and lyrics that land on the syllable.
 */
class EnhancedLrcTest {
    private val line = "[00:00.55]<00:00.55>My <00:00.90>day <00:01.83>will come<00:03.19>"

    @Test fun wordsAndTheirStartsAreRead() {
        val words = requireNotNull(LyricsUtils.parseWordTimings(line))
        assertEquals(listOf("My ", "day ", "will come"), words.map { it.text })
        assertEquals(0.55, words[0].startTime, 0.001)
        assertEquals(0.90, words[1].startTime, 0.001)
        assertEquals(1.83, words[2].startTime, 0.001)
    }

    @Test fun eachWordEndsWhereTheNextBegins() {
        val words = requireNotNull(LyricsUtils.parseWordTimings(line))
        assertEquals(0.90, words[0].endTime, 0.001)
        assertEquals(1.83, words[1].endTime, 0.001)
    }

    /** The trailing stamp is the line's end, not a word of its own. */
    @Test fun theTrailingStampClosesTheLastWord() {
        val words = requireNotNull(LyricsUtils.parseWordTimings(line))
        assertEquals("the last word must not be left open", 3.19, words.last().endTime, 0.001)
        assertTrue("a stamp with no word after it is not a word", words.none { it.text.isBlank() })
    }

    @Test fun aLineWithoutStampsHasNoWordTimings() {
        assertNull(LyricsUtils.parseWordTimings("[00:00.55]My day will come"))
        assertNull(LyricsUtils.parseWordTimings("My day will come"))
    }

    @Test fun theDisplayedTextHasNoStampsLeftInIt() {
        assertEquals("My day will come", LyricsUtils.stripWordTimings(line.substringAfter("]")))
    }

    /** What the parser actually produces from a whole file, which is what the renderer sees. */
    @Test fun parsingAWholeFileKeepsTheWordsAndCleansTheText() {
        val file =
            "[00:00.55]<00:00.55>My <00:00.90>day<00:01.40>\n" +
                "[00:05.81]I gave too much"
        val entries = LyricsUtils.parseLyrics(file)
        assertEquals(2, entries.size)

        assertEquals("My day", entries[0].text)
        assertEquals(2, entries[0].words?.size)

        assertEquals("a plain line keeps working", "I gave too much", entries[1].text)
        assertNull("and gains no invented timings", entries[1].words)
    }
}
