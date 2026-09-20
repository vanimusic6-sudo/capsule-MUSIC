package com.nikhil.yt.playback.audio

import com.nikhil.yt.utils.GlobalLog
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

internal fun audioCdnCrossHostCoalesced(
    requestHost: String,
    routeHost: String?,
    protocol: String?,
): Boolean =
    routeHost != null &&
        protocol.equals("h2", ignoreCase = true) &&
        !requestHost.equals(routeHost, ignoreCase = true)

/**
 * Which address family an address literal belongs to, without keeping the address.
 *
 * A googlevideo link is issued to the address that asked for it, and it carries that address in its
 * own query string. If the media request then leaves by a different one — which is routine on a
 * dual-stack mobile network, where one connection gets an A record and the next an AAAA — the CDN
 * refuses the link. That refusal is a bare 403 with an empty body, which is exactly what this app
 * has been receiving.
 *
 * Only the family is reported, never the address: "v4" and "v6" are enough to see a mismatch and
 * identify nobody.
 */
internal fun addressFamilyOf(address: String?): String =
    when {
        address.isNullOrBlank() -> "none"
        address.contains(':') -> "v6"
        address.count { it == '.' } == 3 -> "v4"
        else -> "other"
    }

/**
 * Whether a status is a refusal, and so worth seeing in a capture that carries no debug lines.
 *
 * A capture arrived with the debug level turned off. It held seven refusals and not one line
 * saying which server produced them, because the only line that names the responding host was a
 * debug one — and the failure line names the host in the link, which a redirect can make a
 * different machine entirely. Seven refusals with nowhere to pin them is not a diagnosis.
 */
internal fun isCdnRefusalStatus(code: Int): Boolean = code >= 400

/**
 * Whether a wire line is worth an unconditional info entry rather than a debug one.
 *
 * Refusals always are. So is the first request on a connection, and only the first: that is the
 * event the refusals need to be compared against, it happens once per socket rather than once per
 * chunk, and writing it at info is what makes the comparison survive a capture taken with debug
 * off — which is how the last several captures arrived. Everything after it on the same connection
 * is the ordinary case and stays at debug.
 */
internal fun isCdnWireWorthReporting(statusCode: Int, requestIndexOnConnection: Int): Boolean =
    isCdnRefusalStatus(statusCode) || requestIndexOnConnection == 1

/** Connection metadata around googlevideo requests; refusals are reported whatever the level. */
internal class AudioCdnConnectionDiagnosticInterceptor(
    private val ledger: AudioCdnConnectionLedger = AudioCdnConnectionLedger(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestHost = request.url.host
        val connection = chain.connection()
        val routeHost = connection?.route()?.address?.url?.host
        val protocol = connection?.protocol()?.toString()
        val coalesced = audioCdnCrossHostCoalesced(requestHost, routeHost, protocol)
        val connectionId = connection?.let(System::identityHashCode) ?: -1
        val linkFamily = addressFamilyOf(request.url.queryParameter("ip"))
        val socketFamily =
            addressFamilyOf(connection?.socket()?.inetAddress?.hostAddress)
        val use = ledger.record(connectionId, clock())

        return try {
            chain.proceed(request).also { response ->
                if (GlobalLog.isEnabled) {
                    // Spelled out three times rather than shared: Timber's lint check reads the
                    // format string at the call site, and a shared one it cannot see is a build
                    // error.
                    if (isCdnRefusalStatus(response.code)) {
                        Timber.tag("AudioCDN").w(
                            "cdn-wire host=%s routeHost=%s protocol=%s coalesced=%s conn=%d " +
                                "reqOnConn=%d connAgeMs=%d " +
                                "status=%d linkIssuedTo=%s requestLeftBy=%s sameFamily=%s",
                            requestHost,
                            routeHost ?: "unknown",
                            protocol ?: "unknown",
                            coalesced,
                            connectionId,
                            use.requestIndex,
                            use.ageMs,
                            response.code,
                            linkFamily,
                            socketFamily,
                            linkFamily == socketFamily,
                        )
                    } else if (isCdnWireWorthReporting(response.code, use.requestIndex)) {
                        Timber.tag("AudioCDN").i(
                            "cdn-wire host=%s routeHost=%s protocol=%s coalesced=%s conn=%d " +
                                "reqOnConn=%d connAgeMs=%d " +
                                "status=%d linkIssuedTo=%s requestLeftBy=%s sameFamily=%s",
                            requestHost,
                            routeHost ?: "unknown",
                            protocol ?: "unknown",
                            coalesced,
                            connectionId,
                            use.requestIndex,
                            use.ageMs,
                            response.code,
                            linkFamily,
                            socketFamily,
                            linkFamily == socketFamily,
                        )
                    } else {
                        Timber.tag("AudioCDN").d(
                            "cdn-wire host=%s routeHost=%s protocol=%s coalesced=%s conn=%d " +
                                "reqOnConn=%d connAgeMs=%d " +
                                "status=%d linkIssuedTo=%s requestLeftBy=%s sameFamily=%s",
                            requestHost,
                            routeHost ?: "unknown",
                            protocol ?: "unknown",
                            coalesced,
                            connectionId,
                            use.requestIndex,
                            use.ageMs,
                            response.code,
                            linkFamily,
                            socketFamily,
                            linkFamily == socketFamily,
                        )
                    }
                }
            }
        } catch (failure: IOException) {
            if (GlobalLog.isEnabled) {
                Timber.tag("AudioCDN").d(
                    "cdn-wire-iofail host=%s routeHost=%s protocol=%s coalesced=%s conn=%d " +
                        "reqOnConn=%d connAgeMs=%d type=%s",
                    requestHost,
                    routeHost ?: "unknown",
                    protocol ?: "unknown",
                    coalesced,
                    connectionId,
                    use.requestIndex,
                    use.ageMs,
                    failure::class.java.simpleName,
                )
            }
            throw failure
        }
    }
}
