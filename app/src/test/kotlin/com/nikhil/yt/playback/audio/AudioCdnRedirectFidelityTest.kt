package com.nikhil.yt.playback.audio

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.nikhil.yt.utils.GlobalLog
import org.junit.Test
import java.util.concurrent.TimeUnit

/** A signed googlevideo link, with the query shapes the real ones carry. */
private const val SIGNED =
    "https://rr1---sn-ajixh5-55.googlevideo.com/videoplayback" +
        "?expire=1790000000&ei=abc&ip=203.0.113.7&id=o-AbC_dEf&itag=251&source=youtube" +
        "&mime=audio%2Fwebm&sig=AJfQ%2BdSwRQIgX%3D%3D&pot=MnQ_pO.token-1&c=WEB_REMIX&n=xYz-1"

private const val REDIRECTED =
    "https://rr1---sn-ajixh5-55.googlevideo.com/videoplayback" +
        "?expire=1790000000&ei=abc&ip=203.0.113.7&id=o-AbC_dEf&itag=251&source=youtube" +
        "&mime=audio%2Fwebm&sig=AJfQ%2BdSwRQIgX%3D%3D&pot=MnQ_pO.token-1&c=WEB_REMIX&n=xYz-1" +
        "&redirect_counter=1&cm2rm=sn-5hnekn7s&rrc=80"

