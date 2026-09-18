package com.nikhil.yt.playback.audio

import com.nikhil.yt.utils.GlobalLog
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/** Statuses that carry a `Location` rather than a body. */
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

/**
 * How many extra requests one call may cost before the redirect chain is called a loop.
 *
 * Generous next to what is ever needed — a capture of seventy-six minutes never chained more than
 * two — and small enough that a server bouncing us in circles fails in a second rather than
 * holding a slice of audio hostage.
 */
internal const val AUDIO_CDN_MAX_EXTRA_REQUESTS = 8

/** How many times a cross-group redirect is answered by asking the issuing host again. */
internal const val AUDIO_CDN_MAX_REISSUES = 2

/**
 * The server group inside a googlevideo host name, or null if there is none to read.
 *
 * Hosts look like `rr1---sn-ajixh5-55.googlevideo.com`: `rr1` is one replica among several that
 * serve the same content, and `sn-ajixh5-55` names the group they belong to. The replica number is
 * interchangeable; the group is not, and it is the group a signed link is issued against.
 */
internal fun googlevideoServerGroup(host: String?): String? {
    if (host == null) return null
    val lower = host.lowercase()
    if (!lower.endsWith(".googlevideo.com")) return null
    val label = lower.substringBefore('.')
    return label
        .split("---")
        .firstOrNull { it.startsWith("sn-") && it.length > "sn-".length }
}

/**
 * Whether following this redirect would carry a signed link to a host it was not issued for.
 *
 * A googlevideo link is signed for the group that handed it out. A redirect that stays inside that
 * group is a replica swap and the signature still holds; one that leaves it hands our credentials
 * to a server that has no reason to honour them, and mostly does not.
 *
 * Anything this cannot read — a host that is not googlevideo, a name with no group in it — is left
 * alone. A policy that does not understand a redirect has no business declining it.
 */
internal fun isCrossGroupGooglevideoRedirect(
    fromHost: String?,
    toHost: String?,
): Boolean {
    val from = googlevideoServerGroup(fromHost) ?: return false
    val to = googlevideoServerGroup(toHost) ?: return false
    return from != to
}

/**
 * Keeps a signed media request on the host its link was issued for.
 *
 * A seventy-six minute capture failed to open a slice eleven times. Every one of the eleven — five
 * refusals and six reads that timed out after eleven seconds — happened on the request *after* a
 * redirect, and not one happened on a direct request:
 *
 *   asked the issuing host            45 of 45 served
 *   redirected inside the group        9 of  9 served
 *   redirected to another group        2 of  7 served, plus six that never answered
 *
 * So the refusal was never about the link, the client, the address family or the pace of the
 * requests. It was about where the request ended up: `rr5---sn-aj4g55-5o` turned away two requests
 * out of three that `rr1---sn-ajixh5-55` had sent it, with an empty body and no explanation, and
 * that single fact accounts for every interruption in the capture.
 *
 * The answer is not to retry harder. It is to not go there: when a redirect would leave the group,
 * the issuing host is asked again instead, which in the same capture answered directly five times
 * out of six. A redirect inside the group is followed exactly as before, because that one works.
 *
 * Twice is the limit, after which the redirect is followed after all. Declining forever would turn
 * a host that genuinely handed its work over into a track that never plays, and two thirds odds
 * are still much better than none.
 */
internal class AudioCdnRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val origin = chain.request()
        var request = origin
        var extraRequests = 0
        var reissues = 0

        while (true) {
            val response = chain.proceed(request)
            val target = redirectTargetOf(request, response) ?: return response

            if (extraRequests >= AUDIO_CDN_MAX_EXTRA_REQUESTS) {
                response.close()
                throw IOException("Too many redirects for ${origin.url.host}")
            }

            val decline =
                reissues < AUDIO_CDN_MAX_REISSUES &&
                    isCrossGroupGooglevideoRedirect(request.url.host, target.host)
            response.close()
            extraRequests += 1

            request =
                if (decline) {
                    reissues += 1
                    if (GlobalLog.isEnabled) {
                        Timber.tag("AudioCDN").w(
                            "cdn-redirect-declined from=%s to=%s attempt=%d; asking the issuing host again",
                            googlevideoServerGroup(request.url.host) ?: "unknown",
                            googlevideoServerGroup(target.host) ?: "unknown",
                            reissues,
                        )
                    }
                    origin
                } else {
                    request.newBuilder().url(target).build()
                }
        }
    }

    private fun redirectTargetOf(
        request: Request,
        response: Response,
    ): HttpUrl? {
        if (response.code !in REDIRECT_CODES) return null
        val location = response.header("Location") ?: return null
        return request.url.resolve(location)
    }
}
