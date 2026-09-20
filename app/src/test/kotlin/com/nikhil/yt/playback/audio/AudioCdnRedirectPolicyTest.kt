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
    fun `a refusal is reported whether or not the capture carries debug lines`() {
        assertTrue(isCdnRefusalStatus(403))
        assertTrue(isCdnRefusalStatus(410))
        assertTrue(isCdnRefusalStatus(500))
        assertFalse(isCdnRefusalStatus(206))
        assertFalse(isCdnRefusalStatus(302))
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

private fun refused(code: Int = 403): (Request) -> Response = { request ->
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Forbidden")
        .body("".toResponseBody())
        .build()
}

/**
 * Going straight to where the last slice landed.
 *
 * A track is read a megabyte at a time and every slice is built from the same link, so each one
 * used to re-ask a question the first slice had already had answered. The redirect that answers
 * it is never free: it carries no length and no chunked framing, so it ends the connection, and a
 * connection's first request is where every refusal in every capture has landed.
 */
class AudioCdnRedirectShortcutTest {
    @Test
    fun `the second slice goes straight to where the first one landed`() {
        val targets = AudioCdnRedirectTargets()
        val interceptor = AudioCdnRedirectInterceptor(targets)

        val first = ScriptedChain(requestFor(ORIGIN), listOf(redirectTo(SAME_GROUP), served()))
        interceptor.intercept(first).close()
        assertEquals(listOf(ORIGIN, SAME_GROUP), first.asked)

        val second = ScriptedChain(requestFor(ORIGIN), listOf(served()))
        interceptor.intercept(second).close()

        assertEquals("the redirect was paid for twice", listOf(SAME_GROUP), second.asked)
    }

    @Test
    fun `a link that never redirected is not rewritten`() {
        val targets = AudioCdnRedirectTargets()
        val interceptor = AudioCdnRedirectInterceptor(targets)

        val first = ScriptedChain(requestFor(ORIGIN), listOf(served()))
        interceptor.intercept(first).close()

        val second = ScriptedChain(requestFor(ORIGIN), listOf(served()))
        interceptor.intercept(second).close()

        assertEquals(listOf(ORIGIN), second.asked)
    }

    @Test
    fun `a refused shortcut is dropped and the original asked instead`() {
        val targets = AudioCdnRedirectTargets()
        val interceptor = AudioCdnRedirectInterceptor(targets)

        val first = ScriptedChain(requestFor(ORIGIN), listOf(redirectTo(SAME_GROUP), served()))
        interceptor.intercept(first).close()

        // The remembered link has gone stale. The slice must still be served.
        val second = ScriptedChain(requestFor(ORIGIN), listOf(refused(), served()))
        val response = interceptor.intercept(second)
        response.close()

        assertEquals(206, response.code)
        assertEquals(listOf(SAME_GROUP, ORIGIN), second.asked)
    }

    @Test
    fun `a shortcut that has failed once is not tried again`() {
        val targets = AudioCdnRedirectTargets()
        val interceptor = AudioCdnRedirectInterceptor(targets)

        val first = ScriptedChain(requestFor(ORIGIN), listOf(redirectTo(SAME_GROUP), served()))
        interceptor.intercept(first).close()
        val second = ScriptedChain(requestFor(ORIGIN), listOf(refused(), served()))
        interceptor.intercept(second).close()

        // One stale target must never turn a single refusal into every slice being refused.
        val third = ScriptedChain(requestFor(ORIGIN), listOf(served()))
        interceptor.intercept(third).close()

        assertEquals(listOf(ORIGIN), third.asked)
    }

    @Test
    fun `a refusal on the original link is still handed back to the caller`() {
        val targets = AudioCdnRedirectTargets()
        val interceptor = AudioCdnRedirectInterceptor(targets)

        val chain = ScriptedChain(requestFor(ORIGIN), listOf(refused()))
        val response = interceptor.intercept(chain)
        response.close()

        // Nothing was remembered, so nothing is retried: the refusal belongs to the caller's
        // own bounded retry, which counts attempts and gives up.
        assertEquals(403, response.code)
        assertEquals(listOf(ORIGIN), chain.asked)
    }

    @Test
    fun `a refused link is never remembered`() {
        val targets = AudioCdnRedirectTargets()
        val interceptor = AudioCdnRedirectInterceptor(targets)

        val first = ScriptedChain(requestFor(ORIGIN), listOf(redirectTo(SAME_GROUP), refused()))
        interceptor.intercept(first).close()

        val second = ScriptedChain(requestFor(ORIGIN), listOf(served()))
        interceptor.intercept(second).close()

        assertEquals(listOf(ORIGIN), second.asked)
    }

    @Test
    fun `declining a cross-group redirect still asks the true original`() {
        val targets = AudioCdnRedirectTargets()
        val interceptor = AudioCdnRedirectInterceptor(targets)

        val first = ScriptedChain(requestFor(ORIGIN), listOf(redirectTo(SAME_GROUP), served()))
        interceptor.intercept(first).close()

        // Now the remembered link starts sending us out of the group.
        val second =
            ScriptedChain(requestFor(ORIGIN), listOf(redirectTo(OTHER_GROUP), served()))
        interceptor.intercept(second).close()

        assertEquals(listOf(SAME_GROUP, ORIGIN), second.asked)
    }

    @Test
    fun `a route change forgets every remembered link`() {
        val targets = AudioCdnRedirectTargets()
        val interceptor = AudioCdnRedirectInterceptor(targets)

        val first = ScriptedChain(requestFor(ORIGIN), listOf(redirectTo(SAME_GROUP), served()))
        interceptor.intercept(first).close()

        targets.forgetAll()

        val second = ScriptedChain(requestFor(ORIGIN), listOf(served()))
        interceptor.intercept(second).close()

        assertEquals(listOf(ORIGIN), second.asked)
    }

    @Test
    fun `a redirect that points at itself is not worth remembering`() {
        val targets = AudioCdnRedirectTargets()
        targets.remember(requestFor(ORIGIN).url, requestFor(ORIGIN).url)

        assertNull(targets.shortcutFor(requestFor(ORIGIN).url))
    }

    @Test
    fun `the memory cannot grow without bound`() {
        val targets = AudioCdnRedirectTargets(capacity = 4)
        for (n in 1..64) {
            targets.remember(
                requestFor("https://rr1---sn-ajixh5-55.googlevideo.com/videoplayback?id=$n").url,
                requestFor(SAME_GROUP).url,
            )
        }

        assertNull(
            targets.shortcutFor(
                requestFor("https://rr1---sn-ajixh5-55.googlevideo.com/videoplayback?id=1").url,
            ),
        )
        assertEquals(
            requestFor(SAME_GROUP).url,
            targets.shortcutFor(
                requestFor("https://rr1---sn-ajixh5-55.googlevideo.com/videoplayback?id=64").url,
            ),
        )
    }
}
