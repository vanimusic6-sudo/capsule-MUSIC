package com.nikhil.yt.innertube

import com.nikhil.yt.innertube.models.Artist
import com.nikhil.yt.innertube.models.ArtistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.innertube.pages.ArtistPage
import com.nikhil.yt.innertube.pages.ArtistSection
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class YouTubeMusicArtistVideoHintsTest {
    private val artistId = "UC" + "X".repeat(22)
    private fun song(id: String, title: String, type: String? = null) = SongItem(
        id = id, title = title, thumbnail = "",
        artists = listOf(Artist("The Artist", artistId)),
        endpoint = WatchEndpoint(
            videoId = id,
            watchEndpointMusicSupportedConfigs = type?.let {
                WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                    WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig(it),
                )
            },
        ),
    )
    private fun page(section: String, items: List<SongItem>) = ArtistPage(
        artist = ArtistItem(
            id = artistId, title = "The Artist", thumbnail = null,
            shuffleEndpoint = null, radioEndpoint = null,
        ),
        sections = listOf(ArtistSection(section, items, moreEndpoint = null)),
        description = null,
    )
    @After fun clear() = YouTubeMusicArtistVideoHints.clear()

    @Test fun existingArtistPageReplacesGlobalSearchWithoutANetworkCall() {
        val id = "clipVID001A"
        YouTubeMusicArtistVideoHints.remember(page("Клипы", listOf(
            song(id, "The Artist - My Song (Official Video)"),
        )), nowMs = 1000)
        val match = YouTubeMusicArtistVideoHints.match(
            "My Song", listOf("The Artist"), 220, "audioID001A",
            listOf(artistId), nowMs = 1001,
        )
        assertEquals(id, match?.videoId)
        assertTrue(requireNotNull(match).score >= 170)
    }

    @Test fun lyricAndUnrelatedVideoOnArtistPageNeverOverrideTheRealClip() {
        val official = "clipVID001B"
        YouTubeMusicArtistVideoHints.remember(page("Videos", listOf(
            song("lyricVID01A", "My Song (Official Lyric Video)"),
            song("otherVID01A", "Other Song (Official Music Video)"),
            song("ugcVIDEO01A", "My Song", "MUSIC_VIDEO_TYPE_UGC"),
            song(official, "My Song", "MUSIC_VIDEO_TYPE_OMV"),
        )), nowMs = 1000)
        assertEquals(official, YouTubeMusicArtistVideoHints.match(
            "My Song", listOf("The Artist"), null, "audioID001A",
            nowMs = 1010,
        )?.videoId)
        assertNull(YouTubeMusicArtistVideoHints.match(
            "My Song", listOf("Another Artist"), null, "audioID001A",
            nowMs = 1010,
        ))
    }

    @Test fun nonVideoShelvesAndExpiredHintsAreNeverTrusted() {
        YouTubeMusicArtistVideoHints.remember(page("Songs", listOf(
            song("audioSong01A", "My Song"),
        )), nowMs = 1000)
        assertNull(YouTubeMusicArtistVideoHints.match(
            "My Song", listOf("The Artist"), null, "audioID001A", nowMs = 1010,
        ))
        YouTubeMusicArtistVideoHints.remember(page("Videos", listOf(
            song("clipVID001C", "My Song"),
        )), nowMs = 1000)
        assertNull(YouTubeMusicArtistVideoHints.match(
            "My Song", listOf("The Artist"), null, "audioID001A",
            listOf("UC" + "Z".repeat(22)), nowMs = 1010,
        ))
        assertNull(YouTubeMusicArtistVideoHints.match(
            "My Song", listOf("The Artist"), null, "audioID001A",
            nowMs = 31L * 60 * 1000 + 1000,
        ))
    }

    @Test fun artistFallbackNeverTrustsAnUnverifiedPlainTitle() {
        val official = "offcl00100A"
        YouTubeMusicArtistVideoHints.remember(page("Videos", listOf(
            song("untag001AAX", "My Song"),
            song(official, "My Song (Official Video)"),
        )), nowMs = 1000)
        assertEquals(official, YouTubeMusicArtistVideoHints.match(
            "My Song", listOf("The Artist"), 180, "audioID001A",
            listOf(artistId), nowMs = 1010,
        )?.videoId)

        YouTubeMusicArtistVideoHints.clear()
        YouTubeMusicArtistVideoHints.remember(page("Videos", listOf(
            song("untag001AAX", "My Song"),
        )), nowMs = 1000)
        assertNull(YouTubeMusicArtistVideoHints.match(
            "My Song", listOf("The Artist"), 180, "audioID001A",
            listOf(artistId), nowMs = 1010,
        ))
    }

    @Test fun explicitNonVideoRouteCanOnlyPassWhenActuallyTypedOmv() {
        YouTubeMusicArtistVideoHints.remember(page("Popular", listOf(
            song("clipVID001D", "My Song", "MUSIC_VIDEO_TYPE_OMV"),
        )), nowMs = 1000)
        assertEquals("clipVID001D", YouTubeMusicArtistVideoHints.match(
            "My Song", listOf("The Artist"), null, "audioID001A",
            nowMs = 1010,
        )?.videoId)
    }
}
