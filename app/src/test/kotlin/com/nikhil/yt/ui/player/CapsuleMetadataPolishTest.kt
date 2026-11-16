package com.nikhil.yt.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure, deterministic regression gates for 12931.jpg / 12933.jpg / 12935.jpg. */
class CapsuleMetadataPolishTest {
    @Test fun titleNeverBisectsCyrillicEmojiOrCombiningAccents() {
        val title = "Кооператив «Лебедин» 👩‍🎤 e\u0301"
        val bounds = capsuleGraphemeBoundaries(title)
        assertEquals(0, bounds.first())
        assertEquals(title.length, bounds.last())
        assertTrue(bounds.size > 4)
        for (index in bounds) {
            assertTrue(index in 0..title.length)
            // No UTF-16 boundary may split a surrogate pair (an emoji code point).
            if (index > 0 && index < title.length) {
                assertFalse(
                    Character.isHighSurrogate(title[index - 1]) &&
                        Character.isLowSurrogate(title[index]),
                )
            }
        }
        for (start in 0 until bounds.lastIndex) {
            val end = capsuleTitleWindowEnd(title, bounds, start, 120) {
                it.codePointCount(0, it.length) * 11
            }
            assertTrue(end in start..bounds.lastIndex)
            val visible = title.substring(bounds[start], bounds[end])
            assertTrue(visible.codePointCount(0, visible.length) <= 11)
            assertTrue(bounds.contains(bounds[end]))
        }
    }

    @Test fun artistNamesUseBulletsAndCountOnlyWholeHiddenNames() {
        val names = listOf("Noize MC", "Монеточка", "Third Artist", "Fourth Artist")
        val full = names.joinToString(" • ")
        assertEquals(full, capsuleArtistLabel(names, full.length + 2) { it.length })
        val two = "Noize MC • Монеточка • ещё +2"
        assertEquals(two, capsuleArtistLabel(names, two.length + 2) { it.length })
        assertEquals("ещё +4", capsuleArtistLabel(names, 13) { it.length })
        assertEquals("+4", capsuleArtistLabel(names, 4) { it.length })
        assertEquals("", capsuleArtistLabel(names, 2) { it.length })
        assertEquals("", capsuleArtistLabel(emptyList(), 400) { it.length })
    }
}
