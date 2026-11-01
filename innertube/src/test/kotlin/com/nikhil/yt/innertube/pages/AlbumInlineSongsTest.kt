package com.nikhil.yt.innertube.pages

import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.models.Artist
import com.nikhil.yt.innertube.models.response.BrowseResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class AlbumInlineSongsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val album = AlbumItem(browseId = "album-id", playlistId = "playlist-id", title = "Album", artists = listOf(Artist("Artist", "artist-id")), year = 2026, thumbnail = "cover")
    private val row = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"track-id"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track"}]}}}]}}"""
    private fun response(shelf: String) = json.decodeFromString<BrowseResponse>("""
        {"responseContext":{},"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[],
        "secondaryContents":{"sectionListRenderer":{"contents":[$shelf]}}}}}
    """)

    @Test fun completeTracksKeepAlbumCreditsWithoutASecondBrowse() {
        val result = AlbumPage.completeInlineSongs(response("""{"musicShelfRenderer":{"contents":[$row]}}"""), album)!!
        assertEquals(listOf("track-id"), result.map { it.id })
        assertEquals(album.artists, result.single().artists)
        assertEquals("Album", result.single().album?.name)
    }

    @Test fun anExplicitlyEmptyShelfIsCompleteButAMissingShelfIsNot() {
        assertEquals(emptyList<Any>(), AlbumPage.completeInlineSongs(response("""{"musicShelfRenderer":{"contents":[]}}"""), album))
        assertNull(AlbumPage.completeInlineSongs(response("{}"), album))
    }

    @Test fun bothContinuationEncodingsPreventTruncation() {
        for (kind in listOf("musicShelfRenderer", "musicPlaylistShelfRenderer")) {
            val legacy = """{"$kind":{"contents":[$row],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}"""
            val inline = """{"$kind":{"contents":[$row,{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}]}}"""
            assertNull(AlbumPage.completeInlineSongs(response(legacy), album))
            assertNull(AlbumPage.completeInlineSongs(response(inline), album))
        }
    }
}
