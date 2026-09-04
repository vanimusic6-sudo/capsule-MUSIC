/*
 * Capsule MUSIC
 * Durable playback queue and player-state persistence.
 *
 * GPL-3.0
 */

package com.nikhil.yt.playback

import android.content.Context
import com.nikhil.yt.extensions.SilentHandler
import com.nikhil.yt.models.PersistPlayerState
import com.nikhil.yt.models.PersistQueue
import com.nikhil.yt.utils.reportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
const val PERSISTENT_AUTOMIX_FILE = "persistent_automix.data"
const val PERSISTENT_PLAYER_STATE_FILE = "persistent_player_state.data"

internal data class PersistentPlaybackSnapshot(
    val queue: PersistQueue,
    val automix: PersistQueue,
    val playerState: PersistPlayerState,
)

/**
 * Owns debounce jobs, progress checkpoints and atomic file IO. Player state is
 * still captured by MusicService through small main-thread callbacks; this
 * class never reaches into ExoPlayer from an IO thread.
 */
internal class PlaybackPersistence(
    context: Context,
    private val mainScope: () -> CoroutineScope,
    private val persistenceEnabled: suspend () -> Boolean,
    private val snapshotProvider: () -> PersistentPlaybackSnapshot?,
    private val playerStateProvider: () -> PersistPlayerState?,
    private val isPlayingProvider: () -> Boolean,
) {
    private val filesDir = context.filesDir
    private val stateLock = Any()
    private val flushScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var progressJob: Job? = null
    private var queueSaveJob: Job? = null
    private var playerStateSaveJob: Job? = null

    fun scheduleQueueSave(delayMs: Long = SAVE_DEBOUNCE_MS) {
        queueSaveJob?.cancel()
        queueSaveJob =
            mainScope().launch {
                delay(delayMs)
                if (!withContext(Dispatchers.IO) { persistenceEnabled() }) return@launch
                saveQueueToDisk()
            }
    }

    fun schedulePlayerStateSave(
        syncToDisk: Boolean,
        delayMs: Long = SAVE_DEBOUNCE_MS,
    ) {
        playerStateSaveJob?.cancel()
        playerStateSaveJob =
            mainScope().launch {
                delay(delayMs)
                if (!withContext(Dispatchers.IO) { persistenceEnabled() }) return@launch
                savePlayerStateToDisk(syncToDisk)
            }
    }

    fun updateProgressCheckpoint(isPlaying: Boolean) {
        progressJob?.cancel()
        progressJob = null

        if (!isPlaying) {
            schedulePlayerStateSave(syncToDisk = true, delayMs = 0L)
            return
        }

        progressJob =
            mainScope().launch {
                while (isActive && isPlayingProvider()) {
                    delay(PROGRESS_INTERVAL_MS)
                    if (!isPlayingProvider()) break
                    if (withContext(Dispatchers.IO) { persistenceEnabled() }) {
                        savePlayerStateToDisk(syncToDisk = false)
                    }
                }
            }
    }

    fun cancelPending() {
        progressJob?.cancel()
        progressJob = null
        queueSaveJob?.cancel()
        queueSaveJob = null
        playerStateSaveJob?.cancel()
        playerStateSaveJob = null
    }

    fun <T> read(fileName: String, type: Class<T>): T? {
        val persistentFile = filesDir.resolve(fileName)
        if (!persistentFile.exists() || !persistentFile.isFile) return null

        return synchronized(stateLock) {
            runCatching {
                persistentFile.inputStream().use { fileInput ->
                    ObjectInputStream(fileInput).use { input ->
                        type.cast(input.readObject())
                    }
                }
            }.onFailure { error ->
                Timber.tag(TAG).w(error, "Failed to read persistent file: %s", fileName)
                runCatching { persistentFile.delete() }
                    .onFailure { deleteError ->
                        Timber.tag(TAG).w(deleteError, "Failed to delete invalid persistent file: %s", fileName)
                    }
            }.getOrNull()
        }
    }

    fun flush(
        snapshot: PersistentPlaybackSnapshot,
        syncToDisk: Boolean = true,
        onComplete: suspend () -> Unit = {},
    ): Job =
        flushScope.launch(SilentHandler) {
            writeSnapshot(snapshot, syncToDisk)
            onComplete()
        }

    private suspend fun saveQueueToDisk() {
        val snapshot =
            withContext(Dispatchers.Main.immediate) {
                snapshotProvider()
            } ?: return

        withContext(Dispatchers.IO) {
            writeSnapshot(snapshot)
        }
    }

    private suspend fun savePlayerStateToDisk(syncToDisk: Boolean) {
        val playerState =
            withContext(Dispatchers.Main.immediate) {
                playerStateProvider()
            } ?: return

        withContext(Dispatchers.IO) {
            writeObject(PERSISTENT_PLAYER_STATE_FILE, playerState, syncToDisk)
        }
    }

    private fun writeSnapshot(
        snapshot: PersistentPlaybackSnapshot,
        syncToDisk: Boolean = true,
    ) {
        writeObject(PERSISTENT_QUEUE_FILE, snapshot.queue, syncToDisk)
        writeObject(PERSISTENT_AUTOMIX_FILE, snapshot.automix, syncToDisk)
        writeObject(PERSISTENT_PLAYER_STATE_FILE, snapshot.playerState, syncToDisk)
    }

    private fun writeObject(
        fileName: String,
        payload: Serializable,
        syncToDisk: Boolean,
    ) {
        val persistentFile = filesDir.resolve(fileName)
        val tempFile = filesDir.resolve("$fileName.tmp")

        synchronized(stateLock) {
            runCatching {
                FileOutputStream(tempFile).use { fileOutput ->
                    ObjectOutputStream(fileOutput).use { output ->
                        output.writeObject(payload)
                        output.flush()
                        if (syncToDisk) fileOutput.fd.sync()
                    }
                }

                if (persistentFile.exists() && !persistentFile.delete()) {
                    error("Could not replace $fileName")
                }
                if (!tempFile.renameTo(persistentFile)) {
                    error("Could not atomically move $fileName")
                }
            }.onFailure { error ->
                runCatching { tempFile.delete() }
                    .onFailure { deleteError ->
                        Timber.tag(TAG).w(deleteError, "Failed to remove temp file: %s", fileName)
                    }
                reportException(error)
            }
        }
    }

    private companion object {
        const val TAG = "PlaybackPersistence"
        const val PROGRESS_INTERVAL_MS = 60_000L
        const val SAVE_DEBOUNCE_MS = 750L
    }
}
