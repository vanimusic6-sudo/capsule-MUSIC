/*
 * Capsule Music — experimental HTTP/3-over-QUIC transport for VIDEO CDN.
 *
 * Runs only in separate TEST (debug) APKs on Android 14+ with no explicit
 * Capsule proxy. Production DEV/release continues using proven OkHttp.
 * InnerTube extraction and the selected clip are NEVER modified.
 *
 * HTTP/3 is a transport capability, not a claim that a given request negotiated
 * QUIC, and not an alternative to YouTube SABR/UMP. This wrapper preserves the
 * VIDEO guard that the normal OkHttp interceptor provides.
 *
 * GPL-3.0
 */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import android.content.Context
import android.net.Uri
import android.net.http.HttpEngine
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.HttpEngineDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nikhil.yt.BuildConfig
import com.nikhil.yt.innertube.CapsuleVideoRequestGuard
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A pure eligibility decision: no HTTP/3 when an application proxy must be respected. */
internal fun shouldUseVideoHttp3Transport(
    debugBuild: Boolean,
    apiLevel: Int,
    hasExplicitProxy: Boolean,
    hostname: String?,
): Boolean {
    if (!debugBuild || apiLevel < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || hasExplicitProxy) return false
    val host = hostname?.lowercase(java.util.Locale.US) ?: return false
    return host.endsWith(".googlevideo.com") && host.length > ".googlevideo.com".length
}

/**
 * The transport selection is made for EACH open, not the whole HLS/DASH playlist.
 * Unknown hosts and all custom proxies retain the current tested OkHttp pipeline.
 * HTTP error responses are NEVER silently retried on another network stack.
 */
internal class CapsuleVideoHttp3RoutingDataSource(
    private val http3: DataSource,
    private val okhttp: DataSource,
    private val useHttp3: (String?) -> Boolean,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        http3.addTransferListener(transferListener)
        okhttp.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(active == null) { "VIDEO transport is already open" }
        val selected = if (useHttp3(dataSpec.uri.host)) http3 else okhttp
        active = selected
        try {
            if (selected !== http3) return selected.open(dataSpec)

            try {
                runBlocking { CapsuleVideoRequestGuard.beforeStreamProbe() }
            } catch (blocked: CapsuleVideoRequestGuard.RequestBlockedException) {
                throw IOException(blocked.message ?: "VIDEO stream cooling down", blocked)
            }

            val guardEpoch = CapsuleVideoRequestGuard.resetEpoch()
            try {
                val opened = selected.open(dataSpec)
                // Do not label a successful range open as a 403 or a challenge.
                CapsuleVideoRequestGuard.noteStreamStatus(200, guardEpoch)
                return opened
            } catch (rejected: InvalidResponseCodeException) {
                // A real 429 must trip VIDEO's guard. A signed-URL 403 must NOT.
                CapsuleVideoRequestGuard.noteStreamStatus(rejected.responseCode, guardEpoch)
                throw rejected
            }
        } catch (failure: Throwable) {
            try {
                selected.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            active = null
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "VIDEO source not open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders.orEmpty()

    override fun close() {
        val selected = active
        active = null
        selected?.close()
    }
}

/**
 * Keep exactly one HttpEngine and two response callback threads for the process.
 * The engine's own protocol negotiation chooses HTTP/3, HTTP/2 or HTTP/1.1.
 * No HTTP/3-specific DNS/IP forcing, altered tokens or QUIC hints are used.
 */
internal object CapsuleVideoHttp3Transport {
    private val callbackExecutor: ExecutorService by lazy {
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "capsule-video-http3").apply { isDaemon = true }
        }
    }

    @Volatile private var platformEngine: HttpEngine? = null

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun getEngine(context: Context): HttpEngine = synchronized(this) {
        platformEngine ?: HttpEngine.Builder(context.applicationContext)
            .setEnableQuic(true)
            .build()
            .also { platformEngine = it }
    }

    fun createUpstream(
        context: Context,
        okhttpClient: OkHttpClient,
        explicitProxyConfigured: Boolean,
    ): DataSource.Factory {
        val okhttpFactory = OkHttpDataSource.Factory(okhttpClient)
        val fallback = DefaultDataSource.Factory(context, okhttpFactory)
        // Do not start even an idle QUIC engine on a production or proxied route.
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < 34 || explicitProxyConfigured) return fallback

        val http3Factory = runCatching {
            HttpEngineDataSource.Factory(getEngine(context), callbackExecutor)
                .setConnectionTimeoutMs(5_000)
                .setReadTimeoutMs(8_000)
        }.getOrElse { failure ->
            Timber.tag("CapsuleHTTP3").w(
                "HttpEngine unavailable (%s); using normal VIDEO OkHttp", failure.javaClass.simpleName,
            )
            return fallback
        }

        return DefaultDataSource.Factory(context, DataSource.Factory {
            CapsuleVideoHttp3RoutingDataSource(
                http3 = http3Factory.createDataSource(),
                okhttp = okhttpFactory.createDataSource(),
                useHttp3 = { host ->
                    shouldUseVideoHttp3Transport(
                        debugBuild = BuildConfig.DEBUG,
                        apiLevel = Build.VERSION.SDK_INT,
                        hasExplicitProxy = explicitProxyConfigured,
                        hostname = host,
                    )
                },
            )
        })
    }
}
