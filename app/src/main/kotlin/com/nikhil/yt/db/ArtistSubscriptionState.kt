package com.nikhil.yt.db

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps short-lived local artist subscription intents separate from the remote library snapshot.
 *
 * YouTube Music can return a subscription list that is a few refreshes behind a successful
 * subscribe/unsubscribe request. Treating one such snapshot as authoritative causes the UI to
 * flip back even though the account already accepted the action. This state machine protects the
 * fresh local intent, while still allowing the authenticated account to become authoritative once
 * the propagation window has passed.
 *
 * Logged-out subscriptions never enter this tracker; they are intentionally local-only.
 */
internal object ArtistSubscriptionState {
    private const val INTENT_TTL_MS = 5 * 60_000L
    private const val REQUIRED_MISSING_SNAPSHOTS = 2

    internal data class PendingIntent(
        val subscribed: Boolean,
        val changedAtMs: Long,
    )

    private val pendingIntents = ConcurrentHashMap<String, PendingIntent>()
    private val missingSnapshots = ConcurrentHashMap<String, Int>()

    fun recordLocalIntent(
        artistId: String?,
        channelId: String?,
        subscribed: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val keys = identityKeys(artistId, channelId)
        if (keys.isEmpty()) return

        val intent = PendingIntent(subscribed = subscribed, changedAtMs = nowMs)
        keys.forEach { pendingIntents[it] = intent }
        resetMissing(artistId, channelId)
    }

    fun pendingDesiredState(
        artistId: String?,
        channelId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean? {
        val intent =
            identityKeys(artistId, channelId)
                .mapNotNull { pendingIntents[it] }
                .maxByOrNull { it.changedAtMs }
                ?: return null

        val ageMs = nowMs - intent.changedAtMs
        if (ageMs >= INTENT_TTL_MS) {
            clearIntent(intent)
            return null
        }

        return intent.subscribed
    }

    fun confirmRemoteState(
        artistId: String?,
        channelId: String?,
        subscribed: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val intent =
            identityKeys(artistId, channelId)
                .mapNotNull { pendingIntents[it] }
                .maxByOrNull { it.changedAtMs }

        if (intent != null) {
            val ageMs = nowMs - intent.changedAtMs
            if (ageMs >= INTENT_TTL_MS || intent.subscribed == subscribed) {
                clearIntent(intent)
            }
        }

        if (subscribed) resetMissing(artistId, channelId)
    }

    /**
     * A single missing server snapshot is not enough to delete a local subscription.
     * Two consecutive authenticated snapshots are required unless a fresh local subscribe intent
     * is still propagating, in which case remote absence is ignored until the intent is confirmed
     * or expires.
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
    }
}
