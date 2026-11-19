package com.nikhil.yt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.json.Json

class LyricsPlusResponseContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun directSyncedLrcMirrorResponseIsAccepted() {
        val payload = json.decodeFromString<LyricsPlusResponse>(
            """{"syncedLyrics":"[00:12.34]Song words","type":"Line"}"""
        )
        assertEquals("[00:12.34]Song words", LyricsPlusLyricsProvider.toLrc(payload))
    }

    @Test fun plainOnlyMirrorResponseIsAccepted() {
        val payload = json.decodeFromString<LyricsPlusResponse>(
            """{"plainLyrics":"One line","type":"plain"}"""
        )
        assertEquals("One line", LyricsPlusLyricsProvider.toLrc(payload))
    }

    @Test fun kpoePayloadStillKeepsTimedWords() {
        val payload = json.decodeFromString<LyricsPlusResponse>(
            """{"type":"Word","lyrics":[{"time":1500,"text":"Hi there","syllabus":[{"time":1500,"duration":500,"text":"Hi "},{"time":2000,"duration":800,"text":"there"}]}]}"""
        )
        assertEquals(
            "[00:01.50]<00:01.50>Hi <00:02.00>there<00:02.80>",
            LyricsPlusLyricsProvider.toLrc(payload)
        )
    }
}
