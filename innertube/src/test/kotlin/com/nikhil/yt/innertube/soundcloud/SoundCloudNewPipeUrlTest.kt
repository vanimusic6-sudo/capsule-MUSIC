package com.nikhil.yt.innertube.soundcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundCloudNewPipeUrlTest {
    @Test fun acceptsOnlyOfficialHttpsSoundCloudTrackUrls() {
        assertTrue(SoundCloudNewPipe.isSoundCloudTrackUrl("https://soundcloud.com/artist/track"))
        assertTrue(SoundCloudNewPipe.isSoundCloudTrackUrl("https://www.soundcloud.com/artist/track?utm_source=share"))
        assertFalse(SoundCloudNewPipe.isSoundCloudTrackUrl("http://soundcloud.com/artist/track"))
        assertFalse(SoundCloudNewPipe.isSoundCloudTrackUrl("https://soundcloud.com.evil.example/artist/track"))
        assertFalse(SoundCloudNewPipe.isSoundCloudTrackUrl("https://soundcloud.com@evil.example/artist/track"))
    }

    @Test fun upgradesOnlyRecognizedSoundCloudThumbnailSize() {
        assertEquals(
            "https://i1.sndcdn.com/artworks-ABC-t500x500.jpg",
            SoundCloudNewPipe.soundCloudArtworkAtFullSize("https://i1.sndcdn.com/artworks-ABC-large.jpg"),
        )
        assertEquals(
            "https://i1.sndcdn.com/artworks-ABC-t500x500.jpg?token=1",
            SoundCloudNewPipe.soundCloudArtworkAtFullSize("https://i1.sndcdn.com/artworks-ABC-large.jpg?token=1"),
        )
        assertEquals(
            "https://other.example/artworks-ABC-large.jpg",
            SoundCloudNewPipe.soundCloudArtworkAtFullSize("https://other.example/artworks-ABC-large.jpg"),
        )
    }

    @Test fun rejectsProfilesPlaylistsAndSearchPagesAsSongs() {
        assertFalse(SoundCloudNewPipe.isSoundCloudTrackUrl("https://soundcloud.com/artist"))
        assertFalse(SoundCloudNewPipe.isSoundCloudTrackUrl("https://soundcloud.com/artist/sets/my-mixtape"))
        assertFalse(SoundCloudNewPipe.isSoundCloudTrackUrl("https://soundcloud.com/search/sounds?q=test"))
        assertFalse(SoundCloudNewPipe.isSoundCloudTrackUrl("https://soundcloud.com/artist/likes"))
    }

    @Test fun derivesProfileUrlFromTrackPermalink() {
        assertEquals(
            "https://soundcloud.com/artist",
            SoundCloudNewPipe.soundCloudUserUrlFromTrack("https://soundcloud.com/artist/track"),
        )
        assertEquals(
            "https://soundcloud.com/artist",
            SoundCloudNewPipe.soundCloudUserUrlFromTrack("https://www.soundcloud.com/artist/track?utm_source=share"),
        )
        assertTrue(SoundCloudNewPipe.soundCloudUserUrlFromTrack("https://soundcloud.com/artist") == null)
        assertTrue(SoundCloudNewPipe.soundCloudUserUrlFromTrack("https://evil.example/artist/track") == null)
    }

    @Test fun distinguishesProfileAndPlaylistUrls() {
        assertTrue(SoundCloudNewPipe.isSoundCloudUserUrl("https://soundcloud.com/artist"))
        assertFalse(SoundCloudNewPipe.isSoundCloudUserUrl("https://soundcloud.com/artist/track"))
        assertTrue(SoundCloudNewPipe.isSoundCloudPlaylistUrl("https://soundcloud.com/artist/sets/my-mixtape"))
        assertFalse(SoundCloudNewPipe.isSoundCloudPlaylistUrl("https://soundcloud.com/artist/track"))
        assertFalse(SoundCloudNewPipe.isSoundCloudPlaylistUrl("https://evil.example/artist/sets/mix"))
    }
}
