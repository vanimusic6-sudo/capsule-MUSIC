/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.media3.common.util.NotificationUtil
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.nikhil.yt.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject


@AndroidEntryPoint
class ExoDownloadService : DownloadService(
    NOTIFICATION_ID,
    1000L,
    CHANNEL_ID,
    R.string.downloading,
    0
) {
    @Inject
    lateinit var downloadUtil: DownloadUtil

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ADD_DOWNLOAD) {
            @Suppress("DEPRECATION")
            val request = intent.getParcelableExtra<androidx.media3.exoplayer.offline.DownloadRequest>(KEY_DOWNLOAD_REQUEST)
            if (request != null) {
                val existing = downloadManager.currentDownloads.firstOrNull { it.request.id == request.id }
                    ?: downloadUtil.downloads.value[request.id]
                if (existing?.state == Download.STATE_COMPLETED &&
                    com.nikhil.yt.playback.audio.AudioCacheIdentity.isComplete(downloadUtil.downloadCache, request.id)) {
                    downloadUtil.repairArtwork(request.id)
                    intent.action = ACTION_INIT
                    return super.onStartCommand(intent, flags, startId)
                }
                if (existing?.state == Download.STATE_FAILED) {
                    com.nikhil.yt.playback.audio.CapsuleAudioEngine.clearDownloadClientFailures(request.id)
                }
                // Media3 addDownload merges an existing active request into STATE_QUEUED,
                // cancelling its downloader even when its bytes/URI did not change. Ignore
                // duplicate bulk submissions instead of restarting healthy transfers.
                val running = keepActiveDownload(existing)
                if (running) {
                    intent.action = ACTION_INIT
                    return super.onStartCommand(intent, flags, startId)
                }
                val merged = if (existing?.request?.isManualDownload() == true) request.withManualPriority() else request
                intent.putExtra(KEY_DOWNLOAD_REQUEST, merged)
                intent.putExtra(KEY_STOP_REASON, DOWNLOAD_WAITING_FOR_SLOT)
            }
        }
        if (intent?.action == REMOVE_ALL_PENDING_DOWNLOADS) {
            downloadManager.currentDownloads.forEach { download ->
                downloadManager.removeDownload(download.request.id)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }


    override fun getDownloadManager() = downloadUtil.downloadManager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification =
        Notification.Builder.recoverBuilder(
            this, downloadUtil.downloadNotificationHelper.buildProgressNotification(
                this,
                R.drawable.download,
                null,
                if (downloads.size == 1) downloads[0].request.downloadTitle()
                else resources.getQuantityString(R.plurals.n_song, downloads.size, downloads.size),
                downloads,
                notMetRequirements
            )
        ).addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.close),
                getString(android.R.string.cancel),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, ExoDownloadService::class.java).setAction(
                        REMOVE_ALL_PENDING_DOWNLOADS
                    ),
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build()
        ).build()


    /**
     * This helper will outlive the lifespan of a single instance of [ExoDownloadService]
     */
    class TerminalStateNotificationHelper(
        private val context: Context,
        private val notificationHelper: DownloadNotificationHelper,
        private var nextNotificationId: Int,
    ) : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            if (download.state == Download.STATE_FAILED) {
                val notification = notificationHelper.buildDownloadFailedNotification(
                    context,
                    R.drawable.error,
                    null,
                    download.request.downloadTitle()
                )
                NotificationUtil.setNotification(context, nextNotificationId++, notification)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "download"
        const val NOTIFICATION_ID = 1
        const val JOB_ID = 1
        const val REMOVE_ALL_PENDING_DOWNLOADS = "REMOVE_ALL_PENDING_DOWNLOADS"
    }
}
