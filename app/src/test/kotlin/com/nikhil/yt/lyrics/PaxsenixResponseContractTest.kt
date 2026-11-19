package com.nikhil.yt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.json.Json

class PaxsenixResponseContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun relayStructuredLineContentIsNotDiscarded() {
        val payload = json.decodeFromString<PaxsenixLyrics>(
            """{"type":"Line","content":[{"timestamp":1500,"text":[{"text":"Hello"},{"text":"world"}]}]}"""
        )
        assertEquals("[00:01.50]Hello world", paxsenixLyricsText(payload))
    }

    @Test fun syllableContentPreservesWordTimestamps() {
        val payload = json.decodeFromString<PaxsenixLyrics>(
            """{"type":"Syllable","content":[{"timestamp":1000,"text":[{"text":"Hello ","timestamp":1000,"endtime":1400},{"text":"world","timestamp":1400,"endtime":1800}]}]}"""
        )
        assertEquals(
            "[00:01.00]<00:01.00>Hello <00:01.40>world<00:01.80>",
            paxsenixLyricsText(payload)
        )
    }

    @Test fun ttmlAndElrcAreStillPrioritized() {
        assertEquals("<tt>lyrics</tt>",
            paxsenixLyricsText(PaxsenixLyrics(ttmlContent = "<tt>lyrics</tt>", plain = "text")))
        assertEquals("[00:04.50]lyric",
            paxsenixLyricsText(PaxsenixLyrics(elrc = "[00:04.50]lyric")))
    }
}
