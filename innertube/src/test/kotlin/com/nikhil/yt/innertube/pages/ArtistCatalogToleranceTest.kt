package com.nikhil.yt.innertube.pages

import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.models.MusicTwoRowItemRenderer
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.SongItem
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Realistic browse cards often lack overlay actions, shuffle/radio or art. */
class ArtistCatalogToleranceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun card(pageType: String, id: String): MusicTwoRowItemRenderer =
        json.decodeFromString(
            """{
                "title": {"runs": [{"text": "An official release", "navigationEndpoint": null}]},
                "subtitle": null, "subtitleBadges": null, "menu": null,
                "thumbnailRenderer": {
                    "musicThumbnailRenderer": null,
                    "musicAnimatedThumbnailRenderer": null,
                    "croppedSquareThumbnailRenderer": null
                },
                "navigationEndpoint": {
                    "browseEndpoint": {
                        "browseId": "$id",
                        "browseEndpointContextSupportedConfigs": {
                            "browseEndpointContextMusicConfig": {"pageType": "$pageType"}
                        }
                    }
                },
                "thumbnailOverlay": null
            }""",
        )

    @Test fun albumWithoutPlayOverlaySurvivesBothArtistParsers() {
        val renderer = card("MUSIC_PAGE_TYPE_ALBUM", "MPREbExample")
        // The More catalog must not require an optional watch/playlist endpoint.
        val catalog = ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer) as AlbumItem
        assertEquals("MPREbExample", catalog.id)
        assertEquals("MPREbExample", catalog.playlistId)
    }

    @Test fun playlistWithoutOptionalMenuOrOverlayIsStillNavigable() {
        val renderer = card("MUSIC_PAGE_TYPE_PLAYLIST", "VLPLexample")
        val catalog = ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer) as PlaylistItem
        assertEquals("PLexample", catalog.id)
        assertNull(catalog.playEndpoint)
        assertNull(catalog.shuffleEndpoint)
        assertNull(catalog.radioEndpoint)
    }

    @Test fun videoWithoutThumbnailIsRetainedAsEpisode() {
        val renderer = json.decodeFromString<MusicTwoRowItemRenderer>(
            """{
                "title": {"runs": [{"text": "Episode 5", "navigationEndpoint": null}]},
                "subtitle": null, "subtitleBadges": null, "menu": null,
                "thumbnailRenderer": {
                    "musicThumbnailRenderer": null, "musicAnimatedThumbnailRenderer": null,
                    "croppedSquareThumbnailRenderer": null
                },
                "navigationEndpoint": {"watchEndpoint": {"videoId": "episode123"}},
                "thumbnailOverlay": null
            }""",
        )
        assertEquals("episode123", (ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer) as SongItem).id)
    }
}
