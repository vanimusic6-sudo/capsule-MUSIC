/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.nikhil.yt.App
import com.nikhil.yt.db.ArtistSubscriptionState
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.utils.ArtistSubscriptionOutbox
import com.nikhil.yt.utils.ArtistSubscriptionSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.commons.lang3.RandomStringUtils
import java.time.LocalDateTime

@Immutable
@Entity(tableName = "artist")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val channelId: String? = null,
    val lastUpdateTime: LocalDateTime = LocalDateTime.now(),
    val bookmarkedAt: LocalDateTime? = null,
    @ColumnInfo(name = "isLocal", defaultValue = "0")
    val isLocal: Boolean = false
) {
    val isYouTubeArtist: Boolean
        get() = id.startsWith("UC") || id.startsWith("FEmusic_library_privately_owned_artist")

    val isPrivatelyOwnedArtist: Boolean
        get() = id.startsWith("FEmusic_library_privately_owned_artist")

    fun localToggleLike() = copy(
        bookmarkedAt = if (bookmarkedAt != null) null else LocalDateTime.now(),
    )

    fun toggleLike() = localToggleLike().also { it.syncSubscription() }

    /**
     * Persist the local desired state before touching the network.
     *
     * Logged-out users stay completely local. Authenticated users get a durable outbox entry that
     * survives process death/reboot and is pushed to YouTube as soon as connectivity is available.
     */
    fun syncSubscription() {
        if (isLocal || isPrivatelyOwnedArtist) return

        val app = runCatching { App.instance }.getOrNull() ?: return
        val context = app.applicationContext
        val subscribed = bookmarkedAt != null

        if (!YouTube.authState.hasLoginCookie) {
            // A logged-out follow is intentionally local-only. Also make sure a stale queued write
            // for this same artist cannot leak into a future account session.
            runBlocking(Dispatchers.IO) {
                ArtistSubscriptionOutbox.removeForArtist(context, id)
            }
            ArtistSubscriptionState.clearLocalIntent(id, channelId)
            return
        }

        // Write-ahead: the durable desired state exists before any HTTP request starts. One outbox
        // row per artist means repeated offline taps collapse to the latest local state.
        val pending =
            runBlocking(Dispatchers.IO) {
                ArtistSubscriptionOutbox.record(
                    context = context,
                    artistId = id,
                    channelId = channelId,
                    subscribed = subscribed,
                )
            }

        ArtistSubscriptionState.recordLocalIntent(
            artistId = id,
            channelId = channelId,
            subscribed = subscribed,
            nowMs = pending.changedAtMs,
            durable = true,
        )

        // WorkManager is the guaranteed path: it waits for connectivity and survives app/process
        // restarts. The direct flush below is only a latency optimization for the already-online case.
        ArtistSubscriptionSyncScheduler.enqueue(context)

        CoroutineScope(Dispatchers.IO).launch {
            ArtistSubscriptionOutbox.flushLatest(context, id)
            this.cancel()
        }
    }

    companion object {
        fun generateArtistId() = "LA" + RandomStringUtils.insecure().next(8, true, false)
    }
}
