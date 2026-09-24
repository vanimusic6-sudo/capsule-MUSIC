package com.nikhil.yt.innertube.soundcloud

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

    @Test fun rejectsProfilesPlaylistsAndSearchPagesAsSongs() {
        assertFalse(SoundCloudNewPipe.isSoundCloudTrackUrl("https://soundcloud.com/artist"))
        assertFalse(SoundCloudNewPipe.isSoundCloudTrackUrl("https://soundcloud.com/artist/sets/my-mixtape"))
        assertFalse(SoundCloudNewPipe.isSoundCloudTrackUrl("https://soundcloud.com/search/sounds?q=test"))
        assertFalse(SoundCloudNewPipe.isSoundCloudTrackUrl("https://soundcloud.com/artist/likes"))
    }
}
