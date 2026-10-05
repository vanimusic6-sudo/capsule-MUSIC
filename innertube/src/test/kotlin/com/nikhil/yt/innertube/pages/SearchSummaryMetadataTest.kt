package com.nikhil.yt.innertube.pages

import com.nikhil.yt.innertube.models.Artist
import com.nikhil.yt.innertube.models.BrowseEndpoint
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig
import com.nikhil.yt.innertube.models.NavigationEndpoint
import com.nikhil.yt.innertube.models.Run
import com.nikhil.yt.innertube.models.SongItem
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

    private fun artistEndpoint(id: String) = NavigationEndpoint(
        browseEndpoint = BrowseEndpoint(id, browseEndpointContextSupportedConfigs =
            BrowseEndpointContextSupportedConfigs(BrowseEndpointContextMusicConfig("MUSIC_PAGE_TYPE_ARTIST"))),
    )

    private fun albumEndpoint(id: String) = NavigationEndpoint(
        browseEndpoint = BrowseEndpoint(id, browseEndpointContextSupportedConfigs =
            BrowseEndpointContextSupportedConfigs(BrowseEndpointContextMusicConfig("MUSIC_PAGE_TYPE_ALBUM"))),
    )
}
