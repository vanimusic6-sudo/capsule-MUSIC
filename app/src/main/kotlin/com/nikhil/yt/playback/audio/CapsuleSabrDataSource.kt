package com.nikhil.yt.playback.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrChunk
import io.ktor.client.HttpClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Separate read-only SABR Media3 transport.
 * It consumes actual UMP audio segments from InnerTubeX; it does not pretend
 * that a serverAbrStreamingUrl is a normal progressive GET endpoint.
 */
internal data class CapsuleSabrOpenContext(val bootstrap: SabrBootstrap)

/** ResolvingDataSource first resolves media, then this source selects the transport. */
internal class CapsuleSabrRoutingDataSource(
    private val direct: DataSource,
    private val sabr: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        direct.addTransferListener(transferListener)
        sabr.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(active == null) { "Already open" }
        val chosen = if (dataSpec.customData is CapsuleSabrOpenContext) sabr else direct
        active = chosen
        return try {
            chosen.open(dataSpec)
        } catch (e: Exception) {
            active = null
            chosen.close()
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "Source not opened" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        val old = active
        active = null
        old?.close()
    }
}

/**
 * Bounded backpressured sequential-byte adapter. Each new Media3 byte-range
 * open starts a fresh SABR collection and skips the already-read bytes using
 * the source-reported startRange. This is correct but may be slower on large
 * backward/forward seeks until a seek-index transport is implemented.
 * No entire-song buffering, no full-file GETs and no unbounded replay memory.
 */
@OptIn(ExperimentalSabrApi::class)
internal class CapsuleSabrDataSource(private val httpClient: () -> HttpClient) :
    BaseDataSource(true) {
    private var requestedSpec: DataSpec? = null
    private var streamJob: Job? = null
    private var events: Channel<SabrChunk>? = null
    private var activeClient: HttpClient? = null
    private var currentChunk: SabrChunk? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var connected = false

    override fun open(dataSpec: DataSpec): Long {
        check(!connected) { "Already open" }
        val session = dataSpec.customData as? CapsuleSabrOpenContext
            ?: throw IOException("SABR bootstrap missing")
        val bootstrap = session.bootstrap
        require(bootstrap.serverAbrStreamingUrl.startsWith("https://")) {
            "SABR request must use HTTPS"
        }
        require(dataSpec.position >= 0L)
        transferInitializing(dataSpec)
        position = dataSpec.position
        val total = bootstrap.contentLengthBytes?.takeIf { it > 0L }
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else total?.let { (it - position).coerceAtLeast(0L) }
            ?: C.LENGTH_UNSET.toLong()
        requestedSpec = dataSpec
        currentChunk = null
        events = Channel(Channel.RENDEZVOUS)
        val queue = checkNotNull(events)
        val client = httpClient()
        activeClient = client
        streamJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                SabrAudioStream(httpClient = client, bootstrap = bootstrap)
                    .chunks()
                    .collect { chunk ->
                        if (chunk.data.size > 4 * 1024 * 1024)
                            throw IOException("SABR segment too large")
                        // Replay is byte-exact. Refuse gaps rather than feeding
                        // Media3 damaged/corrupt compressed audio.
                        queue.send(chunk)
                    }
                queue.close()
            } catch (cancelled: CancellationException) {
                queue.cancel(cancelled)
            } catch (error: Exception) {
                queue.close(error)
            } finally {
                client.close()
            }
        }
        connected = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!connected) throw IOException("SABR source closed")
        if (offset < 0 || length < 0 || offset > buffer.size - length)
            throw IndexOutOfBoundsException()
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        while (true) {
            val chunk = currentChunk
            if (chunk != null && chunk.endRangeExclusive > position &&
                chunk.startRange <= position) {
                val start = (position - chunk.startRange).toInt()
                val requested = minOf(length, chunk.data.size - start)
                val count = if (remaining == C.LENGTH_UNSET.toLong()) requested
                    else minOf(requested.toLong(), remaining).toInt()
                if (count <= 0) return C.RESULT_END_OF_INPUT
                System.arraycopy(chunk.data, start, buffer, offset, count)
                position += count
                if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
                bytesTransferred(count)
                if (position == chunk.endRangeExclusive) currentChunk = null
                return count
            }
            val received = runBlocking {
                checkNotNull(events).receiveCatching()
            }
            if (received.isClosed) {
                received.exceptionOrNull()?.let { throw IOException("SABR stream failed", it) }
                return C.RESULT_END_OF_INPUT
            }
            val next = received.getOrNull() ?: return C.RESULT_END_OF_INPUT
            if (next.endRangeExclusive <= position) continue
            if (next.startRange > position) {
                throw IOException("SABR media has a byte-range gap: ${next.startRange} > $position")
            }
            currentChunk = next
        }
    }

    override fun getUri(): Uri? = requestedSpec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        streamJob?.cancel()
        streamJob = null
        events?.cancel()
        events = null
        activeClient?.close()
        activeClient = null
        currentChunk = null
        requestedSpec = null
        if (connected) {
            connected = false
            transferEnded()
        }
    }
}
