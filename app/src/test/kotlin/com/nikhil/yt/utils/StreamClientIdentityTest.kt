package com.nikhil.yt.utils

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun mediaRequest(client: String, vararg headers: Pair<String, String>): Request =
    Request.Builder()
        .url("https://rr1---sn-ajixh5-55.googlevideo.com/videoplayback?itag=251&c=$client")
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .build()

/**
 * The request to the CDN must look like the client the link was issued to.
 *
 * A signed googlevideo link is minted for a particular client, and a media request that presents
 * a different identity than the /player call that obtained it is the sort of inconsistency a CDN
 * can act on. This was raised as a suspect for the refusals; the headers are derived from the
 * `c=` parameter carried in the link itself, so they cannot drift away from it, and that is the
 * property worth holding still.
 */
class StreamClientIdentityTest {
    @Test fun `the user agent is taken from the client named in the link`() {
        val remix = StreamClientUtils.withFallbackHeaders(mediaRequest("WEB_REMIX"))
        val tv = StreamClientUtils.withFallbackHeaders(mediaRequest("TVHTML5"))

        assertEquals(StreamClientUtils.resolveUserAgent("WEB_REMIX"), remix.header("User-Agent"))
        assertNotEquals(remix.header("User-Agent"), tv.header("User-Agent"))
    }

    @Test fun `a WEB_REMIX link is presented as YouTube Music`() {
        val request = StreamClientUtils.withFallbackHeaders(mediaRequest("WEB_REMIX"))

        assertTrue(request.header("Origin").orEmpty().contains("music.youtube.com"))
        assertTrue(request.header("Referer").orEmpty().contains("music.youtube.com"))
    }

    @Test fun `headers the extractor already set are authoritative and left alone`() {
        // The extractor knows the exact client version it used; a default must not overwrite it.
        val request =
            StreamClientUtils.withFallbackHeaders(
                mediaRequest(
                    "WEB_REMIX",
                    "User-Agent" to "the extractor's own agent",
                    "Origin" to "https://example.invalid",
                ),
            )

        assertEquals("the extractor's own agent", request.header("User-Agent"))
        assertEquals("https://example.invalid", request.header("Origin"))
    }

    @Test fun `the same client always resolves to the same identity`() {
        // Two call sites deriving an identity differently is the failure this rules out.
        repeat(3) {
            assertEquals(
                StreamClientUtils.withFallbackHeaders(mediaRequest("WEB_REMIX")).header("User-Agent"),
                StreamClientUtils.resolveUserAgent("WEB_REMIX"),
            )
        }
    }

    @Test fun `a non-media host is left untouched`() {
        val request =
            Request.Builder().url("https://example.invalid/anything").build()

        assertEquals(request, StreamClientUtils.withFallbackHeaders(request))
    }

    @Test fun `client matching ignores case, as the query parameter may`() {
        assertEquals(
            StreamClientUtils.resolveUserAgent("WEB_REMIX"),
            StreamClientUtils.resolveUserAgent("web_remix"),
        )
    }
}
