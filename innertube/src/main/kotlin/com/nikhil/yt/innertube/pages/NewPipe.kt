/*
 * Velune Project Original (2026)
 * Kòi Natsuko (github.com/koiverse)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.nikhil.yt.innertube.pages

import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.response.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.io.IOException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

private class NewPipeDownloaderImpl(
    proxy: Proxy?,
) : Downloader() {
    private val client =
        OkHttpClient
            .Builder()
            .proxy(proxy)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val requestBuilder =
            okhttp3.Request
                .Builder()
                .method(
                    request.httpMethod(),
                    request.dataToSend()?.toRequestBody(),
                ).url(request.url())

        var hasUserAgent = false
        request.headers().forEach { (headerName, headerValueList) ->
            if (
                headerName.equals("User-Agent", ignoreCase = true) &&
                headerValueList.isNotEmpty()
            ) {
                hasUserAgent = true
            }
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        if (!hasUserAgent) {
            requestBuilder.header("User-Agent", YouTubeClient.USER_AGENT_WEB)
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }

        val responseBody = response.body.string()
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBody,
            response.request.url.toString(),
        )
    }
}

object NewPipeUtils {
    private const val PLAYER_CACHE_RECOVERY_COOLDOWN_MS = 60_000L
    private val playerManagerLock = Any()

    @Volatile
    private var downloaderInitialized = false

    @Volatile
    private var configuredProxy: Proxy? = null

    private var lastPlayerCacheRecoveryAtMs = 0L

    fun getSignatureTimestamp(videoId: String): Result<Int> =
        runCatching {
            withPlayerManagerRecovery {
                YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
            }
        }

    fun getStreamUrl(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        client: YouTubeClient? = null,
    ): Result<String> =
        runCatching {
            val url =
                format.url ?: run {
                    val cipherString =
                        format.signatureCipher
                            ?: format.cipher
                            ?: throw ParsingException("Could not find format URL")

                    decipherSignatureCipher(cipherString) { obfuscatedSignature ->
                        withPlayerManagerRecovery {
                            YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                                videoId,
                                obfuscatedSignature,
                            )
                        }
                    }
                }

            val resolvedUrl =
                runCatching {
                    retryWithBackoff(
                        maxAttempts = 3,
                        initialDelayMs = 250L,
                        maxDelayMs = 2_000L,
                    ) {
                        withPlayerManagerRecovery {
                            YoutubeJavaScriptPlayerManager
                                .getUrlWithThrottlingParameterDeobfuscated(videoId, url)
                        }
                    }
                }.getOrElse {
                    /*
                     * A broken n-parameter decoder must not discard an
                     * otherwise valid signed URL. The one-byte probe will
                     * decide whether YouTube accepts the original value.
                     */
                    url
                }

            YouTube.appendGvsPoToken(resolvedUrl, client)
        }

    fun clearPlayerCaches() {
        synchronized(playerManagerLock) {
            YoutubeJavaScriptPlayerManager.clearAllCaches()
            lastPlayerCacheRecoveryAtMs = 0L
        }
    }

    internal fun decipherSignatureCipher(
        cipherString: String,
        signatureResolver: (String) -> String,
    ): String {
        val params = parseQueryString(cipherString)
        val sourceUrl =
            params["url"]
                ?: throw ParsingException("Could not parse cipher URL")
        val signatureParameter =
            params["sp"]
                ?.takeIf { it.isNotBlank() }
                ?: "signature"
        val readySignature = params["sig"] ?: params["signature"]
        val signature =
            readySignature
                ?: params["s"]
                    ?.let(signatureResolver)
                ?: throw ParsingException("Could not parse cipher signature")

        if (signature.isBlank()) {
            throw ParsingException("Decoded cipher signature is empty")
        }

        return URLBuilder(sourceUrl)
            .apply {
                parameters[signatureParameter] = signature
            }.toString()
    }

    private fun <T> withPlayerManagerRecovery(block: () -> T): T =
        synchronized(playerManagerLock) {
            prepareDownloaderLocked()

            try {
                block()
            } catch (firstFailure: Exception) {
                val now = System.currentTimeMillis()
                if (
                    now - lastPlayerCacheRecoveryAtMs <
                    PLAYER_CACHE_RECOVERY_COOLDOWN_MS
                ) {
                    YoutubeJavaScriptPlayerManager.clearAllCaches()
                    throw firstFailure
                }

                lastPlayerCacheRecoveryAtMs = now
                YoutubeJavaScriptPlayerManager.clearAllCaches()

                try {
                    block()
                } catch (retryFailure: Exception) {
                    // NewPipe caches parser exceptions. Never leave a failed
                    // extraction poisoned until the process is restarted.
                    YoutubeJavaScriptPlayerManager.clearAllCaches()
                    retryFailure.addSuppressed(firstFailure)
                    throw retryFailure
                }
            }
        }

    private fun prepareDownloaderLocked() {
        val currentProxy = YouTube.proxy
        if (!downloaderInitialized || configuredProxy != currentProxy) {
            NewPipe.init(NewPipeDownloaderImpl(currentProxy))
            YoutubeJavaScriptPlayerManager.clearAllCaches()
            configuredProxy = currentProxy
            downloaderInitialized = true
            lastPlayerCacheRecoveryAtMs = 0L
        }
    }

    private inline fun <T> retryWithBackoff(
        maxAttempts: Int,
        initialDelayMs: Long,
        maxDelayMs: Long,
        block: () -> T,
    ): T {
        var attempt = 0
        var delayMs = initialDelayMs
        var lastError: Throwable? = null
        while (attempt < maxAttempts) {
            try {
                return block()
            } catch (error: Throwable) {
                val retryable =
                    error is SocketTimeoutException ||
                        error is IOException ||
                        error.cause is SocketTimeoutException ||
                        error.cause is IOException
                if (!retryable || attempt == maxAttempts - 1) throw error
                lastError = error
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
                delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
                attempt += 1
            }
        }
        throw lastError ?: IllegalStateException("Retry attempts exhausted")
    }
}
