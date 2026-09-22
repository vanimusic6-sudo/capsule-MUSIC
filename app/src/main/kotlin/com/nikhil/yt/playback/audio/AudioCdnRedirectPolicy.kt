package com.nikhil.yt.playback.audio

import com.nikhil.yt.utils.GlobalLog
import com.nikhil.yt.utils.isTransientClosedTlsHandshake
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

/**
 * How many times a cross-group redirect is answered by asking the issuing host again.
 *
 * Once. It was twice, and measuring 300 opens across a day of captures says the second one does
 * not pay for itself.
 *
 * The first decline is clearly worth it: of 68 opens that declined exactly once, 15 were then
 * served directly by the issuing host and 35 were offered a redirect inside the group instead —
 * 50 of 68 resolved, for the cost of one extra request.
 *
 * The second is a coin toss that costs a whole connection. Of 36 opens that declined twice, 18
 * converted, and the other 18 either ran out of budget and followed the cross-group redirect
 * anyway or were refused. A redirect arrives without a length and so ends its socket, and a
 * socket's first request is where every refusal in every capture has landed, so an extra request
 * here is an extra roll of exactly the dice this is trying to avoid.
 *
 * And what it is avoiding has stopped happening. The policy was written from a capture where
 * cross-group redirects were refused five times in seven; across the current captures every
 * cross-group redirect that was eventually followed was served — twelve for twelve. That is only
 * observable after two declines, so it may flatter itself, which is the reason for keeping one
 * decline rather than removing the policy outright.
 *
 * The one decline that is left was put on the same watch, and two captures have now scored it:
 *
 *                                       12:41   15:38   total
 *   issuing host served it directly         2       7       9
 *   issuing host offered the same hop       1       2       3
 *   issuing host refused it                 1       1       2
 *   outcome not readable                    0       1       1
 *                                          --      --      --
 *                                           4      11      15
 *
 * Nine of fifteen declines meant the issuing host simply served the slice itself, which is a
 * cross-group hop that never had to happen. The price was two refusals, and a refusal on this
 * path costs one more request and recovers — both of these did. Sixty percent for that is worth
 * paying, so the decline stays.
 *
 * The other half of the picture says why it is only one decline and not two: in the same two
 * captures every cross-group redirect that was actually followed was served, three for three.
 * There is no longer any evidence that leaving the group is dangerous, only that staying is
 * slightly cheaper. AudioCdnRedirectTargets carries most of that load anyway — 13 requests
 * across the two captures went straight to a remembered target.
 *
 * What would reopen this: declines converting below half, or a capture where following a
 * cross-group redirect is refused. Neither has happened yet.
 */
