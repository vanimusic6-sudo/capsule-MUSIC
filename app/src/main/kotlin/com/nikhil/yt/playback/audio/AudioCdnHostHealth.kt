@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback.audio

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import timber.log.Timber

/** How many opens in a row a server group may fail before it is left alone. */
internal const val CDN_HOST_FAILURES_BEFORE_COLD = 3

/**
 * How long a group stays left alone once it has earned it.
 *
 * Ninety seconds, down from five minutes. A capture had a group marked cold at 18:41 and never
 * asked again, and five minutes is a long sentence for an edge that may have come back in thirty
 * seconds. Shortening it also caps what any mistake in this memory can cost.
 */
internal const val CDN_HOST_COLD_MS = 90 * 1000L

/**
 * How often one request is let through to a cold group to see whether it came back.
 *
 * Twenty seconds rather than sixty: a probe is one request, and finding out early that a group
 * recovered is worth far more than the request saved by asking later.
 */
internal const val CDN_HOST_PROBE_INTERVAL_MS = 20 * 1000L

/**
 * How many opens may be refused locally in a row before this stops refusing them.
 *
 * Leaving a group alone only helps if there is somewhere else to go, and a capture showed there
 * is not always: a re-resolve came back on the same group twenty times running, so every open was
 * refused here, the player retried, and the song never started. Silence for a minute is worse
 * than a refusal that might have been served.
 *
 * Two, because the first skip is the cheap one that usually does land somewhere else, and by the
 * third the evidence is that nothing else is on offer. Any served request resets it.
 */
internal const val CDN_HOST_MAX_CONSECUTIVE_SKIPS = 2

/**
 * Remembers which googlevideo server groups are currently refusing everything.
 *
 * A capture settled a question that an earlier one had answered the other way. Split by server
 * group, one evening's opens looked like this:
 *
 *   sn-aj4g55-5o    11 opens    0 served
 *   sn-4g5lznl7      6 opens    0 served
 *   sn-4g5lznly      3 opens    0 served
 *   sn-ajixh5-55    14 opens    8 served
 *   sn-ixh7yn7e     10 opens    6 served
 *
 * Twenty opens against three groups, not one of them answered, while two other groups served most
 * of what they were sent. One song spent eighty-three seconds on `sn-aj4g55-5o` — eleven opens,
 * each ending in a twelve second timeout or a 403 — was re-resolved onto `sn-4g5lznl7`, spent
 * ninety more seconds there across six opens, and never played a single byte.
 *
 * An earlier capture argued against remembering anything: `sn-ajixh5-55` served twenty-five
 * requests out of twenty-five one hour and refused one the next, so a group that refuses once is
 * not a group to avoid. Both things are true, and together they say what the rule has to be. Not
 * "refused once, banned" — three in a row with nothing served in between, which `sn-ajixh5-55` has
 * never done and the three dead groups did immediately.
 *
 * Five minutes, not twelve hours: these are load balanced edges, and which ones are reachable
 * changes with the network the phone is on. One request per minute is still let through to a cold
 * group, so a group that recovers is used again without anything having to notice.
 */
internal class AudioCdnHostHealth(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val failures = HashMap<String, Int>()
    private val coldUntil = HashMap<String, Long>()
    private val lastProbe = HashMap<String, Long>()

    /** Refusals raised here since the last request that was served, by anyone, anywhere. */
    private var consecutiveSkips = 0

    @Synchronized
    fun recordSuccess(host: String?) {
        // Something was served, so refusing here is leading somewhere after all.
        consecutiveSkips = 0
        val group = googlevideoServerGroup(host) ?: return
        val wasCold = coldUntil.remove(group) != null
        failures.remove(group)
        lastProbe.remove(group)
        if (wasCold) {
            Timber.tag("AudioCDN").w("cdn-host-recovered group=%s", group)
        }
    }

    @Synchronized
    fun recordFailure(host: String?) {
        val group = googlevideoServerGroup(host) ?: return
        val count = (failures[group] ?: 0) + 1
        failures[group] = count
        if (count >= CDN_HOST_FAILURES_BEFORE_COLD && coldUntil[group] == null) {
            coldUntil[group] = now() + CDN_HOST_COLD_MS
            Timber.tag("AudioCDN").w(
                "cdn-host-cold group=%s failures=%d; not asking again for %d s",
                group,
                count,
                CDN_HOST_COLD_MS / 1000,
            )
        }
    }

    /**
     * Whether this request should be refused locally rather than sent.
     *
     * Consumes the probe slot, so a caller that asks must act on the answer: asking twice in the
     * same minute reports cold the second time even if the first was let through.
     */
    @Synchronized
    fun shouldSkipHost(host: String?): Boolean {
        /*
         * The escape hatch, checked before anything else. Whatever this memory believes about the
         * group, it must never be the reason a song cannot start: once refusing has stopped
         * leading anywhere, the request goes out and takes its chances.
         */
        if (consecutiveSkips >= CDN_HOST_MAX_CONSECUTIVE_SKIPS) return false

        val group = googlevideoServerGroup(host) ?: return false
        val expiry = coldUntil[group] ?: return false
        val instant = now()
        if (instant >= expiry) {
            coldUntil.remove(group)
            failures.remove(group)
            lastProbe.remove(group)
            return false
        }
        val probedAt = lastProbe[group]
        if (probedAt == null || instant - probedAt >= CDN_HOST_PROBE_INTERVAL_MS) {
            lastProbe[group] = instant
            return false
        }
        consecutiveSkips += 1
        return true
    }

    /**
     * Drops everything learned so far.
     *
     * Which edges are reachable is a property of the network the phone is on, so nothing learned
     * on one survives the move to another.
     */
    @Synchronized
    fun forget() {
        failures.clear()
        coldUntil.clear()
        lastProbe.clear()
        consecutiveSkips = 0
    }
}

/**
 * Stops a request before it is sent to a server group that is refusing everything.
 *
 * The failure it raises carries ERROR_CODE_IO_BAD_HTTP_STATUS, which is what the service's own
 * recovery path watches for: it drops the cached URL and resolves the song again, which lands on a
 * different node. That is the one move that can help here, and the capture shows it working —
 * re-resolving moved a song off `sn-aj4g55-5o`. What the capture also shows is the cost of getting
 * there the slow way: seventeen opens and two and a half minutes of silence before anything asked
 * for a different node.
 */
internal class AudioCdnHostHealthDataSource(
    private val upstream: DataSource,
    private val health: AudioCdnHostHealth,
) : DataSource {
    private var host: String? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val requestHost = runCatching { dataSpec.uri.host }.getOrNull()
        host = requestHost

        if (health.shouldSkipHost(requestHost)) {
            Timber.tag("AudioCDN").w(
                "cdn-host-skipped group=%s; asking for a different node instead",
                googlevideoServerGroup(requestHost) ?: "unknown",
            )
            throw DataSourceException(
                "googlevideo group ${googlevideoServerGroup(requestHost)} is refusing every request",
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            )
        }

        return try {
            upstream.open(dataSpec).also { health.recordSuccess(requestHost) }
        } catch (failure: Throwable) {
            health.recordFailure(requestHost)
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        host = null
        upstream.close()
    }

    internal class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val health: AudioCdnHostHealth,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            AudioCdnHostHealthDataSource(upstreamFactory.createDataSource(), health)
    }
}
