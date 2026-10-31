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
import com.nikhil.yt.playback.audio.DownloadYieldingInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
        val sourceMediaId: String? = null,
    ) {
        /** Display the real source without trying to reconstruct a missing API account. */
        val sourceLabel: String get() = when {
            sourceMediaId?.startsWith("capsule-open:openverse:") == true -> "Openverse"
            sourceMediaId?.startsWith("capsule-open:archive:") == true -> "Internet Archive"
            sourceMediaId?.startsWith("capsule-open:ccmixter:") == true -> "ccMixter"
            sourceMediaId?.startsWith("capsule-open:funkwhale:") == true -> "Funkwhale"
            else -> "Audius" // Legacy files did not persist provider identity.
        }

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
            val where = "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?) AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0"
            val relativeFolder = Environment.DIRECTORY_DOWNLOADS + "/Capsule Music"
            val folders = arrayOf(relativeFolder, "$relativeFolder/%")
            val result = mutableListOf<SavedAudio>()
            val metadata = context.getSharedPreferences(METADATA_STORE, Context.MODE_PRIVATE)
            var remainingMigrations = 12
            context.contentResolver.query(
                uri, projection, where, folders,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    if (!listOf(".mp3", ".ogg", ".oga", ".opus", ".m4a", ".aac", ".flac")
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
                        sourceMediaId = metadata.getString("mediaId:$id", null),
                    ))
                }
            }
            result
        } catch (e: Exception) {
            Timber.tag("CapsuleDownload").w("Cannot read saved music type=%s", e.javaClass.simpleName)
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
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst() ||
                    cursor.getString(1) != context.packageName) null
                else cursor.getString(0)
            } ?: return@withContext false
            val expected = Environment.DIRECTORY_DOWNLOADS + "/Capsule Music"
            val relative = folder.replace('\\', '/').trimEnd('/')
            if (!relative.equals(expected, ignoreCase = true) &&
                !relative.startsWith("$expected/", ignoreCase = true))
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
            if (oldMediaId != null) {
                prefs.edit().remove("completed:$oldMediaId").remove("completed-owner:$oldMediaId").apply()
                _states.update { it - oldMediaId }
            }
            _savedFilesRevision.update { it + 1L }
            true
        } catch (error: Exception) {
            Timber.tag("CapsuleDownload").w("Cannot delete saved recording type=%s", error.javaClass.simpleName)
            false
        }
    }

    fun cancelInProgress(context: Context, mediaId: String) {
        if (_states.value[mediaId] != State.DOWNLOADING) return
        // Worker owns pending MediaStore bytes and removes them on cancellation.
        androidx.work.WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("capsule-independent:$mediaId")
        updateStatus(mediaId, State.FAILED)
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
                "aac" -> "aac"
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
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            else -> "audio/mpeg"
        }
        val displayName = "$name.$extension"

        if (Build.VERSION.SDK_INT < 29) return legacyEnqueue(app, uri, displayName, mime)
        if (_states.value[track.mediaId] == State.DOWNLOADING) return false
        val workInput = try { IndependentAudioDownloadWorker.input(track, displayName, mime) }
            catch (_: IllegalStateException) { return false }
        updateStatus(track.mediaId, State.DOWNLOADING)
        androidx.work.WorkManager.getInstance(app).enqueueUniqueWork(
            "capsule-independent:${track.mediaId}", androidx.work.ExistingWorkPolicy.KEEP,
            androidx.work.OneTimeWorkRequestBuilder<IndependentAudioDownloadWorker>()
                .setInputData(workInput)
                .setConstraints(androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
        toast(app, R.string.capsule_discovery_download_started)
        return true
    }

    /** Durable worker entry point. Pending rows are never visible in Downloaded. */
    @androidx.annotation.RequiresApi(29)
    internal suspend fun downloadApproved(
        app: Context, track: ExperimentalCatalog.Track, displayName: String, mime: String, workId: String,
    ): Unit = withContext(Dispatchers.IO) {
        val address = track.downloadUrl ?: throw java.io.IOException("Publisher did not permit this download")
        com.nikhil.yt.playback.AudioDownloadSlots.withSlot {
            updateStatus(track.mediaId, State.DOWNLOADING)
            var destination: Uri? = null
            try {
                val provider = app.contentResolver
                val pending = app.getSharedPreferences(METADATA_STORE, Context.MODE_PRIVATE)
                val pendingKey = "pending:${track.mediaId}"
                val pendingOwner = "pending-owner:${track.mediaId}"
                val completedOwner = "completed-owner:${track.mediaId}"
                val previous = ownedWorkReference(pending, track.mediaId, workId)
                if (previous != null && previous.scheme == "content" && previous.authority == "media") {
                    val previousState = provider.query(previous,
                        arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE,
                            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                        null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) null else {
                            val folder = cursor.getString(2).orEmpty().trimEnd('/')
                            val owned = folder == Environment.DIRECTORY_DOWNLOADS + "/Capsule Music" &&
                                cursor.getString(3) == app.packageName
                            owned to (cursor.getInt(0) == 0 && cursor.getLong(1) > 0L)
                        }
                    }
                    if (previousState?.first == true) {
                        if (previousState.second) {
                            pending.edit().putString("completed:${track.mediaId}", previous.toString())
                                .putString(completedOwner, workId).remove(pendingKey).remove(pendingOwner).commit()
                            updateStatus(track.mediaId, State.COMPLETED)
                            _savedFilesRevision.update { it + 1L }
                            return@withSlot
                        }
                        provider.delete(previous, null, null)
                    }
                    // Restored URI IDs may refer to another file on a new device.
                    pending.edit().remove("completed:${track.mediaId}").remove(pendingKey).commit()
                }
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
                if (!pending.edit().putString(pendingKey, safeUri.toString())
                    .putString(pendingOwner, workId).commit()) {
                    throw java.io.IOException("Cannot persist download destination")
                }
                val conn = openDownload(address)
                try {
                    val size = conn.contentLengthLong
                    if (size > MAX_BYTES) throw IllegalStateException("Recording exceeds size limit")
                    val output = provider.openOutputStream(safeUri, "w")
                        ?: throw IllegalStateException("Cannot open downloaded file")
                    output.use { out ->
                        DownloadYieldingInputStream(conn.inputStream).use { input ->
                            // WorkManager's independent Audius copy must not saturate
                            // the same network while a foreground YouTube/Audius
                            // player is buffering. This yields reads only and keeps
                            // the original file/MediaStore cancellation contract.
                            val coroutine = currentCoroutineContext()
                            VerifiedAudioCopy.copy(input, out, size, MAX_BYTES) { coroutine.ensureActive() }
                        }
                    }
                } finally { conn.disconnect() }
                currentCoroutineContext().ensureActive()
                // Persist real track credits. Cover images are saved privately for offline use;
                // a failed image request must never invalidate a successful music download.
                val savedId = ContentUris.parseId(safeUri)
                val image = saveArtwork(app, savedId, track)
                val creditsSaved = app.getSharedPreferences(METADATA_STORE, Context.MODE_PRIVATE).edit()
                    .putString("title:$savedId", track.title)
                    .putString("artist:$savedId", track.artist)
                    .putString("mediaId:$savedId", track.mediaId)
                    .putString("art:$savedId", image ?: (track.artworkLarge ?: track.artwork)
                        ?.takeIf { it.startsWith("https://") })
                    .commit()
                if (!creditsSaved) throw java.io.IOException("Cannot persist recording metadata")
                currentCoroutineContext().ensureActive()
                val published = provider.update(safeUri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                if (published < 1) throw IllegalStateException("Cannot publish downloaded recording")
                pending.edit().putString("completed:${track.mediaId}", safeUri.toString())
                    .putString(completedOwner, workId).remove(pendingKey).remove(pendingOwner).commit()
                updateStatus(track.mediaId, State.COMPLETED)
                _savedFilesRevision.update { it + 1L }
                toast(app, R.string.capsule_discovery_download_completed)
            } catch (cancelled: CancellationException) {
                updateStatus(track.mediaId, State.FAILED)
                destination?.let { runCatching { app.contentResolver.delete(it, null, null) } }
                throw cancelled
            } catch (e: Exception) {
                updateStatus(track.mediaId, State.FAILED)
                destination?.let { runCatching { app.contentResolver.delete(it, null, null) } }
                Timber.tag("CapsuleDownload").w("External source download failed type=%s", e.javaClass.simpleName)
                throw e
            }
        }
    }

    internal fun ownedWorkReference(
        preferences: android.content.SharedPreferences, mediaId: String, workId: String,
    ): Uri? {
        val completed = preferences.getString("completed:$mediaId", null)
            ?.takeIf { preferences.getString("completed-owner:$mediaId", null) == workId }
        val pending = preferences.getString("pending:$mediaId", null)
            ?.takeIf { preferences.getString("pending-owner:$mediaId", null) == workId }
        return (completed ?: pending)?.let(Uri::parse)
    }

    internal fun workerFailed(context: Context, mediaId: String) {
        updateStatus(mediaId, State.FAILED)
        toast(context, R.string.capsule_discovery_download_failed)
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
            Timber.tag("CapsuleDownload").d("Artwork unavailable for offline recording type=%s", e.javaClass.simpleName)
            null
        } finally { connection?.disconnect() }
    }

    private fun openDownload(url: String): HttpURLConnection {
        var address = url
        repeat(6) {
            val parsed = com.nikhil.yt.playback.audio.AudioDownloadUrlPolicy.requireHttps(address).toURL()
            val proxy = YouTube.streamProxy
            val conn = (if (proxy == null) parsed.openConnection()
                else parsed.openConnection(proxy)) as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "CapsuleMusic/1.0")
            conn.setRequestProperty("Accept", "audio/*,application/octet-stream")
            try {
                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                        ?: throw java.io.IOException("Download redirect has no destination")
                    address = URL(parsed, location).toString()
                    conn.disconnect()
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) throw java.io.IOException("Remote recording HTTP $code")
                val contentType = conn.contentType.orEmpty().lowercase()
                if (contentType.contains("text/html") || contentType.contains("json")) {
                    throw java.io.IOException("Publisher returned a web page instead of audio")
                }
                return conn
            } catch (failure: Exception) {
                conn.disconnect()
                throw failure
            }
        }
        throw java.io.IOException("Too many download redirects")
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
