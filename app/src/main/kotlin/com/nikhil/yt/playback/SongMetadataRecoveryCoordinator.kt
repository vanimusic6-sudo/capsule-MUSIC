package com.nikhil.yt.playback

import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.RelatedSongMap
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.models.toMediaMetadata
import com.nikhil.yt.playback.audio.CapsuleAudioEngine
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns best-effort song metadata recovery, related-song persistence and its
 * per-track Jobs. MusicService remains the owner of ExoPlayer/Media3 state and
 * exposes only read callbacks required to validate the current queue item.
 */
internal class SongMetadataRecoveryCoordinator(
    private val scopeProvider: () -> CoroutineScope,
    private val database: MusicDatabase,
    private val awaitStable: suspend (String) -> Unit,
    private val mediaMetadataProvider: suspend (String) -> MediaMetadata?,
    private val automixJobProvider: suspend (String) -> Job?,
    private val playbackBlockedExceptionOrNull: () -> Throwable? = {
        CapsuleAudioEngine.playbackBlockedExceptionOrNull()
    },
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val jobs = SongRecoveryJobOwner()

    fun schedule(
        mediaId: String,
        playbackData: CapsuleAudioEngine.PlaybackData? = null,
    ) {
        jobs.launchIfAbsent(mediaId) {
            scopeProvider().launch(start = CoroutineStart.LAZY) {
                try {
                    awaitStable(mediaId)
                    recoverSong(mediaId, playbackData)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    onFailure(mediaId, failure)
                }
            }
        }
    }

    fun cancelExcept(mediaId: String?) {
        jobs.cancelExcept(mediaId)
    }

    fun cancelAll() {
        jobs.cancelAll()
    }

    suspend fun cacheRelatedSongs(
        mediaId: String,
        songs: List<SongItem>,
    ) {
        if (songs.isEmpty()) return

        val seed = mediaMetadataProvider(mediaId)
        database.withTransaction {
            if (hasRelatedSongs(mediaId)) return@withTransaction
            if (getSongByIdBlocking(mediaId) == null) {
                insert(seed ?: return@withTransaction)
            }
            songs.map(SongItem::toMediaMetadata)
                .onEach(::insert)
                .forEach { related ->
                    insert(
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = related.id,
                        ),
                    )
                }
        }
    }

    private suspend fun recoverSong(
        mediaId: String,
        playbackData: CapsuleAudioEngine.PlaybackData?,
    ) {
        val song = database.song(mediaId).first()
        val mediaMetadata = mediaMetadataProvider(mediaId) ?: return
        val duration =
            song?.song?.duration?.takeIf { it != -1 }
                ?: mediaMetadata.duration.takeIf { it != -1 }
                ?: (
                    playbackData?.videoDetails
                        ?: CapsuleAudioEngine.playerResponseForMetadata(mediaId)
                            .getOrNull()
                            ?.videoDetails
                )?.lengthSeconds?.toIntOrNull()
                ?: -1

        database.withTransaction {
            val existing = getSongByIdBlocking(mediaId)?.song
            if (existing == null) {
                insert(mediaMetadata.copy(duration = duration))
            } else if (existing.duration == -1) {
                update(existing.copy(duration = duration))
            }
        }

        // Automix may already be fetching/persisting the same related songs.
        // Join it before deciding whether a second metadata request is needed.
        automixJobProvider(mediaId)?.join()

        if (!database.hasRelatedSongs(mediaId)) {
            playbackBlockedExceptionOrNull()?.let { throw it }
            val relatedEndpoint =
                YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                    ?: return
            playbackBlockedExceptionOrNull()?.let { throw it }
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            cacheRelatedSongs(mediaId, relatedPage.songs)
        }
    }
}

/**
 * Small, independently testable owner for metadata-recovery Jobs.
 *
 * Jobs are inserted before a lazy Job starts, matching the old MusicService
 * synchronization semantics so two callers cannot launch duplicate work.
 */
internal class SongRecoveryJobOwner {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lock = Any()

    fun launchIfAbsent(
        mediaId: String,
        createLazyJob: () -> Job,
    ): Boolean =
        synchronized(lock) {
            if (jobs[mediaId]?.isActive == true) return@synchronized false

            val job = createLazyJob()
            jobs[mediaId] = job
            job.invokeOnCompletion { jobs.remove(mediaId, job) }
            job.start()
            true
        }

    fun cancelExcept(mediaId: String?) {
        jobs.forEach { (id, job) ->
            if (id != mediaId && jobs.remove(id, job)) {
                job.cancel()
            }
        }
    }

    fun cancelAll() {
        jobs.forEach { (id, job) ->
            if (jobs.remove(id, job)) {
                job.cancel()
            }
        }
    }

    internal fun trackedIds(): Set<String> = jobs.keys.toSet()
}
