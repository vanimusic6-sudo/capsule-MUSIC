package com.nikhil.yt.innertube.pages

import com.nikhil.yt.innertube.models.Artist
import com.nikhil.yt.innertube.models.BrowseEndpoint
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig
import com.nikhil.yt.innertube.models.NavigationEndpoint
import com.nikhil.yt.innertube.models.MusicCardShelfRenderer
import com.nikhil.yt.innertube.models.MusicResponsiveListItemRenderer
import com.nikhil.yt.innertube.models.Run
import com.nikhil.yt.innertube.models.Runs
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.Thumbnail
import com.nikhil.yt.innertube.models.ThumbnailRenderer
import com.nikhil.yt.innertube.models.Thumbnails
import com.nikhil.yt.innertube.models.WatchEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSummaryMetadataTest {
    @Test fun `duration preceding artist is never interpreted as the artist`() {
        val metadata = listOf(
            Run("Song", null),
            Run("2:42", null),
            Run("Oliver Tree", artistEndpoint("UC_oliver")),
            Run(" • ", null),
            Run("Life Goes On", albumEndpoint("MPRE_album")),
        )

        assertEquals(listOf(Artist("Oliver Tree", "UC_oliver")), extractSearchSongArtists(metadata))
        assertEquals(162, extractSearchSongDuration(metadata))
        assertTrue(extractSearchSongArtists(listOf(Run("2:42", null))).isEmpty())
    }

    @Test fun `unlinked featured credit beside a linked artist is preserved`() {
        val metadata = listOf(
            Run("Song", null),
            Run("2:42", null),
            Run("Primary", artistEndpoint("UC_primary")),
            Run(" & ", null),
            Run("Featured Guest", null),
            Run(" • ", null),
            Run("Album", albumEndpoint("MPRE_album")),
        )

        assertEquals(
            listOf(
                Artist("Primary", "UC_primary"),
                Artist("Featured Guest", null),
            ),
            extractSearchSongArtists(metadata),
        )
    }

    @Test fun `russian conjunction keeps guest credit but is not an artist`() {
        val metadata = listOf(
            Run("Sub Urban", artistEndpoint("UC_suburban")),
            Run(" и ", null),
            Run("Bella Poarch", null),
        )

        assertEquals(
            listOf(
                Artist("Sub Urban", "UC_suburban"),
                Artist("Bella Poarch", null),
            ),
            extractSearchSongArtists(metadata),
        )
    }

    @Test fun `top result inherits missing artist from the same song in Songs`() {
        val artist = Artist("Oliver Tree", "UC_oliver")
        val top = SongItem(id = "song-id", title = "Life Goes On", artists = emptyList(),
            duration = 162, thumbnail = "cover")
        val song = top.copy(artists = listOf(artist))
        val summaries = enrichSearchSummaryArtists(listOf(
            SearchSummary("Top result", listOf(top)),
            SearchSummary("Songs", listOf(song)),
        ))

        assertEquals(listOf(artist), (summaries[0].items.single() as SongItem).artists)
        assertEquals(162, (summaries[0].items.single() as SongItem).duration)
    }

    @Test fun `top card artist fills songs even when Songs contains a different video id`() {
        val artist = Artist("Oliver Tree", "UC_oliver")
        val fallback = extractSearchCardArtists(artistCard())
        val top = SearchSummaryPage.fromMusicResponsiveListItemRenderer(
            songRenderer("top-video-id", "Waste My Time", listOf(Run("3:28", null))), fallback,
        ) as SongItem
        val separateSong = SongItem("songs-video-id", "Waste My Time", listOf(artist),
            duration = 208, thumbnail = "cover")
        val summaries = enrichSearchSummaryArtists(listOf(
            SearchSummary("Top result", listOf(top)),
            SearchSummary("Songs", listOf(separateSong)),
        ))

        assertEquals(listOf(artist), (summaries[0].items.single() as SongItem).artists)
        assertEquals(208, top.duration)
    }

    @Test fun `song artist in metadata takes priority over top card artist`() {
        val featured = Artist("Featured Artist", "UC_featured")
        val top = SearchSummaryPage.fromMusicResponsiveListItemRenderer(
            songRenderer("feature-id", "Duet", listOf(Run("Featured Artist", artistEndpoint("UC_featured")))),
            extractSearchCardArtists(artistCard()),
        ) as SongItem

        assertEquals(listOf(featured), top.artists)
    }

    @Test fun `video podcast watch endpoint survives without playlist metadata`() {
        val renderer = songRenderer("unused", "Podcast episode", emptyList()).copy(
            playlistItemData = null,
            navigationEndpoint = NavigationEndpoint(watchEndpoint = WatchEndpoint(videoId = "podcast-video-id")),
        )
        val item = SearchSummaryPage.fromMusicResponsiveListItemRenderer(renderer) as SongItem
        assertEquals("podcast-video-id", item.id)
        assertEquals("podcast-video-id", item.endpoint?.videoId)
    }

    private fun artistCard() = MusicCardShelfRenderer(
        title = Runs(listOf(Run("Oliver Tree", null))),
        subtitle = Runs(emptyList()),
        thumbnail = cover(),
        header = null,
        contents = null,
        buttons = emptyList(),
        onTap = artistEndpoint("UC_oliver"),
        subtitleBadges = null,
    )

    private fun songRenderer(id: String, title: String, metadata: List<Run>) =
        MusicResponsiveListItemRenderer(
            badges = null,
            fixedColumns = null,
            flexColumns = listOf(
                MusicResponsiveListItemRenderer.FlexColumn(
                    MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                        Runs(listOf(Run(title, null))),
                    ),
                ),
                MusicResponsiveListItemRenderer.FlexColumn(
                    MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                        Runs(metadata),
                    ),
                ),
            ),
            thumbnail = cover(),
            menu = null,
            playlistItemData = MusicResponsiveListItemRenderer.PlaylistItemData(null, id),
            overlay = null,
            navigationEndpoint = null,
        )

    private fun cover() = ThumbnailRenderer(
        musicThumbnailRenderer = ThumbnailRenderer.MusicThumbnailRenderer(
            Thumbnails(listOf(Thumbnail("cover", null, null))), null, null,
        ),
        musicAnimatedThumbnailRenderer = null,
        croppedSquareThumbnailRenderer = null,
    )

    private fun artistEndpoint(id: String) = NavigationEndpoint(
        browseEndpoint = BrowseEndpoint(id, browseEndpointContextSupportedConfigs =
            BrowseEndpointContextSupportedConfigs(BrowseEndpointContextMusicConfig("MUSIC_PAGE_TYPE_ARTIST"))),
    )

    private fun albumEndpoint(id: String) = NavigationEndpoint(
        browseEndpoint = BrowseEndpoint(id, browseEndpointContextSupportedConfigs =
            BrowseEndpointContextSupportedConfigs(BrowseEndpointContextMusicConfig("MUSIC_PAGE_TYPE_ALBUM"))),
    )
}
