package com.nikhil.yt.playback.audio

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val ORIGIN = "https://rr1---sn-ajixh5-55.googlevideo.com/videoplayback?id=abc"
private const val SAME_GROUP = "https://rr4---sn-ajixh5-55.googlevideo.com/videoplayback?id=abc"
private const val OTHER_GROUP = "https://rr5---sn-aj4g55-5o.googlevideo.com/videoplayback?id=abc"

/** A scripted chain: each proceed() takes the next reply and records what was asked for. */
private class ScriptedChain(
    private val request: Request,
    replies: List<(Request) -> Response>,
) : Interceptor.Chain {
    private val remaining = ArrayDeque(replies)
    val asked = mutableListOf<String>()

    override fun request(): Request = request

    override fun proceed(request: Request): Response {
        asked += request.url.toString()
        val next = remaining.removeFirstOrNull() ?: error("proceed() called more times than scripted")
        return next(request)
    }

    override fun connection(): Connection? = null

    override fun call(): Call = throw UnsupportedOperationException()

    override fun connectTimeoutMillis(): Int = 0

    override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

    override fun readTimeoutMillis(): Int = 0

    override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

    override fun writeTimeoutMillis(): Int = 0

    override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
}

private fun redirectTo(location: String): (Request) -> Response = { request ->
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(302)
        .message("Found")
        .header("Location", location)
        .body("".toResponseBody())
        .build()
}

private fun served(): (Request) -> Response = { request ->
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(206)
        .message("Partial Content")
        .body("audio".toResponseBody())
        .build()
}

private fun requestFor(url: String): Request = Request.Builder().url(url).build()

class AudioCdnRedirectPolicyTest {
    @Test
    fun `a googlevideo host names the group that signed the link`() {
        assertEquals("sn-ajixh5-55", googlevideoServerGroup("rr1---sn-ajixh5-55.googlevideo.com"))
        assertEquals("sn-aj4g55-5o", googlevideoServerGroup("rr5---sn-aj4g55-5o.googlevideo.com"))
        assertEquals("sn-ixh7rn76", googlevideoServerGroup("RR3---SN-IXH7RN76.googlevideo.com"))
    }

    @Test
    fun `a host with no group in it is not guessed at`() {
        assertNull(googlevideoServerGroup("redirector.googlevideo.com"))
        assertNull(googlevideoServerGroup("www.youtube.com"))
        assertNull(googlevideoServerGroup("sn-evil.example.com"))
        assertNull(googlevideoServerGroup(null))
    }

    @Test
    fun `a host that merely ends in something googlevideo-like is not one of ours`() {
        assertNull(googlevideoServerGroup("rr1---sn-ajixh5-55.googlevideo.com.evil.test"))
        assertNull(googlevideoServerGroup("notgooglevideo.com"))
    }

    @Test
    fun `swapping the replica stays inside the group`() {
        assertFalse(
            isCrossGroupGooglevideoRedirect(
                "rr1---sn-ajixh5-55.googlevideo.com",
                "rr4---sn-ajixh5-55.googlevideo.com",
            ),
        )
    }

    @Test
    fun `leaving the group is what the capture caught`() {
        assertTrue(
            isCrossGroupGooglevideoRedirect(
                "rr1---sn-ajixh5-55.googlevideo.com",
                "rr5---sn-aj4g55-5o.googlevideo.com",
            ),
        )
    }

    @Test
    fun `a redirect this cannot read is left alone`() {
        assertFalse(isCrossGroupGooglevideoRedirect("rr1---sn-a.googlevideo.com", "cdn.example.com"))
        assertFalse(isCrossGroupGooglevideoRedirect("redirector.googlevideo.com", OTHER_GROUP))
    }

    @Test
    fun `a redirect inside the group is followed`() {
        val chain = ScriptedChain(requestFor(ORIGIN), listOf(redirectTo(SAME_GROUP), served()))

        val response = AudioCdnRedirectInterceptor().intercept(chain)

        assertEquals(206, response.code)
        assertEquals(listOf(ORIGIN, SAME_GROUP), chain.asked)
    }

    @Test
    fun `a redirect out of the group sends us back to the issuing host`() {
        val chain = ScriptedChain(requestFor(ORIGIN), listOf(redirectTo(OTHER_GROUP), served()))

        val response = AudioCdnRedirectInterceptor().intercept(chain)

        assertEquals(206, response.code)
        assertEquals(listOf(ORIGIN, ORIGIN), chain.asked)
    }

    @Test
    fun `a host that keeps handing us off is taken at its word in the end`() {
        val chain =
            ScriptedChain(
                requestFor(ORIGIN),
                listOf(
                    redirectTo(OTHER_GROUP),
                    redirectTo(OTHER_GROUP),
                    redirectTo(OTHER_GROUP),
                    served(),
                ),
            )

        val response = AudioCdnRedirectInterceptor().intercept(chain)

        assertEquals(206, response.code)
        assertEquals(
            "two re-issues, then the redirect is followed",
            listOf(ORIGIN, ORIGIN, ORIGIN, OTHER_GROUP),
            chain.asked,
        )
    }

    @Test
    fun `a response that is not a redirect is handed straight back`() {
        val chain = ScriptedChain(requestFor(ORIGIN), listOf(served()))

        assertEquals(206, AudioCdnRedirectInterceptor().intercept(chain).code)
        assertEquals(listOf(ORIGIN), chain.asked)
    }

    @Test
    fun `a redirect with no location is not a redirect`() {
        val noLocation: (Request) -> Response = { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .body("".toResponseBody())
                .build()
        }
        val chain = ScriptedChain(requestFor(ORIGIN), listOf(noLocation))

        assertEquals(302, AudioCdnRedirectInterceptor().intercept(chain).code)
    }

    @Test
    fun `a circle of redirects ends in a failure rather than forever`() {
        val chain =
            ScriptedChain(
                requestFor(ORIGIN),
                List(AUDIO_CDN_MAX_EXTRA_REQUESTS + 1) { redirectTo(SAME_GROUP) },
            )

        val failure =
            runCatching { AudioCdnRedirectInterceptor().intercept(chain) }.exceptionOrNull()

        assertTrue("expected an IOException, got $failure", failure is IOException)
        assertEquals(AUDIO_CDN_MAX_EXTRA_REQUESTS + 1, chain.asked.size)
    }
}
