@file:OptIn(com.metrolist.innertubex.sabr.ExperimentalSabrApi::class)

package com.nikhil.yt.playback.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrChunk
import com.nikhil.yt.innertube.YouTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** SABR is a transport, never a signed HTTP URL. No tokens enter this URI. */
internal const val CAPSULE_AUDIO_SABR_SCHEME = "capsule-sabr"

internal data class CapsuleSabrAudioOpenContext(
    val bootstrap: SabrBootstrap,
)

/**
 * A Media3 byte-stream adapter around InnerTubeX SABR/UMP.
 *
 * The producer runs on IO, with only TWO chunks waiting in the channel.
 * Media3 reads the exact contiguous encoded bytes. Byte-based seeks remain
 * correct by discarding the prefix (though expensive on a cold SABR cache);
 * do not approximate a byte offset with a timestamp and corrupt an MP4 seek.
 *
 * Never use for downloads until offline session renewal is implemented.
 */
internal class CapsuleSabrAudioDataSource(
    private val beforeNetworkOpen: (DataSpec) -> Unit = {},
    private val chunkStream: (SabrBootstrap) -> Flow<SabrChunk> = ::defaultSabrChunks,
) : BaseDataSource(true) {
    private var opened = false
    private var requestedUri: Uri? = null
    private var producer: Job? = null
    private var queue: Channel<SabrChunk>? = null
    private var pending: SabrChunk? = null
    private var pendingOffset = 0
    private var currentPosition = 0L
    private var bytesLeft = Long.MAX_VALUE

    override fun open(dataSpec: DataSpec): Long {
        check(!opened) { "SABR source already opened" }
        val bootstrap = (dataSpec.customData as? CapsuleSabrAudioOpenContext)?.bootstrap
            ?: throw IOException("Missing SABR playback session")
        if (dataSpec.uri.scheme != CAPSULE_AUDIO_SABR_SCHEME ||
            dataSpec.uri.lastPathSegment != bootstrap.videoId
        ) throw IOException("SABR media id does not match its session")
        val total = bootstrap.contentLengthBytes?.takeIf { it > 0L }
        if (total != null && dataSpec.position > total) {
            throw IOException("SABR requested byte position is beyond track length")
        }
        val available = total?.minus(dataSpec.position)
        val length = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() ->
                minOf(dataSpec.length, available ?: dataSpec.length)
            available != null -> available
            else -> C.LENGTH_UNSET.toLong()
        }
        transferInitializing(dataSpec)
        beforeNetworkOpen(dataSpec)
        currentPosition = dataSpec.position
        bytesLeft = if (length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else length
        pending = null
        pendingOffset = 0
        val pipe = Channel<SabrChunk>(capacity = 2)
        queue = pipe
        producer = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                var expectedOffset = 0L
                chunkStream(bootstrap).collect { chunk ->
                    currentCoroutineContext().ensureActive()
                    if (chunk.startRange != expectedOffset || chunk.data.isEmpty() ||
                        chunk.data.size > MAX_SABR_CHUNK_BYTES
                    ) throw IOException("SABR returned an invalid or non-contiguous byte range")
                    expectedOffset = chunk.endRangeExclusive
                    if (total != null && expectedOffset > total) {
                        throw IOException("SABR supplied more bytes than the selected format")
                    }
                    pipe.send(chunk)
                }
                if (total != null && expectedOffset != total) {
                    throw IOException("SABR ended before the selected audio file was complete")
                }
                pipe.close()
            } catch (cancelled: CancellationException) {
                pipe.close(cancelled)
            } catch (failure: Throwable) {
                pipe.close(failure)
            }
        }
        requestedUri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        check(opened) { "SABR source is closed" }
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (bytesLeft == 0L) return C.RESULT_END_OF_INPUT
        while (true) {
            val chunk = pending
            if (chunk != null && pendingOffset < chunk.data.size) {
                val absoluteOffset = chunk.startRange + pendingOffset
                if (absoluteOffset < currentPosition) {
                    pendingOffset += minOf(
                        chunk.data.size - pendingOffset,
                        (currentPosition - absoluteOffset).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    )
                    continue
                }
                if (absoluteOffset != currentPosition) {
                    throw IOException("SABR missing a requested byte range")
                }
                val count = minOf(length, chunk.data.size - pendingOffset,
                    bytesLeft.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                chunk.data.copyInto(buffer, offset, pendingOffset, pendingOffset + count)
                pendingOffset += count
                currentPosition += count
                if (bytesLeft != Long.MAX_VALUE) bytesLeft -= count
                bytesTransferred(count)
                return count
            }
            pending = null
            pendingOffset = 0
            val next = runBlocking { requireNotNull(queue).receiveCatching() }
            if (next.isClosed) {
                val failure = next.exceptionOrNull()
                if (failure != null) {
                    throw if (failure is IOException) failure else IOException("SABR audio transport failed", failure)
                }
                if (bytesLeft != Long.MAX_VALUE && bytesLeft > 0L) {
                    throw IOException("SABR exhausted before the requested bytes were read")
                }
                return C.RESULT_END_OF_INPUT
            }
            pending = next.getOrThrow()
        }
    }

    override fun getUri(): Uri? = requestedUri

    override fun close() {
        producer?.cancel()
        producer = null
        queue?.cancel()
        queue = null
        pending = null
        pendingOffset = 0
        requestedUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private companion object {
        const val MAX_SABR_CHUNK_BYTES = 8 * 1024 * 1024
    }
}

/** Selects one of two byte transports without changing Media3's cache contract. */
internal class CapsuleAudioTransportDataSource(
    private val direct: DataSource,
    private val sabr: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        direct.addTransferListener(transferListener)
        sabr.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(active == null)
        val selected = if (dataSpec.customData is CapsuleSabrAudioOpenContext) sabr else direct
        active = selected
        try {
            return selected.open(dataSpec)
        } catch (failure: Throwable) {
            try { selected.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            active = null
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireNotNull(active).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

    override fun close() {
        val selected = active
        active = null
        selected?.close()
    }
}

/** One transport client per SABR open. No long-lived session carries old proxy
 * or account state across network changes. Do not follow arbitrary redirects. */
private fun defaultSabrChunks(bootstrap: SabrBootstrap): Flow<SabrChunk> = flow {
    val selectedProxy = YouTube.streamProxy ?: YouTube.proxy
    val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000L
            socketTimeoutMillis = 15_000L
            requestTimeoutMillis = 40_000L
        }
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
                retryOnConnectionFailure(false)
            }
            selectedProxy?.let { proxy = it }
        }
    }
    try {
        emitAll(SabrAudioStream(client, bootstrap, maxRequestsWithoutProgress = 2).chunks())
    } finally {
        client.close()
    }
}
