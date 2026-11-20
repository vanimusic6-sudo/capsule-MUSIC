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

    @Test fun updatingArtistBrowseIdentityForcesFreshOfficialVideoMatch() {
        val first = capsuleVideoMatchIdentity("Song", listOf("Artist"), 219,
            listOf("UC" + "A".repeat(22)))
        val changed = capsuleVideoMatchIdentity("Song", listOf("Artist"), 219,
            listOf("UC" + "B".repeat(22)))
        assertNotEquals(first, changed)
        assertEquals(first, capsuleVideoMatchIdentity("Song", listOf("Artist"), 219,
            listOf("UC" + "A".repeat(22))))
    }

    @Test fun sharedOfficialClipNeverRestoresThePreviousAudioTrackId() {
        val stream = YouTubeVideoResolver.ResolvedVideo(
            sourceMediaId = "abcdefghijk",
            videoId = "clip123abcd",
            videoStreamUrl = "https://rr.googlevideo.com/videoplayback?expire=4102444800",
            videoFormat = YouTubeVideoResolver.StreamFormat(
                itag = 136, width = 1280, height = 720, qualityLabel = "720p",
            ),
            expiresAtMs = 4_102_444_800_000L,
        )
        val rebound = capsuleVideoBindSource(stream, "mnopqrstuvw")
        assertEquals("mnopqrstuvw", rebound.sourceMediaId)
        assertEquals(stream.videoId, rebound.videoId)
        assertEquals(stream.videoStreamUrl, rebound.videoStreamUrl)
        assertEquals(stream.expiresAtMs, rebound.expiresAtMs)
        assertEquals(stream.videoFormat, rebound.videoFormat)
        assertEquals("abcdefghijk", stream.sourceMediaId)
        org.junit.Assert.assertSame(stream, capsuleVideoBindSource(stream, "abcdefghijk"))
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
