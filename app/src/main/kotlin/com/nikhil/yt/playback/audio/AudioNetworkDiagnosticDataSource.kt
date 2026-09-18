package com.nikhil.yt.playback.audio

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.TransferListener
import com.nikhil.yt.utils.GlobalLog
import timber.log.Timber
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

internal fun Throwable.isExpectedAudioCdnInterruption(): Boolean {
    val causes = generateSequence(this as Throwable?) { it.cause }
        .take(8)
        .toList()

    // Socket/read timeouts are real network failures even though
    // SocketTimeoutException derives from InterruptedIOException.
    if (causes.any { it is SocketTimeoutException }) return false

    return causes.any { cause ->
        when (cause) {
            is InterruptedIOException ->
                !cause.message.orEmpty().contains("timeout", ignoreCase = true)
            is IOException -> {
                val message = cause.message?.trim().orEmpty()
                message.equals("Canceled", ignoreCase = true) ||
                    message.equals("Cancelled", ignoreCase = true)
            }
            else -> false
        }
    }
}

/**
 * Why the CDN said no, in terms that can be read from a shared log.
 *
 * A rejected stream used to be recorded as "Response code: 403" and nothing else, which is why
 * three separate attempts at this were made from guesswork: the server states its reason and we
 * were throwing it away. googlevideo puts the reason in the response body and in its own headers,
 * and the answer is usually one word — expired, invalid signature, a different IP than the one the
 * URL was issued to.
 *
 * Nothing secret is included, in keeping with the rest of this file: signatures, proof-of-origin
 * tokens and cookies are reported as present or absent and never by value. The one number taken
 * from the URL is how long the link had left to live, which is the single most useful fact about a
 * rejected link and identifies nobody.
 */
private fun describeRejection(failure: Throwable, uri: Uri): String {
    val rejection =
        generateSequence(failure as Throwable?) { it.cause }
            .take(8)
            .filterIsInstance<InvalidResponseCodeException>()
            .firstOrNull()
            ?: return ""

    val reason =
        rejection.responseBody
            .decodeToString()
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.take(160)
            .orEmpty()

    val serverNote =
        rejection.headerFields
            .entries
            .firstOrNull { it.key?.startsWith("X-Squid-Error", ignoreCase = true) == true }
            ?.value
            ?.firstOrNull()
            .orEmpty()

    val expiresInSeconds =
        uri.getQueryParameter("expire")
            ?.toLongOrNull()
            ?.let { it - System.currentTimeMillis() / 1000L }

    return buildString {
        append("code=").append(rejection.responseCode)
        expiresInSeconds?.let { append(" linkExpiresInSec=").append(it) }
        append(" itag=").append(uri.getQueryParameter("itag") ?: "none")
        append(" urlClient=").append(uri.getQueryParameter("c") ?: "none")
        append(" hasPoToken=").append(uri.getQueryParameter("pot") != null)
        append(" hasSignature=")
            .append(uri.getQueryParameter("sig") != null || uri.getQueryParameter("lsig") != null)
        append(" bakedRange=").append(uri.getQueryParameter("range") != null)
        if (reason.isNotEmpty()) append(" reason=\"").append(reason).append('"')
        if (serverNote.isNotEmpty()) append(" via=\"").append(serverNote).append('"')
    }
}

/**
 * Debug-only timing around the real AUDIO network upstream.
 *
 * The wrapper deliberately logs only host/key/timings and never the resolved
 * googlevideo URL, query string, signatures, cookies, or PoTokens. When field
 * logging is disabled it becomes a thin pass-through: no timestamps, strings,
 * counters, or diagnostic allocations are produced.
 *
 * Successful reads that block for at least [SLOW_READ_THRESHOLD_MS] are also
 * reported. A CDN can stall long enough to starve AudioTrack and still return
 * bytes successfully, so relying only on exceptions would miss the real stall.
 */
