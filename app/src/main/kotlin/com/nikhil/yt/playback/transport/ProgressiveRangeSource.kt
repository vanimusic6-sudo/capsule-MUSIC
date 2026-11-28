@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback.transport

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException

internal data class RangePlan(
    val firstBytes: Long,
    val rate: RangeSizePolicy,
    val requireResponseRange: Boolean = false,
)

/** One cursor for a Media3 open, including seeks and cache holes. No metadata requests here. */
internal open class ProgressiveRangeSource(
    private val upstream: DataSource,
    private val planFor: (DataSpec) -> RangePlan?,
    private val readAheadFor: (DataSpec) -> Int = { 0 },
    private val networkIdentity: () -> Long? = { null },
    private val canResume: (IOException, Boolean) -> Boolean,
    private val canRetryRangeOpen: (IOException) -> Boolean = { false },
    private val afterRangeClose: ((Long) -> Unit)? = null,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : DataSource {
    private var request: DataSpec? = null
    // A rejected or malformed range is terminal for this open. A subsequent
    // read must not silently repeat a failed CDN request at the same seam.
    private var terminalFailure: IOException? = null
    private var plan: RangePlan? = null
    private var network: Long? = null
    private var position = 0L
    private var remaining = UNSET
    private var rangeRemaining = UNSET
    private var total = UNSET
    private var rangeStart = 0L
    private var resumed = false
    private var rangeOpenRetried = false
    // Optional transport lifetime policy. Count loader idle time, not time
    // blocked in a network read. Never start another range just to keep TCP alive.
    private var lastRangeReadAtMs: Long? = null
    private var rangeIdleMs = 0L
    private var validator: String? = null
    private var ahead = ByteArray(0)
    private var aheadOffset = 0
    private var aheadEnd = 0

    override fun open(dataSpec: DataSpec): Long {
        check(request == null)
        return try {
            openResource(dataSpec)
        } catch (failure: IOException) {
            terminalFailure = failure
            throw failure
        }
    }

    private fun openResource(dataSpec: DataSpec): Long {
        request = dataSpec
        position = dataSpec.position
        rangeStart = position
        remaining = dataSpec.length
        network = networkIdentity()
        val capacity = readAheadFor(dataSpec)
        if (ahead.size != capacity) ahead = ByteArray(capacity)
        val windowed = dataSpec.uri.isHierarchical && dataSpec.uri.getQueryParameter("range") != null
        val selected = if (windowed) null else planFor(dataSpec)
        plan = selected
        if (selected == null) return openDirect(dataSpec, validate = !windowed)
        val first = slice(selected.firstBytes)
        openRangeWithReconnect(first)
        val range = responseRange()
        if (range == null && selected.requireResponseRange) {
            val host = dataSpec.uri.host.orEmpty()
            if (host.equals("googlevideo.com", true) || host.endsWith(".googlevideo.com", true))
                throw IOException("CDN omitted the requested byte range")
            upstream.close()
            plan = null
            return openDirect(dataSpec, validate = true)
        }
        acceptRange(first, range)
        beginRangeLease()
        return remaining
    }

    private fun openDirect(spec: DataSpec, validate: Boolean): Long {
        val opened = openRangeWithReconnect(spec)
        if (validate) {
            responseRange()?.let { range ->
                val expected = if (spec.length == UNSET) range.total - position else spec.length
                if (range.start != position || range.size != expected)
                    throw IOException("Incomplete or misplaced direct media range")
                // A valid range states the exact byte count even if the upstream
                // has no Content-Length. Preserve early-EOF detection in that case.
                if (remaining == UNSET) remaining = range.size
            }
        }
        if (remaining == UNSET) remaining = opened
        rangeRemaining = remaining
        beginRangeLease()
        return remaining
    }

    private fun slice(limit: Long): DataSpec = requireNotNull(request).buildUpon()
        .setPosition(position)
        .setLength(if (remaining == UNSET) limit else minOf(limit, remaining))
        .build()

    private fun acceptRange(spec: DataSpec, range: ResponseRange?) {
        if (range == null) {
            if (total != UNSET || plan?.requireResponseRange == true || remaining == UNSET)
                throw IOException("Missing continuation byte range")
            rangeRemaining = spec.length
        } else {
            if (range.start != position || range.size > spec.length ||
                (total != UNSET && total != range.total))
                throw IOException("Incompatible media byte range")
            total = range.total
            val available = total - position
            if (remaining == UNSET) remaining = available
            if (remaining > available) throw EOFException("Media range exceeds resource length")
            rangeRemaining = range.size
        }
        val tag = header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
        if (validator != null && validator != tag) throw IOException("Media representation changed")
        validator = tag
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return 0
        terminalFailure?.let { throw IOException("Media open has failed; close before reopening", it) }
        check(request != null)
        return try {
            readResource(buffer, offset, length)
        } catch (failure: IOException) {
            terminalFailure = failure
            throw failure
        }
    }

    private fun readResource(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (rangeRemaining == 0L) {
            if (network != networkIdentity()) throw java.net.SocketException("Media network changed")
            val selected = requireNotNull(plan)
            closeRangeLease()
            val next = slice(selected.rate.onChunkComplete())
            resumed = false
            rangeStart = position
            openRangeWithReconnect(next)
            acceptRange(next, responseRange())
            beginRangeLease()
        }
        val wanted = bounded(length)
        val count = when {
            aheadOffset < aheadEnd -> drainAhead(buffer, offset, wanted)
            wanted < ahead.size -> {
                aheadEnd = readNetwork(ahead, 0, bounded(ahead.size))
                aheadOffset = 0
                if (aheadEnd < 0) return C.RESULT_END_OF_INPUT
                drainAhead(buffer, offset, wanted)
            }
            else -> readNetwork(buffer, offset, wanted)
        }
        if (count > 0) {
            position += count
            if (remaining != UNSET) remaining -= count
            if (rangeRemaining != UNSET) rangeRemaining -= count
        }
        return count
    }

    private fun bounded(size: Int) =
        if (rangeRemaining == UNSET) size else minOf(size.toLong(), rangeRemaining).toInt()

    private fun drainAhead(buffer: ByteArray, offset: Int, wanted: Int): Int {
        val count = minOf(wanted, aheadEnd - aheadOffset)
        ahead.copyInto(buffer, offset, aheadOffset, aheadOffset + count)
        aheadOffset += count
        return count
    }

    // The same bounded transport reconnect is available on the first GET,
    // a cache-hole open, or a continuation. A failed prefetched period must
    // not wait for Media3 to make it current before reconnecting.
    private fun openRangeWithReconnect(spec: DataSpec): Long {
        try {
            return upstream.open(spec)
        } catch (failure: IOException) {
            if (rangeOpenRetried || network != networkIdentity() ||
                !canRetryRangeOpen(failure) || Thread.currentThread().isInterrupted) throw failure
            rangeOpenRetried = true
            resumed = true
            try {
                closeRangeLease()
                if (network != networkIdentity() || Thread.currentThread().isInterrupted) throw failure
                return upstream.open(spec)
            } catch (retry: IOException) {
                if (retry !== failure) retry.addSuppressed(failure)
                throw retry
            }
        }
    }

    private fun closeRangeLease() {
        val last = lastRangeReadAtMs
        val idleMs = if (last != null) rangeIdleMs + (nowMs() - last).coerceAtLeast(0L) else 0L
        lastRangeReadAtMs = null
        rangeIdleMs = 0L
        upstream.close()
        // The response has now released its socket. This also runs at the
        // final range, a direct short response, a seek or cancellation.
        if (last != null) afterRangeClose?.invoke(idleMs)
    }

    private fun beginRangeLease() {
        if (afterRangeClose == null) return
        lastRangeReadAtMs = nowMs()
        rangeIdleMs = 0L
    }

    private fun readNetwork(buffer: ByteArray, offset: Int, length: Int): Int {
        if (afterRangeClose != null) {
            lastRangeReadAtMs?.let { rangeIdleMs += (nowMs() - it).coerceAtLeast(0L) }
        }
        val rate = plan?.rate?.takeIf { it.measures(length) }
        val start = if (rate != null) System.nanoTime() else 0L
        try {
            val count = checkedRead(buffer, offset, length)
            rate?.observeRead(length, count, (System.nanoTime() - start) / 1_000_000L)
            return count
        } catch (failure: IOException) {
            if (plan == null || resumed || network != networkIdentity() ||
                !canResume(failure, position > rangeStart)) throw failure
            resumed = true
            try {
                closeRangeLease()
                val rest = slice(rangeRemaining)
                upstream.open(rest)
                acceptRange(rest, responseRange())
                beginRangeLease()
                return checkedRead(buffer, offset, bounded(length))
            } catch (retry: IOException) {
                if (retry !== failure) retry.addSuppressed(failure)
                throw retry
            }
        } finally {
            if (afterRangeClose != null && lastRangeReadAtMs != null) lastRangeReadAtMs = nowMs()
        }
    }

    private fun checkedRead(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = upstream.read(buffer, offset, length)
        if (count == C.RESULT_END_OF_INPUT && rangeRemaining > 0L)
            throw EOFException("Media response ended with $rangeRemaining bytes outstanding")
        if (count == 0) throw IOException("Empty media read without EOF")
        return count
    }

    private data class ResponseRange(val start: Long, val size: Long, val total: Long)

    private fun header(name: String): String? {
        val entries = upstream.responseHeaders.entries.filter { it.key.equals(name, true) }
        if (entries.isEmpty()) return null
        return entries.singleOrNull()?.value?.singleOrNull()?.trim()
            ?: throw IOException("Ambiguous media response header")
    }

    private fun responseRange(): ResponseRange? {
        val value = header("Content-Range") ?: return null
        val match = RANGE.matchEntire(value) ?: throw IOException("Invalid media byte range")
        val start = match.groupValues[1].toLongOrNull()
        val end = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].toLongOrNull()
        if (start == null || end == null || total == null || end < start || end >= total)
            throw IOException("Invalid media byte range bounds")
        return ResponseRange(start, end - start + 1, total)
    }

    override fun close() {
        request = null
        terminalFailure = null
        plan = null
        validator = null
        total = UNSET
        aheadOffset = 0
        aheadEnd = 0
        resumed = false
        rangeOpenRetried = false
        closeRangeLease()
    }

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders() = upstream.responseHeaders
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    private companion object {
        const val UNSET = -1L
        val RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
    }
}
