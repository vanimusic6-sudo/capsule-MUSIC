/* Capsule MUSIC — pre-HTTP CDN connection diagnostics. GPL-3.0 */
package com.nikhil.yt.playback.audio

import com.nikhil.yt.utils.GlobalLog
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Network interceptors are called only AFTER a connection has been acquired.
 * Record connection establishment separately, so a silent TLS handshake
 * isn't mistaken for an HTTP refusal, a bad signed URL, or a body timeout.
 * This listener never changes DNS, TLS validation, routes or retry budgets.
 */
internal class AudioCdnConnectDiagnostics private constructor(
    private val originHost: String,
    private val linkRef: String,
) : EventListener() {
    private val startedAtNs = System.nanoTime()

    private fun elapsedMs(): Long =
        (System.nanoTime() - startedAtNs).coerceAtLeast(0L) / 1_000_000L

    private fun endpointRef(address: InetAddress?): String =
        address?.hostAddress?.let { AudioCdnLinkIdentity.ref("remote:" + it) } ?: "none"

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        if (googlevideoServerGroup(domainName) == null) return
        Timber.tag("AudioCDN").d(
            "cdn-connect-dns ref=%s host=%s count=%d endpoints=%s ms=%d",
            linkRef, domainName, inetAddressList.size,
            inetAddressList.take(8).joinToString(",") { address ->
                addressFamilyOf(address.hostAddress) + ":" + endpointRef(address)
            }, elapsedMs(),
        )
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        Timber.tag("AudioCDN").d(
            "cdn-connect-tcp-start ref=%s origin=%s remoteRef=%s family=%s proxy=%s ms=%d",
            linkRef, originHost, endpointRef(inetSocketAddress.address),
            addressFamilyOf(inetSocketAddress.address?.hostAddress), proxy.type().name, elapsedMs(),
        )
    }

    override fun secureConnectStart(call: Call) {
        Timber.tag("AudioCDN").d("cdn-connect-tls-start ref=%s ms=%d", linkRef, elapsedMs())
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        Timber.tag("AudioCDN").d(
            "cdn-connect-tls-end ref=%s success=%s ms=%d", linkRef, handshake != null, elapsedMs(),
        )
    }

    override fun connectFailed(
        call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy,
        protocol: Protocol?, ioe: IOException,
    ) {
        // The original host is not necessarily the redirect destination.
        // Preserve only salted endpoint references, never address literals
        // or exception messages (which may include signed URLs).
        Timber.tag("AudioCDN").w(
            "cdn-connect-failed ref=%s origin=%s remoteRef=%s family=%s proxy=%s failure=%s ms=%d",
            linkRef, originHost, endpointRef(inetSocketAddress.address),
            addressFamilyOf(inetSocketAddress.address?.hostAddress),
            proxy.type().name, ioe.javaClass.simpleName, elapsedMs(),
        )
    }

    companion object {
        fun forCall(call: Call): EventListener {
            val url = call.request().url
            if (!GlobalLog.isEnabled || googlevideoServerGroup(url.host) == null) return NONE
            return AudioCdnConnectDiagnostics(url.host, AudioCdnLinkIdentity.ref(url.toString()))
        }
    }
}
