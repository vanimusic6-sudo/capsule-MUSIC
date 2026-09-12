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

/** Debug-only connection metadata around googlevideo requests. */
internal class AudioCdnConnectionDiagnosticInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestHost = request.url.host
        val connection = chain.connection()
        val routeHost = connection?.route()?.address?.url?.host
        val protocol = connection?.protocol()?.toString()
        val coalesced = audioCdnCrossHostCoalesced(requestHost, routeHost, protocol)
        val connectionId = connection?.let(System::identityHashCode) ?: -1

        return try {
            chain.proceed(request).also { response ->
                if (GlobalLog.isEnabled) {
                    Timber.tag("AudioCDN").d(
                        "cdn-wire host=%s routeHost=%s protocol=%s coalesced=%s conn=%d status=%d",
                        requestHost,
                        routeHost ?: "unknown",
                        protocol ?: "unknown",
                        coalesced,
                        connectionId,
                        response.code,
                    )
                }
            }
        } catch (failure: IOException) {
            if (GlobalLog.isEnabled) {
                Timber.tag("AudioCDN").d(
                    "cdn-wire-iofail host=%s routeHost=%s protocol=%s coalesced=%s conn=%d type=%s",
                    requestHost,
                    routeHost ?: "unknown",
                    protocol ?: "unknown",
                    coalesced,
                    connectionId,
                    failure::class.java.simpleName,
                )
            }
            throw failure
        }
    }
}
