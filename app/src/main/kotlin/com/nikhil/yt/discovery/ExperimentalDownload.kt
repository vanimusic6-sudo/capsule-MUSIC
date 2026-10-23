package com.nikhil.yt.discovery

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import com.nikhil.yt.R
import timber.log.Timber

/**
 * External sources MUST NOT enter the YouTube offline/download resolver:
 * that code resolves media IDs through InnerTube and would fail or risk the
 * stable audio CDN state. Use the Android system download provider instead.
 *
 * Explicit user action only. Audius original downloads require is_downloadable;
 * Creative Commons catalogs require a license and an actual HTTPS audio URL.
 * Downloads become ordinary files in the device's Downloads/Capsule Music folder.
 */
object ExperimentalDownload {
    fun enqueue(context: Context, track: ExperimentalCatalog.Track): Boolean {
        val url = track.downloadUrl ?: return false
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return false
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return false
        val extension = when {
            track.source == ExperimentalCatalog.Source.AUDIUS -> "mp3"
            else -> when (uri.lastPathSegment?.substringAfterLast('.', "").orEmpty().lowercase()) {
                "ogg", "oga" -> "ogg"
                "opus" -> "opus"
                "m4a" -> "m4a"
                "flac" -> "flac"
                "wav" -> "wav"
                else -> "mp3"
            }
        }
        val base = "${track.artist} - ${track.title}"
            .replace(Regex("[^\\p{L}\\p{N} _().-]"), "_")
            .trim().take(90).ifBlank { "Capsule track" }
        return try {
            val request = DownloadManager.Request(uri)
                .setTitle(base)
                .setDescription(track.source.title)
                .setMimeType(
                    when (extension) {
                        "flac" -> "audio/flac"
                        "ogg", "opus" -> "audio/ogg"
                        "m4a" -> "audio/mp4"
                        "wav" -> "audio/wav"
                        else -> "audio/mpeg"
                    }
                )
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "Capsule Music/$base.$extension",
                )
            manager.enqueue(request)
            Toast.makeText(context, R.string.capsule_discovery_download_started, Toast.LENGTH_SHORT)
                .show()
            true
        } catch (e: Exception) {
            Timber.tag("CapsuleDownload").w(e, "Independent catalog file download could not start")
            Toast.makeText(context, R.string.capsule_discovery_download_failed, Toast.LENGTH_SHORT)
                .show()
            false
        }
    }
}
