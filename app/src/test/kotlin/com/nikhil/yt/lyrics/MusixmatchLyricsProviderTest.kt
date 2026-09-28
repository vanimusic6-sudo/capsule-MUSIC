package com.nikhil.yt.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusixmatchLyricsProviderTest {
    @Test
    fun rejectsUniformPseudoLanguagePlaceholder() {
        val lyrics =
            buildString {
                repeat(60) { index ->
                    val totalSeconds = 12 + index * 4
                    val minutes = totalSeconds / 60
                    val seconds = totalSeconds % 60
                    append("[%02d:%02d.00]Wob gopini den\n".format(minutes, seconds))
                }
            }

        assertTrue(MusixmatchLyricsProvider.isLikelySyntheticPlaceholder(lyrics))
    }

    @Test
    fun keepsOrdinarySyncedLyrics() {
        val lyrics =
            """
            [00:12.34]I found a reason to stay
            [00:16.91]And every second changes me
            [00:22.08]Somewhere the city wakes again
            [00:27.73]But I still hear your name
            """.trimIndent()

        assertFalse(MusixmatchLyricsProvider.isLikelySyntheticPlaceholder(lyrics))
    }
}
