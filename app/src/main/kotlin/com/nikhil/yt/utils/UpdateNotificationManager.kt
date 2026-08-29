
 /** Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nikhil.yt.BuildConfig
import com.nikhil.yt.MainActivity
import com.nikhil.yt.R
import com.nikhil.yt.constants.EnableUpdateNotificationKey
import com.nikhil.yt.constants.LastNotifiedVersionKey
import com.nikhil.yt.constants.LastUpdateCheckKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object UpdateNotificationManager {
    private const val CHANNEL_ID = "update_notification_channel"
    private const val NOTIFICATION_ID = 9999
    private const val WORK_NAME = "update_check_work"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun normalizeVersion(value: String): String =
        value
            .removePrefix("Capsule ")
            .removePrefix("capsule ")
            .removePrefix("v")
            .trim()

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "capsule updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifications for new capsule releases"
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    fun schedulePeriodicUpdateCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val updateCheckRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            6,
            TimeUnit.HOURS,
            30,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            updateCheckRequest,
        )
    }

    fun cancelPeriodicUpdateCheck(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun checkForUpdates(context: Context) {
        scope.launch {
            try {
                val dataStore = context.dataStore

                val isEnabled = dataStore.data
                    .map { it[EnableUpdateNotificationKey] ?: false }
                    .first()

                if (!isEnabled) {
                    cancelPeriodicUpdateCheck(context)
                    return@launch
                }

                schedulePeriodicUpdateCheck(context)

                val lastCheck = dataStore.data
                    .map { it[LastUpdateCheckKey] ?: 0L }
                    .first()
                val now = System.currentTimeMillis()

                if (now - lastCheck < CHECK_INTERVAL_MS) return@launch

                dataStore.edit { it[LastUpdateCheckKey] = now }

                Updater.getLatestVersionName().onSuccess { latestVersion ->
                    if (normalizeVersion(latestVersion) != normalizeVersion(BuildConfig.VERSION_NAME)) {
                        notifyIfNewVersion(context, latestVersion)
                    }
                }
            } catch (_: Exception) {
                // Update checks must never affect playback or app startup.
            }
        }
    }

    suspend fun notifyIfNewVersion(context: Context, latestVersion: String) {
        try {
            val dataStore = context.dataStore
            val normalizedLatest = normalizeVersion(latestVersion)
            val normalizedCurrent = normalizeVersion(BuildConfig.VERSION_NAME)
            val normalizedLastNotified = normalizeVersion(
                dataStore.data.map { it[LastNotifiedVersionKey] ?: "" }.first(),
            )

            if (
                normalizedLatest.isNotBlank() &&
                normalizedLatest != normalizedCurrent &&
                normalizedLatest != normalizedLastNotified
            ) {
                showUpdateNotification(context, normalizedLatest)
                dataStore.edit { it[LastNotifiedVersionKey] = normalizedLatest }
            }
        } catch (_: Exception) {
            // Notifications are optional.
        }
    }

    private fun showUpdateNotification(context: Context, newVersion: String) {
        createNotificationChannel(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("navigate_to", "settings/update")
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val downloadIntent = Intent(Intent.ACTION_VIEW, Uri.parse(Updater.getLatestDownloadUrl()))
        val downloadPendingIntent = PendingIntent.getActivity(
            context,
            1,
            downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_velune_concept)
            .setContentTitle("capsule update available")
            .setContentText("capsule $newVersion is now available")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppPendingIntent)
            .setAutoCancel(true)
            .addAction(
                R.drawable.download,
                "Download",
                downloadPendingIntent,
            )
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Missing POST_NOTIFICATIONS permission.
        }
    }

    fun cancelUpdateNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
