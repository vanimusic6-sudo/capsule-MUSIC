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
internal const val AUDIO_CDN_MAX_EXTRA_REQUESTS = 4

internal const val AUDIO_CDN_INITIAL_HEADERS_TIMEOUT_SECONDS = 6

/** Reject attacker-controlled Location URLs before copying a signed request's
 * Range, token, visitor headers or authorization to another network endpoint.
 * This matches the existing offline-stream HTTPS + YouTube-host policy.
 */
internal fun isSafeAudioCdnRedirect(url: HttpUrl): Boolean =
    try {
        AudioDownloadUrlPolicy.requireYouTubeMedia(url.toString())
        true
    } catch (_: IOException) {
        false
    }

internal fun isInitialAudioCdnRequest(request: Request): Boolean =
    request.header("Range")?.startsWith("bytes=0-") == true ||
        request.url.queryParameter("range")?.startsWith("0-") == true

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

/** Carry only transport headers across an origin boundary, including remembered redirects. */
internal fun Request.forCdnTarget(target: HttpUrl): Request {
    val builder = newBuilder().url(target)
    if (url.host != target.host || url.port != target.port || url.scheme != target.scheme) {
        for (name in headers.names()) {
            val safeReferer = name.equals("Referer", true) && header(name) in PUBLIC_REFERERS
            if (!safeReferer && name.lowercase(java.util.Locale.ROOT) !in FORWARD_HEADERS) builder.removeHeader(name)
        }
    }
    return builder.build()
}

private val PUBLIC_REFERERS = setOf("https://music.youtube.com/", "https://www.youtube.com/", "https://www.youtube-nocookie.com/")
private val FORWARD_HEADERS = setOf(
    "range", "if-range", "accept", "accept-encoding", "accept-language", "user-agent", "origin",
)

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
        val previouslyLearned = targets.shortcutFor(origin.url)
        val shortcut = previouslyLearned?.takeIf {
            isSafeAudioCdnRedirect(it) && it.queryParameter("range") == null &&
                (it.queryParameter("expire")?.toLongOrNull()?.let { expiry ->
                    expiry > System.currentTimeMillis() / 1000L + 15L
                } != false)
        }
        if (previouslyLearned != null && shortcut == null) targets.forget(origin.url)
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
        var request = if (shortcut != null) taggedOrigin.forCdnTarget(shortcut) else taggedOrigin
        var extraRequests = 0
        var stage = if (shortcut != null) "shortcut" else "original"
        val visited = mutableSetOf<HttpUrl>()
        var hop = 0

        while (true) {
            if (!visited.add(request.url)) throw IOException("Audio CDN redirect loop")
            hop += 1
            val sent = if (trace == null) request else request.newBuilder()
                .tag(AudioCdnRequestTrace::class.java, trace.copy(hop = hop, stage = stage))
                .build()
            // TLS/DNS failures can occur before OkHttp creates a network interceptor or socket,
            // so log the attempted hop here too; otherwise the decisive failed hop vanishes.
            val hopStartedAtNs = if (GlobalLog.isEnabled) System.nanoTime() else 0L
            val suspectGroup = health?.isUnresponsiveHost(sent.url.host) == true
            val initialOpen = isInitialAudioCdnRequest(sent)
            val openTimeoutSeconds = if (suspectGroup) CDN_HOST_SUSPECT_OPEN_TIMEOUT_SECONDS.toInt()
                else AUDIO_CDN_INITIAL_HEADERS_TIMEOUT_SECONDS
            val networkChain = if (suspectGroup || initialOpen) {
                // Only the requested hop's group is known to have failed a
                // previous no-byte open. A new client can be redirected back
                // onto that SAME route, so select the budget for each hop,
                // not just the signed URL's original host.
                chain
                    .withConnectTimeout(openTimeoutSeconds, TimeUnit.SECONDS)
                    .withReadTimeout(openTimeoutSeconds, TimeUnit.SECONDS)
            } else {
                chain
            }
            if (suspectGroup && GlobalLog.isEnabled) {
                Timber.tag("AudioCDN").i(
                    "cdn-suspect-open group=%s connectTimeoutSec=%d readTimeoutSec=%d",
                    googlevideoServerGroup(sent.url.host) ?: "unknown",
                    openTimeoutSeconds,
                    openTimeoutSeconds,
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

            // The short deadline covers connection/headers only. Once the CDN answers,
            // retain the client's normal read allowance for slow but progressing audio.
            if ((suspectGroup || initialOpen) && chain.readTimeoutMillis() > 0) {
                response.body?.source()?.timeout()?.timeout(chain.readTimeoutMillis().toLong(), TimeUnit.MILLISECONDS)
            }

            // HTTP errors are terminal for this open. Recovery belongs to
            // the service/downloader, not a hidden request inside a redirect.
            if (!response.isSuccessful && response.code !in REDIRECT_CODES) {
                if (response.code != 429) targets.forget(origin.url)
                return response
            }

            val target = try {
                redirectTargetOf(request, response)
            } catch (unsafe: IOException) {
                // Always close the 3xx body before aborting the original
                // request. No signed URL/header can reach an unsafe hop.
                response.close()
                throw unsafe
            }
            if (target == null) {
                // Where this landed is worth remembering only once it has actually served.
                if (response.isSuccessful) targets.remember(origin.url, request.url)
                return response
            }

            if (extraRequests >= AUDIO_CDN_MAX_EXTRA_REQUESTS) {
                response.close()
                throw IOException("Too many redirects for ${origin.url.host}")
            }

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
                    "follow",
                    googlevideoServerGroup(request.url.host) ?: "unknown",
                    googlevideoServerGroup(target.host) ?: "unknown",
                )
            }
            response.close()
            extraRequests += 1
            stage = "redirect-followed"
            // Follow the server's validated target, stripping authorization
            // headers from a cross-origin redirect. Never rewrite its query.
            request = request.forCdnTarget(target)
        }
    }

    private fun redirectTargetOf(
        request: Request,
        response: Response,
    ): HttpUrl? {
        if (response.code !in REDIRECT_CODES) return null
        val location = response.header("Location") ?: return null
        val candidate = request.url.resolve(location)
            ?: throw IOException("Audio CDN redirect address invalid")
        if (!isSafeAudioCdnRedirect(candidate)) {
            throw IOException("Audio CDN redirect refused: not an approved HTTPS media host")
        }
        return candidate
    }
}
