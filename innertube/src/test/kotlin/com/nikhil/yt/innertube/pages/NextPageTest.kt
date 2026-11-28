package com.nikhil.yt.innertube.pages

import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.innertube.models.response.NextResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NextPageTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val endpoint = WatchEndpoint(videoId = "abcdefghijk")

    @Test fun standaloneVideoWithoutMusicQueueIsAValidEmptyResult() {
        val response = json.decodeFromString<NextResponse>("""{"contents":{}}""")
        val result = NextPage.fromResponse(response, endpoint)
        assertTrue(result.items.isEmpty())
        assertNull(result.currentIndex)
        assertNull(result.continuation)
        assertEquals(endpoint, result.endpoint)
    }

    @Test fun emptyTabsAreNotIndexedUnsafely() {
        val response = json.decodeFromString<NextResponse>("""{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[]}}}}}""")
        assertTrue(NextPage.fromResponse(response, endpoint).items.isEmpty())
        assertNull(NextPage.playlistPanel(response))
    }

    @Test fun relatedEndpointSurvivesMissingQueue() {
        val response = json.decodeFromString<NextResponse>("""{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{}},{"tabRenderer":{}},{"tabRenderer":{"endpoint":{"browseEndpoint":{"browseId":"MPRDtest"}}}}]}}}}}""")
        val result = NextPage.fromResponse(response, endpoint)
        assertTrue(result.items.isEmpty())
        assertEquals("MPRDtest", result.relatedEndpoint?.browseId)
    }
}
