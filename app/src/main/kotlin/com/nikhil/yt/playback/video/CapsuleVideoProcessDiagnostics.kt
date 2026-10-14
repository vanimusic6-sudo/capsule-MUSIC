package com.nikhil.yt.playback.video

import android.content.Context
import java.io.File
import timber.log.Timber

/** Remote process crashes cannot reach the main process's Timber tree. */
internal object CapsuleVideoProcessDiagnostics {
    private const val FILE_NAME = "capsule-video-extractor-crash.txt"
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            runCatching {
                File(context.filesDir, FILE_NAME).writeText(failure.stackTraceToString().take(16_384))
            }
            previous?.uncaughtException(thread, failure)
                ?: android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
    fun reportRecentCrash(context: Context) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < 60_000L) {
                Timber.tag("CapsuleVideo").e("VIDEO extractor crash: %s", file.readText().take(16_384))
                file.delete()
            }
        }
    }
}
