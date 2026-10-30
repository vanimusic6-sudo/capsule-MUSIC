package com.nikhil.yt.innertube.pages

import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.models.BrowseEndpoint
import com.nikhil.yt.innertube.models.NavigationEndpoint
import com.nikhil.yt.innertube.models.MusicResponsiveListItemRenderer
import com.nikhil.yt.innertube.models.MusicCarouselShelfRenderer
import com.nikhil.yt.innertube.models.SectionListRenderer
import com.nikhil.yt.innertube.models.Runs
import com.nikhil.yt.innertube.models.Run
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

    @Test fun sevenOfficialAlbumsWithoutPageTypeAreAllPreserved() {
        // YouTube returns both annotated and unannotated MPRE browse cards.
        // Before the fix an unannotated release did not match isAlbum/isPlaylist.
        val releases = (1..7).map { number ->
            card("MUSIC_PAGE_TYPE_ALBUM", "MPREbAlbum$number").copy(
                navigationEndpoint = NavigationEndpoint(
                    browseEndpoint = BrowseEndpoint("MPREbAlbum$number"),
                ),
            )
        }
        val parsed = releases.mapNotNull(ArtistItemsPage::fromMusicTwoRowItemRenderer)
        assertEquals(7, parsed.size)
        assertEquals(7, parsed.filterIsInstance<AlbumItem>().size)
        assertEquals(7, parsed.distinctBy { it.id }.size)
    }

    @Test fun artistLandingCarouselKeepsSevenAlbumsAndItsMoreEndpoint() {
        val releaseCards = (1..7).map { n ->
            MusicCarouselShelfRenderer.Content(
                musicTwoRowItemRenderer = card("MUSIC_PAGE_TYPE_ALBUM", "MPREbAlbum$n")
                    .copy(navigationEndpoint = NavigationEndpoint(
                        browseEndpoint = BrowseEndpoint("MPREbAlbum$n")
                    )),
                musicResponsiveListItemRenderer = null,
                musicNavigationButtonRenderer = null,
            )
        }
        val carousel = MusicCarouselShelfRenderer(
            header = MusicCarouselShelfRenderer.Header(
                MusicCarouselShelfRenderer.Header.MusicCarouselShelfBasicHeaderRenderer(
                    strapline = null,
                    title = Runs(listOf(Run("Albums", NavigationEndpoint(
                        browseEndpoint = BrowseEndpoint("UCArtist", params = "album-selection")
                    )))),
                    thumbnail = null,
                    moreContentButton = null,
                )
            ),
            contents = releaseCards,
            itemSize = "COLLECTION_STYLE_ITEM_SIZE_MEDIUM",
            numItemsPerColumn = null,
        )
        val section = SectionListRenderer.Content(
            musicCarouselShelfRenderer = carousel,
            musicShelfRenderer = null,
            musicCardShelfRenderer = null,
            musicPlaylistShelfRenderer = null,
            musicDescriptionShelfRenderer = null,
            musicResponsiveHeaderRenderer = null,
            musicEditablePlaylistDetailHeaderRenderer = null,
            gridRenderer = null,
            itemSectionRenderer = null,
        )
        val parsed = ArtistPage.fromSectionListRendererContent(section)
        assertEquals(7, parsed?.items?.size)
        assertEquals("UCArtist", parsed?.moreEndpoint?.browseId)
        assertEquals("album-selection", parsed?.moreEndpoint?.params)
    }

    @Test fun responsiveAlbumWithoutTypeOrWatchButtonIsNotDiscarded() {
        val renderer = json.decodeFromString<MusicResponsiveListItemRenderer>(
            """{
                "badges": null, "fixedColumns": null,
                "flexColumns": [{
                    "musicResponsiveListItemFlexColumnRenderer": {
                        "text": {"runs": [{"text": "Forgotten album", "navigationEndpoint": null}]}
                    }
                }],
                "thumbnail": null, "menu": null, "playlistItemData": null,
                "overlay": null,
                "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREbOther"}}
            }""",
        )
        val parsed = ArtistItemsPage.fromMusicResponsiveListItemRenderer(renderer) as AlbumItem
        assertEquals("MPREbOther", parsed.id)
        assertEquals("MPREbOther", parsed.playlistId)
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
