package com.nikhil.yt.lrclib.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackNullableDurationTest {
    @Test
    fun searchResponseAcceptsNullDurationAndDoesNotTreatItAsZero() {
        val tracks = Json { ignoreUnknownKeys = true }.decodeFromString<List<Track>>(
            """[
              {"id":1,"trackName":"MIXTAPE","artistName":"Artist","duration":null,"plainLyrics":"plain","syncedLyrics":"[00:01.00]one"},
              {"id":2,"trackName":"MIXTAPE","artistName":"Artist","duration":201.2,"plainLyrics":null,"syncedLyrics":"[00:01.00]two"}
            ]""",
        )

        assertNull(tracks[0].duration)
        assertEquals(2, tracks.bestMatchingFor(201)?.id)
        assertNull(listOf(tracks[0]).bestMatchingFor(201))
    }
}
