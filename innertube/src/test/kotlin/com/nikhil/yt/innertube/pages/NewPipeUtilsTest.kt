package com.nikhil.yt.innertube.pages

import io.ktor.http.parseQueryString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ParsingException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class NewPipeUtilsTest {
    @Test
    fun `cipher defaults to signature parameter when sp is absent`() {
        val source = "https://example.googlevideo.com/videoplayback?expire=1&n=abc"
        val cipher = "s=hidden&url=${source.urlEncode()}"

        val result =
            NewPipeUtils.decipherSignatureCipher(cipher) { value ->
                assertEquals("hidden", value)
                "decoded"
            }
        val query = parseQueryString(URI(result).rawQuery)

        assertEquals("decoded", query["signature"])
        assertEquals("abc", query["n"])
    }

    @Test
    fun `cipher respects explicit signature parameter`() {
        val source = "https://example.googlevideo.com/videoplayback?expire=1"
        val cipher = "s=hidden&sp=sig&url=${source.urlEncode()}"

        val result =
            NewPipeUtils.decipherSignatureCipher(cipher) {
                "decoded"
            }
        val query = parseQueryString(URI(result).rawQuery)

        assertEquals("decoded", query["sig"])
    }

    @Test
    fun `ready signature does not invoke JavaScript resolver`() {
        val source = "https://example.googlevideo.com/videoplayback?expire=1"
        val cipher = "sig=ready&sp=lsig&url=${source.urlEncode()}"

        val result =
            NewPipeUtils.decipherSignatureCipher(cipher) {
                error("Resolver must not run for a ready signature")
            }
        val query = parseQueryString(URI(result).rawQuery)

        assertEquals("ready", query["lsig"])
    }

    @Test
    fun `empty decoded signature is rejected`() {
        val source = "https://example.googlevideo.com/videoplayback?expire=1"
        val cipher = "s=hidden&url=${source.urlEncode()}"

        assertThrows(ParsingException::class.java) {
            NewPipeUtils.decipherSignatureCipher(cipher) { "" }
        }
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.toString())
}
