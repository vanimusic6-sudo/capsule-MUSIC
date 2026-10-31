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
 * Keep Android's system media card alive if Media3's notification controller
 * cannot publish one. Reuses the existing session token, channel and ID.
 *
 * This is required for HONOR's system media island; do not replace it with
 * a generic foreground service notification.
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
            // Media3 may call removeNotification() again if its own
            // notification-controller timeline stays empty. In that case
            // it also stops *our* foreground service (same notification ID).
            // Reassert foreground whenever the native card disappeared.
            if (!rescueActive || !nativePosted) {
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
            // minSdk=26: the legacy stopForeground(Boolean) path is unreachable.
            service.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
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
