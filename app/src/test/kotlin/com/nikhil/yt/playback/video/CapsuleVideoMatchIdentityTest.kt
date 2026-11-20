package com.nikhil.yt.playback.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CapsuleVideoMatchIdentityTest {
    @Test fun sameMetadataReusesVerifiedClipDespiteArtistOrderAndCase() {
        val original = capsuleVideoMatchIdentity(
            " Song ", listOf("Main Artist", "Guest"), 219,
        )
        val updated = capsuleVideoMatchIdentity(
            "song", listOf("guest", "main artist", "Guest"), 219,
        )
        assertEquals(original, updated)
    }

    @Test fun changedSongMetadataInvalidatesOldClipAssociation() {
        val song = capsuleVideoMatchIdentity("Home", listOf("Artist One"), 219)
        assertNotEquals(song, capsuleVideoMatchIdentity("Home", listOf("Artist Two"), 219))
        assertNotEquals(song, capsuleVideoMatchIdentity("Another Home", listOf("Artist One"), 219))
        assertNotEquals(song, capsuleVideoMatchIdentity("Home", listOf("Artist One"), 290))
    }

    @Test fun normalizedIdentityBoundsUntrustedSearchMetadata() {
        val huge = capsuleVideoMatchIdentity("T".repeat(12_000), List(24) {
            "Artist-$it " + "A".repeat(500)
        }, -5)
        assertEquals(256, huge.title.length)
        assertEquals(8, huge.artists.size)
        assertEquals(null, huge.durationSeconds)
        assertEquals(true, huge.artists.all { it.length <= 128 })
    }
}
