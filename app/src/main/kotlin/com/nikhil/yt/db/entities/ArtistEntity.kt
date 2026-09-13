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
import com.nikhil.yt.db.ArtistSubscriptionState
import com.nikhil.yt.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    fun syncSubscription() {
        if (isLocal || isPrivatelyOwnedArtist) return

        // Logged-out mode is deliberately local-only. The database bookmark is still changed by
        // the caller, but no authenticated YouTube mutation (or reconciliation intent) is created.
        if (!YouTube.authState.hasLoginCookie) return

        val subscribed = bookmarkedAt != null
        ArtistSubscriptionState.recordLocalIntent(id, channelId, subscribed)

        CoroutineScope(Dispatchers.IO).launch {
            val resolvedChannelId = channelId ?: YouTube.getChannelId(id)
            // Register the resolved UC id as an alias before sending the mutation. The library
            // endpoint returns public channel ids, while some local artist rows use browse ids.
            ArtistSubscriptionState.recordLocalIntent(id, resolvedChannelId, subscribed)
            YouTube.subscribeChannel(resolvedChannelId, subscribed)
            this.cancel()
        }
    }

    companion object {
        fun generateArtistId() = "LA" + RandomStringUtils.insecure().next(8, true, false)
    }
}
