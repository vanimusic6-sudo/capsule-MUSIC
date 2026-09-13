package com.nikhil.yt.db

import com.nikhil.yt.App
import com.nikhil.yt.utils.ArtistSubscriptionOutbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Protects local artist subscription intent from stale YouTube library snapshots.
 *
 * Short-lived intents cover normal online propagation. Durable intents are restored from the
 * subscription outbox and deliberately do not expire: an offline user action remains authoritative
 * until it has actually been delivered to YouTube.
 */
internal object ArtistSubscriptionState {
    private const val INTENT_TTL_MS = 5 * 60_000L
    private const val REQUIRED_MISSING_SNAPSHOTS = 2

    internal data class PendingIntent(
        val subscribed: Boolean,
        val changedAtMs: Long,
        val durable: Boolean = false,
    )

    private val pendingIntents = ConcurrentHashMap<String, PendingIntent>()
    private val missingSnapshots = ConcurrentHashMap<String, Int>()
    private val durableHydrated = AtomicBoolean(false)
    private val hydrateLock = Any()

    fun recordLocalIntent(
        artistId: String?,
        channelId: String?,
        subscribed: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        durable: Boolean = false,
    ) {
        val keys = identityKeys(artistId, channelId)
        if (keys.isEmpty()) return

        val intent =
            PendingIntent(
                subscribed = subscribed,
                changedAtMs = nowMs,
                durable = durable,
            )
        keys.forEach { pendingIntents[it] = intent }
        resetMissing(artistId, channelId)
    }

    fun pendingDesiredState(
        artistId: String?,
        channelId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean? {
        ensureDurableHydrated()

        val intent =
            identityKeys(artistId, channelId)
                .mapNotNull { pendingIntents[it] }
                .maxByOrNull { it.changedAtMs }
                ?: return null

        if (!intent.durable) {
            val ageMs = nowMs - intent.changedAtMs
            if (ageMs >= INTENT_TTL_MS) {
                clearIntent(intent)
                return null
            }
        }

        return intent.subscribed
    }

    fun confirmRemoteState(
        artistId: String?,
        channelId: String?,
        subscribed: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        ensureDurableHydrated()

        val intent =
            identityKeys(artistId, channelId)
                .mapNotNull { pendingIntents[it] }
                .maxByOrNull { it.changedAtMs }

        if (intent != null && !intent.durable) {
            val ageMs = nowMs - intent.changedAtMs
            if (ageMs >= INTENT_TTL_MS || intent.subscribed == subscribed) {
                clearIntent(intent)
            }
        }

        if (subscribed) resetMissing(artistId, channelId)
    }

    /**
     * A single missing server snapshot is not enough to delete a local subscription.
     * Durable outbox intent blocks remote absence indefinitely until delivery succeeds.
     */
    fun shouldApplyRemoteAbsence(
        artistId: String?,
        channelId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (pendingDesiredState(artistId, channelId, nowMs) == true) {
            resetMissing(artistId, channelId)
            return false
        }

        val keys = identityKeys(artistId, channelId)
        if (keys.isEmpty()) return false

        var maxCount = 0
        keys.forEach { key ->
            val count = missingSnapshots.merge(key, 1) { previous, one -> previous + one } ?: 1
            if (count > maxCount) maxCount = count
        }
        return maxCount >= REQUIRED_MISSING_SNAPSHOTS
    }

    fun observeRemotePresence(artistId: String?, channelId: String?) {
        resetMissing(artistId, channelId)
    }

    fun identitiesMatch(
        localId: String?,
        localChannelId: String?,
        remoteId: String?,
        remoteChannelId: String?,
    ): Boolean {
        val local = identityKeys(localId, localChannelId)
        if (local.isEmpty()) return false
        return identityKeys(remoteId, remoteChannelId).any(local::contains)
    }

    fun clearLocalIntent(
        artistId: String?,
        channelId: String?,
        expectedSubscribed: Boolean? = null,
    ) {
        ensureDurableHydrated()
        val intent =
            identityKeys(artistId, channelId)
                .mapNotNull { pendingIntents[it] }
                .maxByOrNull { it.changedAtMs }
                ?: return
        if (expectedSubscribed == null || intent.subscribed == expectedSubscribed) {
            clearIntent(intent)
        }
    }

    private fun ensureDurableHydrated() {
        if (durableHydrated.get()) return

        val app = runCatching { App.instance }.getOrNull() ?: return
        synchronized(hydrateLock) {
            if (durableHydrated.get()) return

            val pending =
                runCatching {
                    runBlocking(Dispatchers.IO) {
                        ArtistSubscriptionOutbox.snapshot(app.applicationContext)
                    }
                }.getOrNull() ?: return

            pending.forEach { item ->
                recordLocalIntent(
                    artistId = item.artistId,
                    channelId = item.channelId,
                    subscribed = item.subscribed,
                    nowMs = item.changedAtMs,
                    durable = true,
                )
            }
            durableHydrated.set(true)
        }
    }

    private fun resetMissing(artistId: String?, channelId: String?) {
        identityKeys(artistId, channelId).forEach(missingSnapshots::remove)
    }

    private fun clearIntent(intent: PendingIntent) {
        pendingIntents.entries.removeIf { it.value == intent }
    }

    private fun identityKeys(artistId: String?, channelId: String?): Set<String> =
        buildSet {
            artistId?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            channelId?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
        }

    internal fun resetForTests() {
        pendingIntents.clear()
        missingSnapshots.clear()
        // JVM tests do not have an initialized Android Application. Treat the in-memory state as
        // authoritative for the duration of a test after resetForTests().
        durableHydrated.set(true)
    }
}
