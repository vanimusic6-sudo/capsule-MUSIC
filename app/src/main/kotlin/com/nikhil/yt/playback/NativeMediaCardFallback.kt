@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.nikhil.yt.MainActivity
import com.nikhil.yt.R
import com.nikhil.yt.utils.GlobalLog
import timber.log.Timber

/**
 * Last-resort Android PLATFORM MediaStyle publication.
 *
 * The physical HONOR field log showed no notification at all, although Media3
 * reported isPlaying=true, an added MediaLibrarySession and channel importance=2.
 * Media3's internal notification manager returns without calling the provider
 * if its notification controller is disconnected or sees an empty timeline.
 *
 * Only after giving Media3 the first chance do we publish this using THE SAME
 * real platform MediaSession.Token, channel and ID. This is not an OEM-specific
 * fabricated island or a second fake MediaSession. It is the documented Android
 * MediaStyle contract consumed by the lock screen, Bluetooth and SystemUI.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal class NativeMediaCardFallback(private val service: MusicService) {
    private val manager: NotificationManager
        get() = service.getSystemService(NotificationManager::class.java)

    var rescueActive = false
        private set

    fun hasNativeMediaNotification(): Boolean = runCatching {
        manager.activeNotifications.any {
            it.id == MusicService.NOTIFICATION_ID &&
                it.notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        }
    }.getOrDefault(false)

    fun reconcile(session: MediaSession, player: Player, media3ProviderReady: Boolean) {
        if (player.currentMediaItem == null ||
            player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
        ) {
            clear()
            return
        }

        val nativePosted = hasNativeMediaNotification()
        if (media3ProviderReady && nativePosted) {
            // Normal Media3 ownership wins without changing its notification.
            rescueActive = false
            return
        }
        if (!rescueActive && nativePosted) return
        if (!rescueActive && !player.isPlaying) return

        try {
            val notification = buildNativeMediaCardNotification(
                context = service,
                token = session.platformToken,
                title = player.mediaMetadata.title ?: service.getString(R.string.music_player),
                artist = player.mediaMetadata.artist ?: "",
                isPlaying = player.isPlaying,
            )
            if (!rescueActive) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    service.startForeground(
                        MusicService.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                    )
                } else {
                    service.startForeground(MusicService.NOTIFICATION_ID, notification)
                }
            } else {
                manager.notify(MusicService.NOTIFICATION_ID, notification)
            }
            rescueActive = true
            if (GlobalLog.isEnabled) {
                Timber.tag("CapsuleSystemMedia").i(
                    "native-platform-media-rescue published=%s tokenAttached=%s playing=%s nativeManagerReady=%s",
                    hasNativeMediaNotification(),
                    notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true,
                    player.isPlaying,
                    media3ProviderReady,
                )
            }
        } catch (failure: Exception) {
            // Never interrupt audible playback due to a notification error.
            Timber.tag("CapsuleSystemMedia").e(
                failure, "native-platform-media-rescue failure",
            )
        }
    }

    fun clear() {
        if (!rescueActive) return
        rescueActive = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                service.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                service.stopForeground(true)
            }
            manager.cancel(MusicService.NOTIFICATION_ID)
        }
    }
}

/** Pure platform notification builder, independently verifiable on Robolectric. */
@RequiresApi(Build.VERSION_CODES.O)
internal fun buildNativeMediaCardNotification(
    context: Context,
    token: android.media.session.MediaSession.Token,
    title: CharSequence,
    artist: CharSequence,
    isPlaying: Boolean,
): Notification {
    fun command(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, MusicService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    val previous = Notification.Action.Builder(
        R.drawable.ic_skip_previous, "Previous",
        command("com.nikhil.yt.ACTION_PREV", 901),
    ).build()
    val playOrPause = Notification.Action.Builder(
        if (isPlaying) R.drawable.pause else R.drawable.play,
        if (isPlaying) "Pause" else "Play",
        command("com.nikhil.yt.ACTION_PLAY_PAUSE", 902),
    ).build()
    val next = Notification.Action.Builder(
        R.drawable.ic_skip_next, "Next",
        command("com.nikhil.yt.ACTION_NEXT", 903),
    ).build()

    val activity = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return Notification.Builder(context, MusicService.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_capsule_monochrome)
        .setContentTitle(title)
        .setContentText(artist)
        .setContentIntent(activity)
        .setCategory(Notification.CATEGORY_TRANSPORT)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .setOngoing(isPlaying)
        .setShowWhen(false)
        .addAction(previous)
        .addAction(playOrPause)
        .addAction(next)
        .setStyle(
            Notification.MediaStyle()
                .setMediaSession(token)
                .setShowActionsInCompactView(0, 1, 2),
        )
        .build()
}
