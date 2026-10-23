package com.nikhil.yt.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgottenDiscoveryTest {
    private fun track(
        id: String,
        source: String = "wikimedia_audio",
        title: String = "Traditional folk music $id",
        category: String? = "music",
        artist: String = "Performer $id",
        duration: Int = 183,
        url: String? = "https://upload.wikimedia.org/example-$id.ogg",
        license: String? = "https://creativecommons.org/licenses/by/4.0/",
    ) = ExperimentalCatalog.Track(
        source = ExperimentalCatalog.Source.OPENVERSE,
        id = id,
        title = title,
        artist = artist,
        artwork = null,
        streamUrl = url,
        sourcePage = "https://commons.wikimedia.org/wiki/File:example-$id",
        durationSeconds = duration,
        license = license,
        catalogProvider = source,
        audioCategory = category,
    )

    @Test fun selectsMusicalCommonsAndFreesoundButNotJamendo() {
        val inputs = listOf(
            track("one"),
            track("two", source = "freesound", title = "Old blues song"),
            track("three", source = "jamendo"),
        )
        val selection = ForgottenDiscovery.select(inputs, dayKey = 13)
        assertEquals(2, selection.size)
        assertFalse(selection.any { it.catalogProvider == "jamendo" })
    }

    @Test fun rejectsPronunciationsEffectsUnlicensedAndUnavailable() {
        val inputs = listOf(
            track("song"),
            track("language", title = "LL-Q123 English pronunciation"),
            track("brief", duration = 3),
            track("effect", title = "Old car sound effect"),
            track("unlicensed", license = null),
            track("offline", url = null),
            track("speech", category = "pronunciation"),
        )
        assertEquals(listOf("song"), ForgottenDiscovery.select(inputs, 10).map { it.id })
    }

    @Test fun repeatableDailyRotationAndArtistCap() {
        val tracks = (1..35).map { track("$it", artist = "One archive musician") } +
            (36..70).map { track("$it") }
        val first = ForgottenDiscovery.select(tracks, dayKey = 101, maximum = 12)
        val second = ForgottenDiscovery.select(tracks, dayKey = 101, maximum = 12)
        assertEquals(first, second)
        assertEquals(12, first.size)
        assertTrue(first.count { it.artist == "One archive musician" } <= 2)
        assertTrue(ForgottenDiscovery.select(tracks, 101, 0).isEmpty())
    }
}
