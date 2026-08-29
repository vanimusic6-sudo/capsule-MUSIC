/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nikhil.yt.constants.EnableUpdateNotificationKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val dataStore = applicationContext.dataStore
            val isEnabled = dataStore.data
                .map { it[EnableUpdateNotificationKey] ?: false }
                .first()

            if (!isEnabled) return Result.success()

            Updater.getLatestVersionName().onSuccess { latestVersion ->
                UpdateNotificationManager.notifyIfNewVersion(
                    applicationContext,
                    latestVersion,
                )
            }

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
