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

    @Test fun singleObjectDataFromCompatibleServersIsParsed() {
        val input = """{"type":"success","data":{"videoId":"Y9SmNz2RofI","songTitle":"Strangers","artistName":"Oliver Tree","plainLyric":"Readable line"}}"""
        assertEquals("Readable line", SimpMusicLyricsProvider.parseEntries(input).single().lyrics)
    }

    @Test fun explicitErrorsAndProcessingAreNotTreatedAsLyrics() {
        assertTrue(SimpMusicLyricsProvider.parseEntries(
            """{"type":"error","success":false,"error":{"code":429}}"""
        ).isEmpty())
        assertTrue(SimpMusicLyricsProvider.parseEntries(
            """{"type":"processing","success":false,"processing":{"code":102}}"""
        ).isEmpty())
    }

    @Test fun titleFallbackUsesDocumentedTitleParameter() {
        val path = "src/main/kotlin/com/nikhil/yt/lyrics/SimpMusicLyricsProvider.kt"
        val file = listOf(java.io.File(path), java.io.File("app/$path"))
            .firstOrNull(java.io.File::isFile) ?: error("SimpMusic source missing")
        assertTrue(file.readText().contains(
            """requestEntries("$ENDPOINT/search/title", title, queryKey = "title")"""
        ))
    }
}
