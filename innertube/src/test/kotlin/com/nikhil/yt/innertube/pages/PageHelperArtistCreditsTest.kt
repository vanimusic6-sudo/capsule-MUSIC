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
    fun `linked artist name matching a conjunction stays valid`() {
        assertEquals(
            listOf(Artist("And", "UC_and_artist")),
            PageHelper.extractArtists(
                listOf(Run("And", artistEndpoint("UC_and_artist"))),
            ),
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

    @Test
    fun `single canonical parser finds all song credits but not duration or album`() {
        val metadata = listOf(
            Run("Main", artistEndpoint("UC_main")),
            Run(" & ", null),
            Run("Guest", null),
            Run(" • ", null),
            Run("3:12", null),
            Run(" • ", null),
            Run("Album title", albumEndpoint("MPRE_album")),
            Run(" • ", null),
            Run("Second Guest", artistEndpoint("UC_second")),
        )
        assertEquals(
            listOf(
                Artist("Main", "UC_main"),
                Artist("Guest", null),
                Artist("Second Guest", "UC_second"),
            ),
            PageHelper.extractSongArtists(metadata),
        )
    }

    @Test
    fun `unlinked and linked copies of one guest become one navigable credit`() {
        val metadata = listOf(
            Run("Primary", artistEndpoint("UC_primary")),
            Run(" & ", null),
            Run("Guest", null),
            Run(" • ", null),
            Run("Guest", artistEndpoint("UC_guest")),
        )
        assertEquals(
            listOf(Artist("Primary", "UC_primary"), Artist("Guest", "UC_guest")),
            PageHelper.extractSongArtists(metadata),
        )
    }

    @Test
    fun `title or duration before a linked artist never becomes an artist`() {
        val metadata = listOf(
            Run("Track title", null),
            Run("2:42", null),
            Run("Oliver Tree", artistEndpoint("UC_oliver")),
        )
        assertEquals(
            listOf(Artist("Oliver Tree", "UC_oliver")),
            PageHelper.extractSongArtists(metadata),
        )
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
