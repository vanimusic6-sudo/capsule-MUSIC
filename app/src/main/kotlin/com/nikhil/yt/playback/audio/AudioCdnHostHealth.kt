@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback.audio

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.TransferListener
import timber.log.Timber
import java.io.IOException

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
 * Cold groups are periodically probed, and all observations are discarded when the route changes.
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
     * same probe interval reports cold the second time even if the first was let through.
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
 * A typed recovery signal stops Media3 retrying the cached URL. MusicService owns the bounded
 * fresh resolve and preserves the track position. A new URL may still name the same group, so
 * the health model retains its probe and consecutive-skip escape hatch.
 */
internal class AudioCdnHostHealthDataSource(
    private val upstream: DataSource,
    private val health: AudioCdnHostHealth,
) : DataSource {
    private var host: String? = null
    private var servedByHost: String? = null
    private var deliveredBytes = false
    private var mediaId: String? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val requestHost = runCatching { dataSpec.uri.host }.getOrNull()
        host = requestHost
        servedByHost = null
        deliveredBytes = false
        mediaId = (dataSpec.customData as? AudioCdnOpenContext)?.mediaId
            ?: dataSpec.key?.takeIf { it.startsWith("capsule:audio:") }?.let(AudioCacheIdentity::mediaId)

        if (health.shouldSkipHost(requestHost)) {
            Timber.tag("AudioCDN").w(
                "cdn-host-skipped group=%s; requesting a fresh stream URL",
                googlevideoServerGroup(requestHost) ?: "unknown",
            )
            mediaId?.let {
                throw AudioCdnRefreshRequiredException(it, AudioCdnRefreshReason.HOST_COOLDOWN)
            }
            throw DataSourceException(
                "googlevideo group ${googlevideoServerGroup(requestHost)} is refusing every request",
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            )
        }

        return try {
            upstream.open(dataSpec).also {
                // HTTP 206 means headers arrived, not that the CDN actually delivered audio.
                // Keep a previously failing host under observation until the first successful
                // body read. A redirect can change the group that really served the bytes.
                servedByHost = upstream.uri?.host ?: requestHost
            }
        } catch (failure: IOException) {
            throw classifyFailure(failure, AudioCdnRefreshReason.OPEN_FAILURE)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        upstream.read(buffer, offset, length).also { count ->
            if (count > 0 && !deliveredBytes) {
                deliveredBytes = true
                health.recordSuccess(servedByHost ?: host)
            }
        }
    } catch (failure: IOException) {
        throw classifyFailure(failure, AudioCdnRefreshReason.READ_FAILURE)
    }

    private fun classifyFailure(failure: IOException, reason: AudioCdnRefreshReason): IOException {
        // Includes stale-selection cancellation before a physical request was ever sent.
        if (failure.isExpectedAudioCdnInterruption()) return failure
        if (failure.audioCdnRefreshRequiredOrNull() != null) return failure
        val httpCode = generateSequence(failure as Throwable?) { it.cause }
            .take(12).filterIsInstance<InvalidResponseCodeException>().firstOrNull()?.responseCode

        // HTTP 403/410 is a rejection of this signed request, NOT evidence that the entire
        // googlevideo group is offline. Three retries of the SAME first slice used to mark
        // a healthy host cold for 90s, amplifying rather than preventing a transient refusal.
        // All HTTP statuses (especially 429) remain with their existing per-link/safety policies.
        // Only actual transport faults contribute to host reachability.
        if (httpCode != null || !failure.isAudioCdnTransportFailure()) return failure

        // A read failure belongs to the server that served the open, which may be the
        // redirect target. For a failed open there is no reliable final URL; use the origin.
        health.recordFailure(servedByHost ?: host)
        val id = mediaId
        return if (id != null && googlevideoServerGroup(host) != null) {
            AudioCdnRefreshRequiredException(id, reason, failure)
        } else failure
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        host = null
        servedByHost = null
        deliveredBytes = false
        mediaId = null
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
