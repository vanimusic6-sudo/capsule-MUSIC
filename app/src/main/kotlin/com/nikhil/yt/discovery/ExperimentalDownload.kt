package com.nikhil.yt.discovery

import android.app.DownloadManager
import android.content.ContentValues
import android.content.ContentUris
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
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
    /**
     * The native app library reads Media3's DownloadIndex, while independent
     * creator-approved Audius files are saved through Android MediaStore.
     * Expose the latter explicitly so the library's Downloaded filter can
     * combine both without lying to YouTube's offline cache/index.
     *
     * Works after process death and across upgrades; only reads our own
     * Downloads/Capsule Music folder, not the user's other downloads.
     */
    private const val METADATA_STORE = "capsule_independent_downloads_v1"

    data class SavedAudio(
        val id: Long,
        val uri: Uri,
        val name: String,
        val bytes: Long,
        val savedTitle: String? = null,
        val savedArtist: String? = null,
        val artwork: String? = null,
    ) {
        // Older releases saved only a filename; those recordings remain playable.
        val title: String get() = savedTitle?.takeIf(String::isNotBlank)
            ?: name.substringBeforeLast('.').substringAfter(" - ", name.substringBeforeLast('.'))
        val artist: String get() = savedArtist?.takeIf(String::isNotBlank)
            ?: name.substringBefore(" - ", "Audius")
    }

    suspend fun savedFiles(context: Context): List<SavedAudio> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext emptyList()
        try {
            val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
            )
            val where = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0"
            val folder = Environment.DIRECTORY_DOWNLOADS + "/Capsule Music/%"
            val result = mutableListOf<SavedAudio>()
            val metadata = context.getSharedPreferences(METADATA_STORE, Context.MODE_PRIVATE)
            var remainingMigrations = 12
            context.contentResolver.query(
                uri, projection, where, arrayOf(folder),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext() && result.size < 300) {
                    val name = cursor.getString(nameColumn) ?: continue
                    if (!listOf(".mp3", ".ogg", ".oga", ".opus", ".m4a", ".flac")
                            .any { name.endsWith(it, ignoreCase = true) }) continue
                    val id = cursor.getLong(idColumn)
                    val bytes = cursor.getLong(sizeColumn)
                    if (bytes <= 0L) continue
                    // Read embedded ID3/MP4 tags from legacy downloads without modifying audio.
                    if (remainingMigrations > 0 &&
                        !metadata.getBoolean("scanned:$id", false) &&
                        (metadata.getString("title:$id", null) == null ||
                            metadata.getString("art:$id", null) == null)
                    ) {
                        remainingMigrations--
                        recoverEmbeddedMetadata(context, id, ContentUris.withAppendedId(uri, id))
                    }
                    result.add(SavedAudio(
                        id = id,
                        uri = ContentUris.withAppendedId(uri, id),
                        name = name,
                        bytes = bytes,
                        savedTitle = metadata.getString("title:$id", null),
                        savedArtist = metadata.getString("artist:$id", null),
                        artwork = metadata.getString("art:$id", null),
                    ))
                }
            }
            result
        } catch (e: Exception) {
            Timber.tag("CapsuleDownload").w(e, "Cannot read saved music from MediaStore")
            emptyList()
        }
    }

    private fun recoverEmbeddedMetadata(context: Context, id: Long, uri: Uri) {
        val prefs = context.getSharedPreferences(METADATA_STORE, Context.MODE_PRIVATE)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf(String::isNotBlank)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf(String::isNotBlank)
            val image = retriever.embeddedPicture?.takeIf { it.size in 1..1_500_000 }
                ?.let { bytes ->
                    runCatching {
                        val folder = File(context.filesDir, "external-artwork").apply { mkdirs() }
                        val file = File(folder, "legacy-$id.img")
                        FileOutputStream(file).use { it.write(bytes) }
                        Uri.fromFile(file).toString()
                    }.getOrNull()
                }
            prefs.edit().apply {
                if (prefs.getString("title:$id", null) == null && title != null)
                    putString("title:$id", title)
                if (prefs.getString("artist:$id", null) == null && artist != null)
                    putString("artist:$id", artist)
                if (prefs.getString("art:$id", null) == null && image != null)
                    putString("art:$id", image)
                putBoolean("scanned:$id", true)
            }.apply()
        } catch (_: Exception) {
            prefs.edit().putBoolean("scanned:$id", true).apply()
        } finally { runCatching { retriever.release() } }
    }

    enum class State { DOWNLOADING, COMPLETED, FAILED }
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states = _states.asStateFlow()
    private val _savedFilesRevision = MutableStateFlow(0L)
    val savedFilesRevision = _savedFilesRevision.asStateFlow()
    private fun updateStatus(id: String, status: State) {
        _states.update { current ->
            val bounded = if (current.size > 200) emptyMap() else current
            bounded + (id to status)
        }
    }

    /**
     * Permanently remove only an app-owned Capsule Music recording.
     * Never touch the YouTube Media3 DownloadIndex or its separate caches.
     * A failed or unauthorized MediaStore operation leaves metadata untouched.
     */
    suspend fun deleteSaved(context: Context, file: SavedAudio): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29 ||
            file.uri.scheme != "content" || file.uri.authority != "media") {
            return@withContext false
        }
        try {
            val resolver = context.contentResolver
            val folder = resolver.query(
                file.uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: return@withContext false
            val expected = Environment.DIRECTORY_DOWNLOADS + "/Capsule Music/"
            if (!folder.replace('\\', '/').startsWith(expected, ignoreCase = true))
                return@withContext false
            if (resolver.delete(file.uri, null, null) != 1) return@withContext false
            val prefs = context.getSharedPreferences(METADATA_STORE, Context.MODE_PRIVATE)
            val oldMediaId = prefs.getString("mediaId:${file.id}", null)
            prefs.edit().remove("title:${file.id}").remove("artist:${file.id}")
                .remove("art:${file.id}").remove("scanned:${file.id}")
                .remove("mediaId:${file.id}").apply()
            // Only remove files from our app-private image folder; do not follow
            // arbitrary URI paths from MediaStore metadata or user-supplied names.
            val covers = File(context.filesDir, "external-artwork")
            File(covers, "${file.id}.img").delete()
            File(covers, "legacy-${file.id}.img").delete()
            if (oldMediaId != null) _states.update { it - oldMediaId }
            _savedFilesRevision.update { it + 1L }
            true
        } catch (error: Exception) {
            Timber.tag("CapsuleDownload").w(error, "Cannot delete saved external recording")
            false
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
                    val published = provider.update(safeUri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null)
                    if (published < 1) throw IllegalStateException("Cannot publish downloaded recording")
                    // Persist real track credits. Cover images are saved privately for offline use;
                    // a failed image request must never invalidate a successful music download.
                    val savedId = ContentUris.parseId(safeUri)
                    val image = saveArtwork(app, savedId, track)
                    app.getSharedPreferences(METADATA_STORE, Context.MODE_PRIVATE).edit()
                        .putString("title:$savedId", track.title)
                        .putString("artist:$savedId", track.artist)
                        .putString("mediaId:$savedId", track.mediaId)
                        .putString("art:$savedId", image ?: (track.artworkLarge ?: track.artwork)
                            ?.takeIf { it.startsWith("https://") })
                        .apply()
                    updateStatus(track.mediaId, State.COMPLETED)
                    _savedFilesRevision.update { it + 1L }
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

    private fun saveArtwork(context: Context, id: Long, track: ExperimentalCatalog.Track): String? {
        val url = (track.artworkLarge ?: track.artwork)
            ?.takeIf { it.startsWith("https://") } ?: return null
        val destination = File(File(context.filesDir, "external-artwork").apply { mkdirs() }, "$id.img")
        var connection: HttpURLConnection? = null
        return try {
            val address = URL(url)
            val proxy = YouTube.streamProxy
            connection = (if (proxy == null) address.openConnection() else address.openConnection(proxy))
                as HttpURLConnection
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.setRequestProperty("Accept", "image/*")
            if (connection.responseCode !in 200..299 ||
                !connection.contentType.orEmpty().lowercase().startsWith("image/") ||
                connection.contentLengthLong > 1_500_000L
            ) return null
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { out ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > 1_500_000L) throw IllegalStateException("Artwork too large")
                        out.write(buffer, 0, n)
                    }
                    if (total == 0L) throw IllegalStateException("Empty artwork")
                }
            }
            Uri.fromFile(destination).toString()
        } catch (e: Exception) {
            destination.delete()
            Timber.tag("CapsuleDownload").d(e, "Artwork unavailable for offline recording")
            null
        } finally { connection?.disconnect() }
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
