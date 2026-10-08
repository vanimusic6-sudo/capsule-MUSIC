package com.nikhil.yt.innertube.pages

import com.nikhil.yt.innertube.models.Artist
import com.nikhil.yt.innertube.models.BrowseEndpoint
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig
import com.nikhil.yt.innertube.models.NavigationEndpoint
import com.nikhil.yt.innertube.models.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class PageHelperArtistCreditsTest {
    @Test
    fun `unlinked featured artist remains visible`() {
        val runs =
            listOf(
                Run("Primary", artistEndpoint("UC_primary")),
                Run(" & ", null),
                Run("Featured Guest", null),
            )

        assertEquals(
            listOf(
                Artist("Primary", "UC_primary"),
                Artist("Featured Guest", null),
            ),
            PageHelper.extractArtists(runs),
        )
    }

    @Test
    fun `russian conjunction between artists is not emitted as an artist`() {
        val runs =
            listOf(
                Run("Sub Urban", artistEndpoint("UC_suburban")),
                Run(" и ", null),
                Run("Bella Poarch", null),
            )

        assertEquals(
            listOf(
                Artist("Sub Urban", "UC_suburban"),
                Artist("Bella Poarch", null),
            ),
            PageHelper.extractArtists(runs),
        )
    }

    @Test
    fun `album endpoints and separators are not artists`() {
        val runs =
            listOf(
                Run("Primary", artistEndpoint("UC_primary")),
                Run(" • ", null),
                Run("Album", albumEndpoint("MPRE_album")),
            )

        assertEquals(
            listOf(Artist("Primary", "UC_primary")),
            PageHelper.extractArtists(runs),
        )
    }

    @Test
    fun `duplicate credit is emitted once`() {
        val runs =
            listOf(
                Run("Primary", artistEndpoint("UC_primary")),
                Run("Primary", artistEndpoint("UC_primary")),
            )

        assertEquals(1, PageHelper.extractArtists(runs).size)
    }

    private fun artistEndpoint(id: String) =
        NavigationEndpoint(
            browseEndpoint =
                BrowseEndpoint(
                    id,
                    browseEndpointContextSupportedConfigs =
                        BrowseEndpointContextSupportedConfigs(
                            BrowseEndpointContextMusicConfig("MUSIC_PAGE_TYPE_ARTIST"),
                        ),
                ),
        )

    private fun albumEndpoint(id: String) =
        NavigationEndpoint(
            browseEndpoint =
                BrowseEndpoint(
                    id,
                    browseEndpointContextSupportedConfigs =
                        BrowseEndpointContextSupportedConfigs(
                            BrowseEndpointContextMusicConfig("MUSIC_PAGE_TYPE_ALBUM"),
                        ),
                ),
        )
}
