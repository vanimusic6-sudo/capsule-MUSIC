package com.nikhil.yt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SimpMusicApiContractTest {
    @Test fun referenceSuccessTypeDoesNotRequireBooleanSuccessField() {
        val input = """{"type":"success","data":[{"songTitle":"Strangers","artistName":"Oliver Tree","durationSeconds":170,"videoId":"Y9SmNz2RofI","syncedLyrics":"[00:01.20]Hello"}]}"""
        val matches = SimpMusicLyricsProvider.parseEntries(input)
        assertEquals(1, matches.size)
        assertEquals("[00:01.20]Hello", matches.single().lyrics)
        assertTrue(SimpMusicLyricsProvider.matches(matches.single(), "Strangers", "Oliver Tree", 171))
    }

    @Test fun noEchoedVideoIdStillContainsAUsableSubmission() {
        val input = """{"type":"success","data":[{"songTitle":"Strangers","artistName":"Oliver Tree","plainLyric":"A lyric"}]}"""
        val matches = SimpMusicLyricsProvider.parseEntries(input)
        assertEquals("", matches.single().videoId)
        assertEquals("A lyric", matches.single().lyrics)
    }

    @Test fun nonLyricJsonPayloadCannotMasqueradeAsTimedText() {
        assertFalse(SimpMusicLyricsProvider.isRenderableSync("[{\"time\":1200,\"text\":\"hello\"}]"))
    }
}