internal const val AUDIO_CDN_MAX_REISSUES = 1

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
internal class AudioCdnRedirectInterceptor(
    private val targets: AudioCdnRedirectTargets = AudioCdnRedirectTargets(),
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val origin = chain.request()

        /*
         * A track is read a megabyte at a time and every slice is built from the same link, so
         * without this every slice asks a question the first one already had answered. Going
         * straight to where the last slice landed removes a request, and with it a connection:
         * a redirect arrives with no length and no chunked framing, so it always costs the
         * socket, and a socket's first request is where every refusal in every capture has
         * landed.
         */
        val shortcut = targets.shortcutFor(origin.url)
        // Anchor diagnostics on the original signed link for every internal request,
        // including a cross-group reissue and a fallback after a refused shortcut.
        // Reusing raw `origin` used to drop this tag on exactly those paths, yielding
        // ref=unknown at the decisive 403 and hiding which redirected URL had failed.
        val trace = if (GlobalLog.isEnabled) {
            AudioCdnRequestTrace(
                originalUrl = origin.url,
                linkRef = AudioCdnLinkIdentity.ref(origin.url.toString()),
                flowId = AudioCdnTraceIds.next(),
            )
        } else null
        val taggedOrigin = origin.newBuilder().tag(AudioCdnRequestTrace::class.java, trace).build()
        var request = if (shortcut != null) taggedOrigin.newBuilder().url(shortcut).build() else taggedOrigin
        var usingShortcut = shortcut != null
        var extraRequests = 0
        var reissues = 0
        var stage = if (shortcut != null) "shortcut" else "original"
        var hop = 0

        while (true) {
            hop += 1
            val sent = if (trace == null) request else request.newBuilder()
                .tag(AudioCdnRequestTrace::class.java, trace.copy(hop = hop, stage = stage))
                .build()
            // TLS/DNS failures can occur before OkHttp creates a network interceptor or socket,
            // so log the attempted hop here too; otherwise the decisive failed hop vanishes.
            val hopStartedAtNs = if (GlobalLog.isEnabled) System.nanoTime() else 0L
            val response = try {
                chain.proceed(sent)
            } catch (failure: IOException) {
                if (GlobalLog.isEnabled) {
                    Timber.tag("AudioCDN").w(
                        "cdn-hop-failed flow=%d hop=%d stage=%s linkRef=%s effectiveRef=%s " +
                            "host=%s group=%s range=%s appHeaderRef=%s failureType=%s " +
                            "closedTls=%s elapsedMs=%d",
                        trace?.flowId ?: -1L,
                        hop,
                        stage,
                        trace?.linkRef ?: "unknown",
                        AudioCdnLinkIdentity.ref(sent.url.toString()),
                        sent.url.host,
                        googlevideoServerGroup(sent.url.host) ?: "unknown",
                        audioCdnSafeRange(sent.header("Range")),
                        audioCdnHeaderRef(sent),
                        failure::class.java.simpleName,
                        failure.isTransientClosedTlsHandshake(),
                        if (hopStartedAtNs > 0L) {
                            (System.nanoTime() - hopStartedAtNs).coerceAtLeast(0L) / 1_000_000L
                        } else -1L,
                    )
                }
                throw failure
            }

            /*
             * A remembered link that is refused is forgotten at once and the original asked
             * instead. Otherwise one stale target could turn a single refusal into every slice
             * of the track being refused, which is a far worse failure than the redirect this
             * is saving.
             */
            // A 429 on a shortcut MUST reach CapsuleAudioRequestInterceptor and the global
            // rate-limit breaker unchanged. All other HTTP errors retain the existing fallback
            // to the issuing URL, including a transient 5xx or a stale shortcut's 404.
            // Swallowing 429 behind that fallback concealed explicit back-off and sent another GET.
            if (usingShortcut && isCdnRefusalStatus(response.code) && response.code != 429) {
                response.close()
                targets.forget(origin.url)
                if (GlobalLog.isEnabled) {
                    Timber.tag("AudioCDN").w(
                        "cdn-shortcut-refused flow=%d hop=%d linkRef=%s host=%s code=%d; asking the original link again",
                        trace?.flowId ?: -1L,
                        hop,
                        trace?.linkRef ?: "unknown",
                        request.url.host,
                        response.code,
                    )
                }
                usingShortcut = false
                extraRequests += 1
                request = taggedOrigin
                stage = "original-after-shortcut"
                continue
            }

            val target = redirectTargetOf(request, response)
            if (target == null) {
                // Where this landed is worth remembering only once it has actually served.
                if (!isCdnRefusalStatus(response.code)) targets.remember(origin.url, request.url)
                return response
            }

            if (extraRequests >= AUDIO_CDN_MAX_EXTRA_REQUESTS) {
                response.close()
                throw IOException("Too many redirects for ${origin.url.host}")
            }

            val decline =
                reissues < AUDIO_CDN_MAX_REISSUES &&
                    isCrossGroupGooglevideoRedirect(request.url.host, target.host)
            if (GlobalLog.isEnabled) {
                Timber.tag("AudioCDN").i(
                    "cdn-redirect-decision flow=%d hop=%d linkRef=%s fromRef=%s toRef=%s " +
                        "status=%d decision=%s fromGroup=%s toGroup=%s",
                    trace?.flowId ?: -1L,
                    hop,
                    trace?.linkRef ?: "unknown",
                    AudioCdnLinkIdentity.ref(request.url.toString()),
                    AudioCdnLinkIdentity.ref(target.toString()),
                    response.code,
                    if (decline) "decline-once" else "follow",
                    googlevideoServerGroup(request.url.host) ?: "unknown",
                    googlevideoServerGroup(target.host) ?: "unknown",
                )
            }
            response.close()
            extraRequests += 1
            // Once the chain has moved off the remembered link, a later refusal belongs to
            // wherever it has got to, not to the shortcut, and must not un-remember it twice.
            usingShortcut = false

            request =
                if (decline) {
                    reissues += 1
                    stage = "original-after-decline"
                    if (GlobalLog.isEnabled) {
                        Timber.tag("AudioCDN").w(
                            "cdn-redirect-declined from=%s to=%s attempt=%d; asking the issuing host again",
                            googlevideoServerGroup(request.url.host) ?: "unknown",
                            googlevideoServerGroup(target.host) ?: "unknown",
                            reissues,
                        )
                    }
                    taggedOrigin
                } else {
                    stage = "redirect-followed"
                    /*
                     * A followed redirect is reported too, and at the same level as a declined one.
                     * A refusal that arrives after one of these came from a machine no other line
                     * in the capture names, and a capture that cannot say which server refused
                     * cannot say whether this policy is complete.
                     */
                    if (GlobalLog.isEnabled) {
                        /*
                         * A redirect inside the group is the ordinary case — ten a session, more
                         * than half of all redirect events — and at warning it buried the three
                         * lines a capture is actually read for. Leaving the group is still a
                         * warning, because that is the one this policy exists to have an opinion
                         * about.
                         */
                        val sameGroup =
                            !isCrossGroupGooglevideoRedirect(request.url.host, target.host)
                        if (sameGroup) {
                            Timber.tag("AudioCDN").d(
                                "cdn-redirect-followed from=%s to=%s sameGroup=%s",
                                request.url.host,
                                target.host,
                                true,
                            )
                        } else {
                            Timber.tag("AudioCDN").w(
                                "cdn-redirect-followed from=%s to=%s sameGroup=%s",
                                request.url.host,
                                target.host,
                                false,
                            )
                        }
                    }
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
