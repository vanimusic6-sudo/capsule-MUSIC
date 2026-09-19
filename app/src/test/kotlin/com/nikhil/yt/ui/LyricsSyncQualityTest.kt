package com.nikhil.yt.ui

import com.nikhil.yt.lyrics.LyricsSyncQuality
import com.nikhil.yt.lyrics.lyricsSyncQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grading lyrics by how closely they can follow the music.
 *
 * This is what decides which provider's answer gets used, so the grading has to be about the
 * lyrics themselves and not about who sent them: a source that advertises word sync and returns a
 * block of prose must not outrank one that quietly does the job.
 */
class LyricsSyncQualityTest {
    @Test fun proseWithNoTimestampsIsPlain() {
        assertEquals(LyricsSyncQuality.PLAIN, lyricsSyncQuality("My day will come\nI gave too much"))
        assertEquals(LyricsSyncQuality.PLAIN, lyricsSyncQuality(""))
        assertEquals(LyricsSyncQuality.PLAIN, lyricsSyncQuality("   \n  "))
    }

    @Test fun ordinaryLrcIsLineTimed() {
        val lrc = "[00:00.55]My day will come\n[00:05.81]I gave too much"
        assertEquals(LyricsSyncQuality.LINE, lyricsSyncQuality(lrc))
    }

    @Test fun enhancedLrcIsWordTimed() {
        val elrc = "[00:00.55]<00:00.55>My <00:00.90>day <00:01.83>will come<00:03.19>"
        assertEquals(LyricsSyncQuality.WORD, lyricsSyncQuality(elrc))
    }

    @Test fun ttmlIsGradedByWhetherItCarriesWordTimes() {
        val lineOnly =
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s">a line</p></div></body></tt>"""
        assertEquals(LyricsSyncQuality.LINE, lyricsSyncQuality(lineOnly))

        val wordTimed =
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s">""" +
                """<span begin="1s">My</span> <span begin="1.4s">day</span></p></div></body></tt>"""
        assertEquals(LyricsSyncQuality.WORD, lyricsSyncQuality(wordTimed))
    }

    /** The ranking is compared with >, so the order of the constants is load-bearing. */
    @Test fun betterSyncOutranksWorse() {
        assertTrue(LyricsSyncQuality.WORD > LyricsSyncQuality.LINE)
        assertTrue(LyricsSyncQuality.LINE > LyricsSyncQuality.PLAIN)
    }
}