private class RecordingChain(
    private val request: Request,
    replies: List<(Request) -> Response>,
) : Interceptor.Chain {
    private val remaining = ArrayDeque(replies)
    val sent = mutableListOf<Request>()

    override fun request(): Request = request

    override fun proceed(request: Request): Response {
        sent += request
        return (remaining.removeFirstOrNull() ?: error("unscripted proceed"))(request)
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

private fun found(location: String): (Request) -> Response = { request ->
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(302)
        .message("Found")
        .header("Location", location)
        .body("".toResponseBody())
        .build()
}

private fun partial(): (Request) -> Response = { request ->
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(206)
        .message("Partial Content")
        .body("audio".toResponseBody())
        .build()
}

/**
 * What survives a redirect, exactly.
 *
 * A signed googlevideo link is refused if anything about the request stops matching what the
 * signature covers, and the hop after a 302 is where a client gets that wrong: a header dropped,
 * a range re-applied, a query string rebuilt in a different order or re-encoded. These were
 * raised as the prime suspect for the refusals, and they are worth pinning here rather than
 * checked by reading a debug log once, because a log says what happened yesterday and a test
 * says what will happen tomorrow.
 */
class AudioCdnRedirectFidelityTest {
    private fun signedRequest(): Request =
        Request.Builder()
            .url(SIGNED)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("Range", "bytes=1048576-2097151")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

    @Test fun `diagnostic hop numbers identify the exact request across redirects`() {
        GlobalLog.setEnabled(true)
        try {
            val chain = RecordingChain(signedRequest(), listOf(found(REDIRECTED), partial()))
            AudioCdnRedirectInterceptor(AudioCdnRedirectTargets()).intercept(chain).close()

            val original = requireNotNull(chain.sent[0].tag(AudioCdnRequestTrace::class.java))
            val redirected = requireNotNull(chain.sent[1].tag(AudioCdnRequestTrace::class.java))
            assertTrue(original.flowId > 0L)
            assertEquals(original.flowId, redirected.flowId)
            assertEquals(original.linkRef, redirected.linkRef)
            assertEquals(1, original.hop)
            assertEquals(2, redirected.hop)
            assertEquals("original", original.stage)
            assertEquals("redirect-followed", redirected.stage)
            assertEquals(audioCdnHeaderRef(chain.sent[0]), audioCdnHeaderRef(chain.sent[1]))
            assertEquals("bytes=1048576-2097151", audioCdnSafeRange(chain.sent[1].header("Range")))
        } finally {
            GlobalLog.setEnabled(false)
        }
    }

    @Test fun `refused shortcut retains same link ID and identifies fallback to original`() {
        GlobalLog.setEnabled(true)
        try {
            val targets = AudioCdnRedirectTargets()
            val interceptor = AudioCdnRedirectInterceptor(targets)
            interceptor.intercept(
                RecordingChain(signedRequest(), listOf(found(REDIRECTED), partial())),
            ).close()

            val retry = RecordingChain(
                signedRequest(),
                listOf(
                    { request ->
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                            .code(403).message("Forbidden").body("".toResponseBody()).build()
                    },
                    partial(),
                ),
            )
            interceptor.intercept(retry).close()
            assertEquals(2, retry.sent.size)
            val shortcut = requireNotNull(retry.sent[0].tag(AudioCdnRequestTrace::class.java))
            val fallback = requireNotNull(retry.sent[1].tag(AudioCdnRequestTrace::class.java))
            assertEquals(shortcut.flowId, fallback.flowId)
            assertEquals(shortcut.linkRef, fallback.linkRef)
            assertEquals("shortcut", shortcut.stage)
            assertEquals("original-after-shortcut", fallback.stage)
            assertEquals(1, shortcut.hop)
            assertEquals(2, fallback.hop)
            assertEquals(signedRequest().url, retry.sent[1].url)
            assertEquals(audioCdnHeaderRef(retry.sent[0]), audioCdnHeaderRef(retry.sent[1]))
        } finally {
            GlobalLog.setEnabled(false)
        }
    }

    @Test fun `every header survives the hop after a redirect`() {
        val chain = RecordingChain(signedRequest(), listOf(found(REDIRECTED), partial()))

        AudioCdnRedirectInterceptor(AudioCdnRedirectTargets()).intercept(chain).close()

        assertEquals(2, chain.sent.size)
        assertEquals(
            "headers changed across the redirect",
            chain.sent[0].headers,
            chain.sent[1].headers,
        )
    }

    @Test fun `the Range header is carried over once and not re-applied`() {
        val chain = RecordingChain(signedRequest(), listOf(found(REDIRECTED), partial()))

        AudioCdnRedirectInterceptor(AudioCdnRedirectTargets()).intercept(chain).close()

        assertEquals(listOf("bytes=1048576-2097151"), chain.sent[1].headers.values("Range"))
    }

    @Test fun `the method is not changed by the redirect`() {
        val chain = RecordingChain(signedRequest(), listOf(found(REDIRECTED), partial()))

        AudioCdnRedirectInterceptor(AudioCdnRedirectTargets()).intercept(chain).close()

        assertEquals("GET", chain.sent[1].method)
    }

    @Test fun `the redirect target is used exactly as the server wrote it`() {
        val chain = RecordingChain(signedRequest(), listOf(found(REDIRECTED), partial()))

        AudioCdnRedirectInterceptor(AudioCdnRedirectTargets()).intercept(chain).close()

        // Byte for byte: no reordering, no re-encoding of the signature or the token, nothing
        // appended. Anything else changes what the signature covers.
        assertEquals(REDIRECTED, chain.sent[1].url.toString())
    }

    @Test fun `declining a redirect asks the original link exactly as it was`() {
        val crossGroup = SIGNED.replace("sn-ajixh5-55", "sn-aj4g55-5o")
        val chain = RecordingChain(signedRequest(), listOf(found(crossGroup), partial()))

        AudioCdnRedirectInterceptor(AudioCdnRedirectTargets()).intercept(chain).close()

        assertEquals(SIGNED, chain.sent[1].url.toString())
        assertEquals(chain.sent[0].headers, chain.sent[1].headers)
    }

    @Test fun `a remembered shortcut carries the same headers as the original would have`() {
        val targets = AudioCdnRedirectTargets()
        val interceptor = AudioCdnRedirectInterceptor(targets)
        interceptor.intercept(
            RecordingChain(signedRequest(), listOf(found(REDIRECTED), partial())),
        ).close()

        val next = RecordingChain(signedRequest(), listOf(partial()))
        interceptor.intercept(next).close()

        assertEquals(REDIRECTED, next.sent[0].url.toString())
        assertEquals(signedRequest().headers, next.sent[0].headers)
    }
}
