package com.nikhil.yt.playback

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.nikhil.yt.R

/**
 * Keep the actual Media3 MediaStyle notification available when Android
 * restarts/binds MusicService while a song is playing.
 *
 * HONOR's media capsule recognizes the platform media session/notification,
 * not a generic CATEGORY_SERVICE foreground notification. Never replace an
 * active MediaStyle notification with a placeholder during onStartCommand.
 *
 * Unlike a custom notification implementation, this delegates every button,
 * metadata, playback state, style and refresh to Media3.
 */
@UnstableApi
internal class CapsuleMediaNotificationProvider(
    context: Context,
    @DrawableRes smallIconRes: Int,
) : MediaNotification.Provider {
    private val delegate =
        DefaultMediaNotificationProvider(
            context,
            { MusicService.NOTIFICATION_ID },
            MusicService.CHANNEL_ID,
            R.string.music_player,
        ).apply { setSmallIcon(smallIconRes) }

    @Volatile
    var latest: MediaNotification? = null
        private set

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        if (com.nikhil.yt.utils.GlobalLog.isEnabled) {
            timber.log.Timber.tag("CapsuleSystemMedia").i(
                "media3-provider-create sessionAdded=true mediaItems=%d playing=%s",
                mediaSession.player.mediaItemCount,
                mediaSession.player.isPlaying,
            )
        }
        val result = delegate.createNotification(
            mediaSession,
            mediaButtonPreferences,
            actionFactory,
        ) { updated ->
            latest = updated
            onNotificationChangedCallback.onNotificationChanged(updated)
        }
        latest = result
        return result
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: android.os.Bundle,
    ): Boolean = delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        delegate.notificationChannelInfo

    fun clear() {
        latest = null
    }
}
