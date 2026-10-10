package com.nikhil.yt.innertube

import com.nikhil.yt.innertube.models.Artist
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs
import com.nikhil.yt.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig
import com.nikhil.yt.innertube.pages.SearchSummary
import com.nikhil.yt.innertube.pages.markExplicitVideoShelves
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitVideoOriginTest {
    private fun item(type: String? = null, fromVideos: Boolean = false) =
        SongItem(
            id = "realVideoId",
            title = "Example",
            artists = listOf(Artist("Singer", null)),
            thumbnail = "https://example.org/cover.jpg",
            endpoint = type?.let {
                WatchEndpoint(
                    videoId = "realVideoId",
                    watchEndpointMusicSupportedConfigs =
                        WatchEndpointMusicSupportedConfigs(WatchEndpointMusicConfig(it)),
                )
            },
            selectedFromVideoResults = fromVideos,
        )

    @Test
    fun normalAudioAndAtvNeverAutostartVideo() {
        assertFalse(item().isOriginalVideo)
        assertFalse(item("MUSIC_VIDEO_TYPE_ATV").isOriginalVideo)
    }

    @Test
    fun omvUgcAndPodcastVideoTypesAutostartVideo() {
        assertTrue(item("MUSIC_VIDEO_TYPE_OMV").isOriginalVideo)
        assertTrue(item("MUSIC_VIDEO_TYPE_UGC").isOriginalVideo)
        assertTrue(item("MUSIC_VIDEO_TYPE_PODCAST_EPISODE").isOriginalVideo)
    }

    @Test
    fun explicitVideosFilterWithoutTypeStillAutostartsVideo() {
        assertTrue(item(fromVideos = true).isOriginalVideo)
    }

    @Test
    fun podcastShelfIsVideoButOrdinarySongShelfIsNot() {
        val source = item()
        val result = markExplicitVideoShelves(
            listOf(
                SearchSummary("Podcasts", listOf(source)),
                SearchSummary("Songs", listOf(source)),
            ),
        )
        assertTrue((result[0].items[0] as SongItem).isOriginalVideo)
        assertFalse((result[1].items[0] as SongItem).isOriginalVideo)
    }
}