internal class AudioNetworkDiagnosticDataSource(
    private val upstream: DataSource,
    private val beforeNetworkOpen: ((DataSpec) -> Unit)? = null,
) : DataSource {
    private var diagnosticsEnabled = false
    private var startedAtNs = 0L
    private var openCompletedAtNs = 0L
    private var firstByteLogged = false
    private var endLogged = false
    private var bytesRead = 0L
    private var slowReadCount = 0
    private var worstReadMs = 0L
    private var mediaKey: String? = null
    private var host: String? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // Selection settling belongs before the physical network open. In particular, a cached
        // pre-resolved URL must not bypass the rapid-skip guard and reach OkHttp before cancellation.
        beforeNetworkOpen?.invoke(dataSpec)

        diagnosticsEnabled = GlobalLog.isEnabled
        if (!diagnosticsEnabled) return upstream.open(dataSpec)

        startedAtNs = System.nanoTime()
        openCompletedAtNs = 0L
        firstByteLogged = false
        endLogged = false
        bytesRead = 0L
        slowReadCount = 0
        worstReadMs = 0L
        mediaKey = dataSpec.key?.take(64)
        host = dataSpec.uri.host?.take(96)

        Timber.tag(TAG).i(
            "cdn-open-start id=%s host=%s position=%d length=%d",
            mediaKey ?: "none",
            host ?: "unknown",
            dataSpec.position,
            dataSpec.length,
        )

        return try {
            upstream.open(dataSpec).also { resolvedLength ->
                openCompletedAtNs = System.nanoTime()
                Timber.tag(TAG).i(
                    "cdn-open-ready id=%s host=%s openMs=%d resolvedLength=%d",
                    mediaKey ?: "none",
                    host ?: "unknown",
                    elapsedMs(startedAtNs, openCompletedAtNs),
                    resolvedLength,
                )
            }
        } catch (failure: Throwable) {
            val now = System.nanoTime()
            if (failure.isExpectedAudioCdnInterruption()) {
                Timber.tag(TAG).d(
                    "cdn-open-interrupted id=%s host=%s elapsedMs=%d",
                    mediaKey ?: "none",
                    host ?: "unknown",
                    elapsedMs(startedAtNs, now),
                )
            } else {
                Timber.tag(TAG).w(
                    failure,
                    "cdn-open-failed id=%s host=%s elapsedMs=%d %s",
                    mediaKey ?: "none",
                    host ?: "unknown",
                    elapsedMs(startedAtNs, now),
                    describeRejection(failure, dataSpec.uri),
                )
            }
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!diagnosticsEnabled) return upstream.read(buffer, offset, length)

        val readStartedAtNs = System.nanoTime()
        return try {
            upstream.read(buffer, offset, length).also { count ->
                val now = System.nanoTime()
                val readMs = elapsedMs(readStartedAtNs, now)

                if (count > 0) {
                    bytesRead += count.toLong()
                    if (!firstByteLogged) {
                        firstByteLogged = true
                        Timber.tag(TAG).i(
                            "cdn-first-byte id=%s host=%s fromOpenStartMs=%d afterOpenMs=%d firstReadBytes=%d",
                            mediaKey ?: "none",
                            host ?: "unknown",
                            elapsedMs(startedAtNs, now),
                            if (openCompletedAtNs != 0L) elapsedMs(openCompletedAtNs, now) else -1L,
                            count,
                        )
                    }
                } else if (count == -1 && !endLogged) {
                    endLogged = true
                    Timber.tag(TAG).d(
                        "cdn-eof id=%s host=%s bytes=%d elapsedMs=%d",
                        mediaKey ?: "none",
                        host ?: "unknown",
                        bytesRead,
                        elapsedMs(startedAtNs, now),
                    )
                }

                if (readMs >= SLOW_READ_THRESHOLD_MS) {
                    slowReadCount += 1
                    worstReadMs = maxOf(worstReadMs, readMs)
                    Timber.tag(TAG).w(
                        "cdn-slow-read id=%s host=%s readMs=%d requestedBytes=%d returnedBytes=%d totalBytes=%d slowReads=%d",
                        mediaKey ?: "none",
                        host ?: "unknown",
                        readMs,
                        length,
                        count,
                        bytesRead,
                        slowReadCount,
                    )
                }
            }
        } catch (failure: Throwable) {
            val now = System.nanoTime()
            if (failure.isExpectedAudioCdnInterruption()) {
                Timber.tag(TAG).d(
                    "cdn-read-interrupted id=%s host=%s readMs=%d bytes=%d elapsedMs=%d",
                    mediaKey ?: "none",
                    host ?: "unknown",
                    elapsedMs(readStartedAtNs, now),
                    bytesRead,
                    elapsedMs(startedAtNs, now),
                )
            } else {
                Timber.tag(TAG).w(
                    failure,
                    "cdn-read-failed id=%s host=%s readMs=%d bytes=%d elapsedMs=%d",
                    mediaKey ?: "none",
                    host ?: "unknown",
                    elapsedMs(readStartedAtNs, now),
                    bytesRead,
                    elapsedMs(startedAtNs, now),
                )
            }
            throw failure
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            upstream.close()
        } finally {
            if (diagnosticsEnabled && startedAtNs != 0L) {
                Timber.tag(TAG).d(
                    "cdn-close id=%s host=%s bytes=%d firstByte=%s elapsedMs=%d slowReads=%d worstReadMs=%d",
                    mediaKey ?: "none",
                    host ?: "unknown",
                    bytesRead,
                    firstByteLogged,
                    elapsedMs(startedAtNs, System.nanoTime()),
                    slowReadCount,
                    worstReadMs,
                )
            }
            diagnosticsEnabled = false
            startedAtNs = 0L
            openCompletedAtNs = 0L
            firstByteLogged = false
            endLogged = false
            bytesRead = 0L
            slowReadCount = 0
            worstReadMs = 0L
            mediaKey = null
            host = null
        }
    }

    internal class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val beforeNetworkOpen: ((DataSpec) -> Unit)? = null,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            AudioNetworkDiagnosticDataSource(
                upstream = upstreamFactory.createDataSource(),
                beforeNetworkOpen = beforeNetworkOpen,
            )
    }

    private companion object {
        const val TAG = "AudioCDN"
        const val SLOW_READ_THRESHOLD_MS = 250L

        fun elapsedMs(startNs: Long, endNs: Long): Long =
            if (startNs == 0L || endNs < startNs) -1L else (endNs - startNs) / 1_000_000L
    }
}
