package com.nikhil.yt.playback.audio

import com.nikhil.yt.utils.GlobalLog
import com.nikhil.yt.utils.isTransientClosedTlsHandshake
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

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

/** One reissue is reserved for a destination already observed timing out on this network.
 * Healthy server-directed redirects are followed immediately; declining every first 302
 * added 0.8–1.0s and a new socket in the 2026-11-02 device capture. */
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

/** Follow server redirects, retain successful destinations, and isolate known failed hops. */
internal class AudioCdnRedirectInterceptor(
    private val targets: AudioCdnRedirectTargets = AudioCdnRedirectTargets(),
    private val health: AudioCdnHostHealth? = null,
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
            val suspectGroup = health?.isUnresponsiveHost(sent.url.host) == true
            val networkChain = if (suspectGroup) {
                // Only the requested hop's group is known to have failed a
                // previous no-byte open. A new client can be redirected back
                // onto that SAME route, so select the budget for each hop,
                // not just the signed URL's original host.
                chain
                    .withConnectTimeout(CDN_HOST_SUSPECT_OPEN_TIMEOUT_SECONDS.toInt(), TimeUnit.SECONDS)
                    .withReadTimeout(CDN_HOST_SUSPECT_OPEN_TIMEOUT_SECONDS.toInt(), TimeUnit.SECONDS)
            } else {
                chain
            }
            if (suspectGroup && GlobalLog.isEnabled) {
                Timber.tag("AudioCDN").i(
                    "cdn-suspect-open group=%s connectTimeoutSec=%d readTimeoutSec=%d",
                    googlevideoServerGroup(sent.url.host) ?: "unknown",
                    CDN_HOST_SUSPECT_OPEN_TIMEOUT_SECONDS,
                    CDN_HOST_SUSPECT_OPEN_TIMEOUT_SECONDS,
                )
            }
            val response = try {
                networkChain.proceed(sent)
            } catch (failure: IOException) {
                if (GlobalLog.isEnabled) {
                    Timber.tag("AudioCDN").w(
                        "cdn-hop-failed flow=%d hop=%d stage=%s linkRef=%s effectiveRef=%s " +
                            "host=%s group=%s range=%s bakedRange=%s appHeaderRef=%s failureType=%s " +
                            "closedTls=%s elapsedMs=%d",
                        trace?.flowId ?: -1L,
                        hop,
                        stage,
                        trace?.linkRef ?: "unknown",
                        AudioCdnLinkIdentity.ref(sent.url.toString()),
                        sent.url.host,
                        googlevideoServerGroup(sent.url.host) ?: "unknown",
                        audioCdnSafeRange(sent.header("Range")),
                        audioCdnSafeBakedRange(sent.url.queryParameter("range")),
                        audioCdnHeaderRef(sent),
                        failure::class.java.simpleName,
                        failure.isTransientClosedTlsHandshake(),
                        if (hopStartedAtNs > 0L) {
                            (System.nanoTime() - hopStartedAtNs).coerceAtLeast(0L) / 1_000_000L
                        } else -1L,
                    )
                }
                // The issuing server may have replied 302 successfully: attribute
                // a failed cross-group hop to the actual destination, not the
                // original link's group. Preserve the original IOException as
                // the cause for the existing timeout/cancellation classifier.
                if (isCrossGroupGooglevideoRedirect(origin.url.host, sent.url.host) &&
                    !failure.isExpectedAudioCdnInterruption()
                ) {
                    throw AudioCdnFailedHopException(sent.url.host, failure)
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
                    health?.isUnresponsiveHost(target.host) == true &&
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
