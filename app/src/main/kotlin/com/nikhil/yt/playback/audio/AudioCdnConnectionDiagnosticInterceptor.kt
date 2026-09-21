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
 * The URL's issued address, the local socket and the remote endpoint have different meanings.
 * None of the socket fields proves which public exit address a VPN or NAT used. Never log the
 * addresses or claim an IP match from two address-family labels.
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
 * Whether a response leaves its connection fit to be used again.
 *
 * Not a matter of which machine the connection reached, either: each of these hosts resolves to a
 * single address, so a reconnect goes back to the same server. The refusal is something that
 * server decides about a connection, not a lottery between replicas -- which is why a retry down
 * a fresh socket to the identical address is served.
 *
 * A capture showed forty-nine googlevideo requests spread over thirty connections, none of them
 * older than four and a half seconds, and every refusal landing on a connection's very first
 * request. So what decides how many first requests there are decides how much exposure there is,
 * and for HTTP/1.1 that is whether the response told OkHttp where its body ends. A reply that
 * declares a length or arrives chunked can be followed by another on the same socket; one that
 * delimits its body by closing the connection cannot, and neither can one that asks to close.
 * Redirects are the suspects: each one here was followed by a new connection to the same host.
 *
 * Only the shape of the framing is reported, never a header's contents.
 */
internal fun cdnResponseKeepsConnectionUsable(
    connectionHeader: String?,
    contentLength: Long,
    transferEncoding: String?,
): Boolean {
    if (connectionHeader?.contains("close", ignoreCase = true) == true) return false
    if (transferEncoding?.contains("chunked", ignoreCase = true) == true) return true
    return contentLength >= 0L
}

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
        val remoteFamily =
            addressFamilyOf(connection?.socket()?.inetAddress?.hostAddress)
        val localFamily = addressFamilyOf(connection?.socket()?.localAddress?.hostAddress)
        val proxyType = connection?.route()?.proxy?.type()?.name ?: "unknown"
        val use = ledger.record(connectionId, clock(), requestHost)

        return try {
            chain.proceed(request).also { response ->
                // Counted whatever the log level: a tally read once per capture is worth nothing
                // if it only counts the sessions somebody remembered to turn logging on for.
                AudioCdnSessionStats.recordResponse(response.code, use.requestIndex)
                AudioCdnSessionStats.recordProtocol(protocol)
                if (GlobalLog.isEnabled &&
                    (isCdnRefusalStatus(response.code) || use.requestIndex == 1)
                ) {
                    val trace = request.tag(AudioCdnRequestTrace::class.java)
                    val phase = if (trace?.originalUrl == request.url) "original" else "redirect-or-shortcut"
                    // This ref is salted in memory; it cannot reveal a signed URL, token or
                    // visitor identity from a shared diagnostic export. It joins the same link's
                    // first rejected request, redirected hop and later successful retry.
                    Timber.tag("AudioCDN").i(
                        "cdn-link-route ref=%s phase=%s status=%d originalGroup=%s servedGroup=%s " +
                            "rangeHeader=%s cookieHeader=%s originHeader=%s refererHeader=%s",
                        trace?.linkRef ?: "unknown",
                        phase,
                        response.code,
                        googlevideoServerGroup(trace?.originalUrl?.host) ?: "unknown",
                        googlevideoServerGroup(request.url.host) ?: "unknown",
                        request.header("Range") != null,
                        request.header("Cookie") != null,
                        request.header("Origin") != null,
                        request.header("Referer") != null,
                    )
                }
                /*
                 * A link is issued to the address that asked for it and carries that address in
                 * its own query string, so leaving by a different family is close to a
                 * guaranteed refusal. Every capture so far has been v4 to v4, which is why this
                 * has only ever been three fields to compare by eye — and a condition nobody is
                 * comparing is a condition nobody will notice. It gets its own line, above the
                 * level captures are usually taken at, the first time it ever happens.
                 */
                if (GlobalLog.isEnabled && linkFamily != "none" && linkFamily != remoteFamily) {
                    Timber.tag("AudioCDN").w(
                        "cdn-family-mismatch host=%s linkIssuedFamily=%s remoteAddressFamily=%s " +
                            "localAddressFamily=%s status=%d",
                        requestHost,
                        linkFamily,
                        remoteFamily,
                        localFamily,
                        response.code,
                    )
                }
                if (GlobalLog.isEnabled) {
                    val reusable =
                        cdnResponseKeepsConnectionUsable(
                            connectionHeader = response.header("Connection"),
                            contentLength = response.body?.contentLength() ?: -1L,
                            transferEncoding = response.header("Transfer-Encoding"),
                        )
                    // Spelled out three times rather than shared: Timber's lint check reads the
                    // format string at the call site, and a shared one it cannot see is a build
                    // error.
                    if (isCdnRefusalStatus(response.code)) {
                        Timber.tag("AudioCDN").w(
                            "cdn-wire host=%s routeHost=%s protocol=%s coalesced=%s conn=%d " +
                                "reqOnConn=%d connAgeMs=%d prevConnReqs=%d reusable=%s " +
                                "status=%d linkIssuedFamily=%s remoteAddressFamily=%s " +
                                "localAddressFamily=%s proxyType=%s",
                            requestHost,
                            routeHost ?: "unknown",
                            protocol ?: "unknown",
                            coalesced,
                            connectionId,
                            use.requestIndex,
                            use.ageMs,
                            use.previousRequestsToHost,
                            reusable,
                            response.code,
                            linkFamily,
                            remoteFamily,
                            localFamily,
                            proxyType,
                        )
                    } else if (isCdnWireWorthReporting(response.code, use.requestIndex)) {
                        Timber.tag("AudioCDN").i(
                            "cdn-wire host=%s routeHost=%s protocol=%s coalesced=%s conn=%d " +
                                "reqOnConn=%d connAgeMs=%d prevConnReqs=%d reusable=%s " +
                                "status=%d linkIssuedFamily=%s remoteAddressFamily=%s " +
                                "localAddressFamily=%s proxyType=%s",
                            requestHost,
                            routeHost ?: "unknown",
                            protocol ?: "unknown",
                            coalesced,
                            connectionId,
                            use.requestIndex,
                            use.ageMs,
                            use.previousRequestsToHost,
                            reusable,
                            response.code,
                            linkFamily,
                            remoteFamily,
                            localFamily,
                            proxyType,
                        )
                    } else {
                        Timber.tag("AudioCDN").d(
                            "cdn-wire host=%s routeHost=%s protocol=%s coalesced=%s conn=%d " +
                                "reqOnConn=%d connAgeMs=%d prevConnReqs=%d reusable=%s " +
                                "status=%d linkIssuedFamily=%s remoteAddressFamily=%s " +
                                "localAddressFamily=%s proxyType=%s",
                            requestHost,
                            routeHost ?: "unknown",
                            protocol ?: "unknown",
                            coalesced,
                            connectionId,
                            use.requestIndex,
                            use.ageMs,
                            use.previousRequestsToHost,
                            reusable,
                            response.code,
                            linkFamily,
                            remoteFamily,
                            localFamily,
                            proxyType,
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
