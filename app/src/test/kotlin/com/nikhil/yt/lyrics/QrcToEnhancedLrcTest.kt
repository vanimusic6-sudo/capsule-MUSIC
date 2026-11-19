package com.nikhil.yt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrcToEnhancedLrcTest {
    @Test fun qqWordLengthsSurviveAsEnhancedLrc() {
        val xml = """<QrcInfos><LyricInfo><Lyric_1 LyricContent="[ti:Song]
[12200,1700]Let (12200,200)it (12400,250)be(12650,300)
"/></LyricInfo></QrcInfos>"""
        val result = requireNotNull(QrcToEnhancedLrc.convert(xml, "Song", "Band"))
        assertEquals(
            "[00:12.200]<00:12.200>Let <00:12.400>it <00:12.650>be<00:12.950>",
            result,
        )
        val entry = LyricsUtils.parseLyrics(result).single()
        assertEquals(12_200L, entry.time)
        assertEquals("Let it be", entry.text)
        assertEquals(3, entry.words?.size)
    }

    @Test fun metadataAndCreditsAreNotShownAsSongLines() {
        val xml = """<Lyric_1 LyricContent="[ti:Example]
[ar:Band]
[5000,500]Example - Band(5000,500)
[6000,400]Lyrics by：Somebody(6000,400)
[14000,800]Actual lyrics(14000,800)
"/>"""
        val result = requireNotNull(QrcToEnhancedLrc.convert(xml, "Example", "Band"))
        assertFalse(result.contains("Lyrics by"))
        assertFalse(result.contains("Example - Band"))
        assertTrue(result.contains("Actual lyrics"))
    }

    @Test fun decodesXmlEscapesAndAllowsLineOnlyQrcRows() {
        val xml = """<Lyric_1 LyricContent="[12000,1000]Rock &amp; roll"/>"""
        val result = QrcToEnhancedLrc.convert(xml, "Track", "Band")
        assertEquals("[00:12.000]Rock & roll", result)
    }
}
