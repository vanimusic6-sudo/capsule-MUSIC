package com.nikhil.yt.playback.video

import com.nikhil.yt.constants.CapsuleVideoQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CapsuleVideoResolveRequest(
    val sourceMediaId: String,
    val title: String,
    val artists: List<String>,
    val durationSeconds: Int?,
    val quality: CapsuleVideoQuality,
    val originalVideo: Boolean = false,
)

/** AUDIO's verified /player response can identify an official source clip even
 * when an older playlist/library row lost its watch-endpoint type. */
internal fun shouldUseCanonicalVideo(metadataOriginal: Boolean, verifiedMusicVideoType: String?): Boolean =
    metadataOriginal || verifiedMusicVideoType == "MUSIC_VIDEO_TYPE_OMV"

/**
 * Owns latest-only VIDEO resolve work. Player replacement and VIDEO/AUDIO state
 * transitions deliberately remain in MusicService.
 */
internal class CapsuleVideoResolveCoordinator(
    private val scopeProvider: () -> CoroutineScope,
    private val resolver: suspend (CapsuleVideoResolveRequest) -> Result<YouTubeVideoResolver.ResolvedVideo> = { request ->
        withContext(Dispatchers.IO) {
            YouTubeVideoResolver.resolveForSong(
                sourceMediaId = request.sourceMediaId,
                title = request.title,
                artists = request.artists,
                durationSeconds = request.durationSeconds,
                quality = request.quality,
                originalVideo = request.originalVideo,
            )
        }
    },
) {
    private var generation = 0L
    private var job: Job? = null

    fun cancel() {
        generation += 1L
        job?.cancel()
        job = null
    }

    fun resolve(
        request: CapsuleVideoResolveRequest,
        isRelevant: () -> Boolean,
        onResult: (Result<YouTubeVideoResolver.ResolvedVideo>) -> Unit,
    ) {
        job?.cancel()
        val requestGeneration = ++generation
        val launched =
            scopeProvider().launch(start = CoroutineStart.LAZY) {
                val result =
                    try {
                        resolver(request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Result.failure(failure)
                    }

                if (generation != requestGeneration) return@launch
                job = null
                if (!isRelevant()) return@launch
                onResult(result)
            }
        job = launched
        launched.start()
    }
}
