package com.nikhil.yt.discovery

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.nikhil.yt.R
import com.nikhil.yt.playback.ExoDownloadService
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Persist approved-source downloads across Android process death, with a finite retry budget. */
class IndependentAudioDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (android.os.Build.VERSION.SDK_INT < 29) return Result.failure()
        val track = track(inputData) ?: return Result.failure()
        val name = inputData.getString("name") ?: return Result.failure()
        val mime = inputData.getString("mime") ?: return Result.failure()
        if (runAttemptCount >= 3) {
            ExperimentalDownload.workerFailed(applicationContext, track.mediaId)
            return Result.failure()
        }
        return try {
            setForeground(foregroundInfo(track))
            ExperimentalDownload.downloadApproved(applicationContext, track, name, mime, id.toString())
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            if (runAttemptCount < 2) Result.retry() else {
                ExperimentalDownload.workerFailed(applicationContext, track.mediaId)
                Result.failure()
            }
        } catch (_: Exception) {
            ExperimentalDownload.workerFailed(applicationContext, track.mediaId)
            Result.failure()
        }
    }

    @androidx.annotation.RequiresApi(29)
    private fun foregroundInfo(track: ExperimentalCatalog.Track): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            ExoDownloadService.CHANNEL_ID, applicationContext.getString(R.string.downloading),
            NotificationManager.IMPORTANCE_LOW,
        ))
        val notification = NotificationCompat.Builder(applicationContext, ExoDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.download).setContentTitle(track.title).setContentText(track.artist)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(R.drawable.close, applicationContext.getString(android.R.string.cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(20_000 + (id.hashCode() and 0x7fff), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        internal fun input(track: ExperimentalCatalog.Track, name: String, mime: String): Data = workDataOf(
            "source" to track.source.name, "id" to track.id, "title" to track.title,
            "artist" to track.artist, "artwork" to track.artwork,
            "artworkLarge" to track.artworkLarge, "stream" to track.streamUrl,
            "license" to track.license, "downloadable" to track.downloadable,
            "name" to name, "mime" to mime,
        )

        internal fun track(data: Data): ExperimentalCatalog.Track? = runCatching {
            ExperimentalCatalog.Track(
                source = ExperimentalCatalog.Source.valueOf(requireNotNull(data.getString("source"))),
                id = requireNotNull(data.getString("id")), title = requireNotNull(data.getString("title")),
                artist = requireNotNull(data.getString("artist")), artwork = data.getString("artwork"),
                artworkLarge = data.getString("artworkLarge"), streamUrl = data.getString("stream"),
                sourcePage = "", license = data.getString("license"),
                downloadable = data.getBoolean("downloadable", false),
            )
        }.getOrNull()
    }
}
