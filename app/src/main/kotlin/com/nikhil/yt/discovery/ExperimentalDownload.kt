package com.nikhil.yt.discovery

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import com.nikhil.yt.R
import com.nikhil.yt.innertube.YouTube
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

/**
 * Independent licensed-audio downloads, never routed through the YouTube
 * downloader or the app's YouTube CDN identity/quarantine.
 *
 * Android DownloadManager doesn't inherit Capsule's configured proxy; this
 * caused playback to work through the proxy while downloading the SAME URL
 * failed. On Android Q+ stream into the public Downloads collection using
 * the app's own proxy-aware HTTPS transport and a bounded IO coroutine.
 * Legacy devices keep system DownloadManager.
 */
object ExperimentalDownload {
    private const val MAX_BYTES = 240L * 1024 * 1024
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(2)
    private val main = Handler(Looper.getMainLooper())
    enum class State { DOWNLOADING, COMPLETED, FAILED }
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states = _states.asStateFlow()
    private fun updateStatus(id: String, status: State) {
        _states.update { current ->
            val bounded = if (current.size > 200) emptyMap() else current
            bounded + (id to status)
        }
    }

    fun enqueue(context: Context, track: ExperimentalCatalog.Track): Boolean {
        val address = track.downloadUrl ?: return false
        val uri = Uri.parse(address)
        if (uri.scheme != "https" || uri.host.isNullOrBlank() ||
            track.source == ExperimentalCatalog.Source.RADIO) return false
        val app = context.applicationContext
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
        val name = "${track.artist} - ${track.title}"
            .replace(Regex("[^\\p{L}\\p{N} _().-]"), "_")
            .trim().take(95).ifBlank { "Capsule track" }
        val mime = when (extension) {
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            else -> "audio/mpeg"
        }
        val displayName = "$name.$extension"

        if (Build.VERSION.SDK_INT < 29) return legacyEnqueue(app, uri, displayName, mime)
        if (_states.value[track.mediaId] == State.DOWNLOADING) return false
        updateStatus(track.mediaId, State.DOWNLOADING)
        jobs.launch {
            permits.withPermit {
                var destination: Uri? = null
                try {
                    val provider = app.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/Capsule Music")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val safeUri = provider.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                    ) ?: throw IllegalStateException("No available Downloads destination")
                    destination = safeUri
                    val conn = openDownload(address)
                    try {
                        val size = conn.contentLengthLong
                        if (size > MAX_BYTES) throw IllegalStateException("Recording exceeds size limit")
                        val output = provider.openOutputStream(safeUri, "w")
                            ?: throw IllegalStateException("Cannot open downloaded file")
                        output.use { out ->
                            conn.inputStream.use { input ->
                                val buffer = ByteArray(32 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > MAX_BYTES) throw IllegalStateException(
                                        "Recording exceeds size limit")
                                    out.write(buffer, 0, count)
                                }
                                if (total == 0L) throw IllegalStateException("Empty recording")
                            }
                        }
                    } finally { conn.disconnect() }
                    provider.update(safeUri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null)
                    updateStatus(track.mediaId, State.COMPLETED)
                    toast(app, R.string.capsule_discovery_download_completed)
                } catch (e: Exception) {
                    updateStatus(track.mediaId, State.FAILED)
                    destination?.let { runCatching { app.contentResolver.delete(it, null, null) } }
                    Timber.tag("CapsuleDownload").w(e, "External source download failed")
                    toast(app, R.string.capsule_discovery_download_failed)
                }
            }
        }
        toast(app, R.string.capsule_discovery_download_started)
        return true
    }

    private fun openDownload(url: String): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol == "https") { "Only HTTPS media downloads supported" }
        val proxy = YouTube.streamProxy
        val conn = (if (proxy == null) parsed.openConnection()
            else parsed.openConnection(proxy)) as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "CapsuleMusic/1.0")
        conn.setRequestProperty("Accept", "audio/*,application/octet-stream")
        if (conn.responseCode !in 200..299) {
            val code = conn.responseCode
            conn.disconnect()
            throw IllegalStateException("Remote recording HTTP $code")
        }
        val contentType = conn.contentType.orEmpty().lowercase()
        if (contentType.contains("text/html") || contentType.contains("json")) {
            conn.disconnect()
            throw IllegalStateException("Publisher returned a web page instead of audio")
        }
        return conn
    }

    private fun legacyEnqueue(context: Context, uri: Uri, name: String, mime: String): Boolean =
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return false
            dm.enqueue(DownloadManager.Request(uri)
                .setTitle(name)
                .setMimeType(mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "Capsule Music/$name"))
            toast(context, R.string.capsule_discovery_download_started)
            true
        } catch (e: Exception) {
            Timber.tag("CapsuleDownload").w(e, "Legacy download failed")
            toast(context, R.string.capsule_discovery_download_failed)
            false
        }

    private fun toast(context: Context, message: Int) = main.post {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
