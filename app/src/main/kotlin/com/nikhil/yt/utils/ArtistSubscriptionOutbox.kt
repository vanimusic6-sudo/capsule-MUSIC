package com.nikhil.yt.utils

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nikhil.yt.db.ArtistSubscriptionState
import com.nikhil.yt.innertube.YouTube
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Durable write-ahead queue for artist subscriptions.
 *
 * The local database is the immediate source of truth. If an authenticated user changes a
 * subscription while offline, the desired state is stored here before any network attempt.
 * WorkManager drains the queue once connectivity is available, so process death/reboot cannot
 * lose the user's intent. One row per artist means rapid toggles collapse to the latest state.
 */
internal object ArtistSubscriptionOutbox {
    private val OutboxKey = stringPreferencesKey("artist_subscription_outbox_v1")
    private val flushMutex = Mutex()

    internal data class Pending(
        val artistId: String,
        val channelId: String?,
        val subscribed: Boolean,
        val revision: String,
        val changedAtMs: Long,
    )

    suspend fun record(
        context: Context,
        artistId: String,
        channelId: String?,
        subscribed: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Pending {
        val pending =
            Pending(
                artistId = artistId,
                channelId = channelId?.trim()?.takeIf(String::isNotEmpty),
                subscribed = subscribed,
                revision = UUID.randomUUID().toString(),
                changedAtMs = nowMs,
            )

        context.dataStore.edit { preferences ->
            val items = decode(preferences[OutboxKey])
            items[artistId] = pending
            write(preferences, items)
        }
        return pending
    }

    suspend fun removeForArtist(context: Context, artistId: String) {
        context.dataStore.edit { preferences ->
            val items = decode(preferences[OutboxKey])
            if (items.remove(artistId) != null) {
                write(preferences, items)
            }
        }
    }

    suspend fun clear(context: Context) {
        context.dataStore.edit { preferences ->
            preferences.remove(OutboxKey)
        }
    }

    suspend fun snapshot(context: Context): List<Pending> =
        decode(context.dataStore.data.first()[OutboxKey])
            .values
            .sortedBy(Pending::changedAtMs)

    suspend fun hasPending(context: Context): Boolean = snapshot(context).isNotEmpty()

    suspend fun flushLatest(context: Context, artistId: String): Boolean {
        val pending = snapshot(context).firstOrNull { it.artistId == artistId } ?: return true
        return flush(context, pending)
    }

    suspend fun flushAll(context: Context): Boolean {
        val pending = snapshot(context)
        if (pending.isEmpty()) return true
        if (!YouTube.authState.hasLoginCookie) return false

        var allSucceeded = true
        for (item in pending) {
            if (!flush(context, item)) allSucceeded = false
        }
        return allSucceeded
    }

    private suspend fun flush(context: Context, queued: Pending): Boolean =
        flushMutex.withLock {
            if (!YouTube.authState.hasLoginCookie) return@withLock false

            // Re-read after taking the mutex. A newer tap must always be sent after an older one;
            // otherwise two in-flight HTTP calls could complete out of order and leave YouTube in
            // the wrong state even though the local queue contained the correct final state.
            val current = snapshot(context).firstOrNull { it.artistId == queued.artistId }
                ?: return@withLock true
            if (current.revision != queued.revision) return@withLock true

            val resolvedChannelId =
                current.channelId
                    ?.takeIf { it.isNotBlank() }
                    ?: current.artistId.takeIf { it.startsWith("UC") }
                    ?: YouTube.getChannelId(current.artistId).takeIf { it.isNotBlank() }
                    ?: return@withLock false

            ArtistSubscriptionState.recordLocalIntent(
                artistId = current.artistId,
                channelId = resolvedChannelId,
                subscribed = current.subscribed,
                nowMs = current.changedAtMs,
                durable = true,
            )

            val result = YouTube.subscribeChannel(resolvedChannelId, current.subscribed)
            if (result.isFailure) {
                Timber.w(
                    result.exceptionOrNull(),
                    "Artist subscription outbox: remote mutation failed for ${current.artistId}",
                )
                return@withLock false
            }

            val removed = removeIfRevision(context, current.artistId, current.revision)
            if (removed) {
                // The durable write has reached YouTube. Keep a short-lived in-memory intent so a
                // stale library snapshot cannot immediately undo the UI while YouTube propagates
                // the successful mutation through its browse endpoints.
                ArtistSubscriptionState.clearLocalIntent(
                    artistId = current.artistId,
                    channelId = resolvedChannelId,
                    expectedSubscribed = current.subscribed,
                )
                ArtistSubscriptionState.recordLocalIntent(
                    artistId = current.artistId,
                    channelId = resolvedChannelId,
                    subscribed = current.subscribed,
                    nowMs = System.currentTimeMillis(),
                    durable = false,
                )
            }
            true
        }

    private suspend fun removeIfRevision(
        context: Context,
        artistId: String,
        revision: String,
    ): Boolean {
        var removed = false
        context.dataStore.edit { preferences ->
            val items = decode(preferences[OutboxKey])
            val current = items[artistId]
            if (current?.revision == revision) {
                items.remove(artistId)
                write(preferences, items)
                removed = true
            }
        }
        return removed
    }

    private fun write(
        preferences: MutablePreferences,
        items: Map<String, Pending>,
    ) {
        if (items.isEmpty()) {
            preferences.remove(OutboxKey)
        } else {
            preferences[OutboxKey] = encode(items.values)
        }
    }

    private fun decode(raw: String?): LinkedHashMap<String, Pending> {
        val result = linkedMapOf<String, Pending>()
        if (raw.isNullOrBlank()) return result

        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val artistId = item.optString("artistId").trim()
                val revision = item.optString("revision").trim()
                if (artistId.isEmpty() || revision.isEmpty()) continue

                result[artistId] =
                    Pending(
                        artistId = artistId,
                        channelId =
                            item.optString("channelId")
                                .trim()
                                .takeIf { it.isNotEmpty() && it != "null" },
                        subscribed = item.optBoolean("subscribed"),
                        revision = revision,
                        changedAtMs = item.optLong("changedAtMs", 0L),
                    )
            }
        }.onFailure { error ->
            Timber.w(error, "Artist subscription outbox: ignoring malformed persisted queue")
        }
        return result
    }

    private fun encode(items: Collection<Pending>): String {
        val array = JSONArray()
        items.sortedBy(Pending::changedAtMs).forEach { item ->
            array.put(
                JSONObject().apply {
                    put("artistId", item.artistId)
                    put("channelId", item.channelId ?: JSONObject.NULL)
                    put("subscribed", item.subscribed)
                    put("revision", item.revision)
                    put("changedAtMs", item.changedAtMs)
                },
            )
        }
        return array.toString()
    }
}

internal object ArtistSubscriptionSyncScheduler {
    private const val UNIQUE_WORK_NAME = "artist-subscription-outbox"

    fun enqueue(context: Context) {
        val request =
            OneTimeWorkRequestBuilder<ArtistSubscriptionSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS,
                )
                .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

class ArtistSubscriptionSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        // Keep the durable queue intact if the account temporarily disappears. A retry also covers
        // the short auth-publication window during process startup.
        if (!YouTube.authState.hasLoginCookie) return Result.retry()

        return if (ArtistSubscriptionOutbox.flushAll(applicationContext)) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
