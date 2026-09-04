/*
 * Capsule MUSIC
 * VIDEO resolver v2 — official-video matching in Capsule, stream extraction in
 * isolated NewPipeExtractor process.
 *
 * Public API intentionally stays compatible with the previous resolver so the
 * rest of MusicService does not inherit NewPipe types or YouTube extraction
 * details.
 * GPL-3.0
 */
package com.nikhil.yt.playback.video

import android.net.Uri
import android.os.Bundle
import android.os.DeadObjectException
import android.os.RemoteException
import com.nikhil.yt.App
import com.nikhil.yt.constants.CapsuleVideoQuality
import com.nikhil.yt.innertube.CapsuleVideoRequestGuard
import com.nikhil.yt.innertube.YouTubeMusicVideoLinkResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

object YouTubeVideoResolver {
    private const val TAG = "CapsuleVideo"
    private const val CACHE_SAFETY_MS = 45_000L

    data class StreamFormat(
        val itag: Int,
        val width: Int?,
        val height: Int?,
        val qualityLabel: String?,
        val quality: String = qualityLabel.orEmpty(),
    )

    data class ResolvedVideo(
        val sourceMediaId: String,
        val videoId: String,
        val videoStreamUrl: String,
        val videoFormat: StreamFormat,
        val audioStreamUrl: String? = null,
        val audioFormat: StreamFormat? = null,
        val expiresAtMs: Long,
    ) {
        val streamUrl: String
            get() = videoStreamUrl

        val format: StreamFormat
            get() = videoFormat

        val isAdaptive: Boolean
            get() = !audioStreamUrl.isNullOrBlank() && audioFormat != null

        val qualityLabel: String
            get() =
                videoFormat.qualityLabel
                    ?: videoFormat.height?.let { "${it}p" }
                    ?: "VIDEO"
    }

    class VideoBackendException(
        val failure: CapsuleVideoFailure,
        message: String,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)

    private data class Cached(
        val resolved: ResolvedVideo,
        val cacheKey: String,
    )

    private val cache = ConcurrentHashMap<String, Cached>()
    private val latestCacheKeyByVideoId = ConcurrentHashMap<String, String>()

    /*
     * One extraction at a time is deliberate. It prevents queue skips, UI
     * recompositions and Media3 recovery from creating a burst of parallel
     * YouTube extraction requests. Cache hits never take this lock.
     */
    private val resolveMutex = Mutex()

    /*
     * Share the entire song -> official video -> extracted streams operation,
     * not just the final extractor call. Without this, two quick VIDEO requests
     * for the same track could both perform the YouTube Music linking request
     * before they reached resolveMutex. That creates unnecessary traffic and
     * makes rapid mode toggles more likely to trip upstream throttling.
     *
     * The owner is the only coroutine that performs work. Other callers await
     * the same result. If the owner is cancelled because the user moved to a
     * different track, all waiters are cancelled too and no stale result is
     * published by this resolver.
     */
    private val songResolveMutex = Mutex()
    private val inFlightSongResolves =
        mutableMapOf<String, CompletableDeferred<Result<ResolvedVideo>>>()

    @Volatile
    private var initialized = false

    fun invalidate(videoId: String) {
        val id = videoId.trim()
        if (id.isBlank()) return
        cache.keys.removeIf { it.startsWith("$id:") }
        latestCacheKeyByVideoId.remove(id)
    }

    fun peekResolved(videoId: String): ResolvedVideo? {
        ensureInitialized()
        val id = videoId.trim()
        val key = latestCacheKeyByVideoId[id] ?: return null
        val now = System.currentTimeMillis()
        val cached = cache[key]
            ?.resolved
            ?.takeIf { it.expiresAtMs > now + CACHE_SAFETY_MS }
            ?: run {
                cache.remove(key)
                latestCacheKeyByVideoId.remove(id, key)
                return null
            }
        return cached
    }

    suspend fun resolveForSong(
        sourceMediaId: String,
        title: String,
        artists: List<String>,
        durationSeconds: Int?,
        quality: CapsuleVideoQuality,
    ): Result<ResolvedVideo> {
        ensureInitialized()
        val canonicalId = sourceMediaId.trim()
        if (canonicalId.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing YouTube Music track id"))
        }

        val requestKey = "$canonicalId:${quality.name}"
        var isOwner = false
        val sharedResult =
            songResolveMutex.withLock {
                inFlightSongResolves[requestKey]
                    ?: CompletableDeferred<Result<ResolvedVideo>>()
                        .also { created ->
                            inFlightSongResolves[requestKey] = created
                            isOwner = true
                        }
            }

        if (!isOwner) {
            return sharedResult.await()
        }

        try {
            val result =
                try {
                    val link =
                        YouTubeMusicVideoLinkResolver
                            .resolve(
                                sourceMediaId = canonicalId,
                                title = title,
                                artists = artists,
                                durationSeconds = durationSeconds,
                            )
                            .getOrThrow()

                    val resolved =
                        resolveExact(
                            sourceMediaId = canonicalId,
                            videoId = link.videoId,
                            quality = quality,
                            muxedOnly = false,
                        )
                    latestCacheKeyByVideoId[link.videoId] =
                        cacheKey(link.videoId, quality, false)
                    CapsuleVideoRequestGuard.noteSuccess()
                    Result.success(resolved)
                } catch (cancelled: CancellationException) {
                    sharedResult.cancel(cancelled)
                    throw cancelled
                } catch (throwable: Throwable) {
                    Result.failure(throwable)
                }

            sharedResult.complete(result)
            return result
        } finally {
            songResolveMutex.withLock {
                inFlightSongResolves.remove(requestKey, sharedResult)
            }
        }
    }

    /**
     * Safe fallback used by the existing ResolvingDataSource when a prepared
     * VIDEO URL expired. It explicitly asks the backend for one muxed stream;
     * adaptive merging is never initiated from inside DataSource.open().
     */
    suspend fun resolveMuxed(
        videoId: String,
        quality: CapsuleVideoQuality,
    ): Result<ResolvedVideo> = runCatching {
        ensureInitialized()
        val id = videoId.trim()
        require(id.isNotBlank()) { "Missing linked YouTube video id" }
        resolveExact(
            sourceMediaId = id,
            videoId = id,
            quality = quality,
            muxedOnly = true,
        )
    }

    private suspend fun resolveExact(
        sourceMediaId: String,
        videoId: String,
        quality: CapsuleVideoQuality,
        muxedOnly: Boolean,
    ): ResolvedVideo {
        val key = cacheKey(videoId, quality, muxedOnly)
        val now = System.currentTimeMillis()
        cache[key]
            ?.resolved
            ?.takeIf { it.expiresAtMs > now + CACHE_SAFETY_MS }
            ?.let { return it }

        if (CapsuleVideoRequestGuard.isBlocked()) {
            throw CapsuleVideoRequestGuard.RequestBlockedException(
                "YouTube VIDEO paused for ${CapsuleVideoRequestGuard.remainingBackoffMs() / 1000L}s",
            )
        }

        CapsuleVideoRequestGuard.beforeMetadataRequest()

        return resolveMutex.withLock {
            val secondNow = System.currentTimeMillis()
            cache[key]
                ?.resolved
                ?.takeIf { it.expiresAtMs > secondNow + CACHE_SAFETY_MS }
                ?.let { return@withLock it }

            val remote = callRemoteExtractor(videoId, quality, muxedOnly)
            val resolved = remote.toResolved(sourceMediaId)
            cache[key] = Cached(resolved = resolved, cacheKey = key)
            latestCacheKeyByVideoId[videoId] = key
            resolved
        }
    }

    private suspend fun callRemoteExtractor(
        videoId: String,
        quality: CapsuleVideoQuality,
        muxedOnly: Boolean,
    ): CapsuleResolvedVideo = withContext(Dispatchers.IO) {
        val context = App.instance.applicationContext
        val authority = "${context.packageName}.capsule.video.extractor"
        val uri = Uri.parse("content://$authority")
        val extras =
            Bundle().apply {
                putString(CapsuleVideoIpc.EXTRA_VIDEO_ID, videoId)
                putString(CapsuleVideoIpc.EXTRA_QUALITY, CapsuleVideoIpc.qualityToWire(quality))
                putBoolean(CapsuleVideoIpc.EXTRA_MUXED_ONLY, muxedOnly)
            }

        val bundle =
            try {
                context.contentResolver.call(
                    uri,
                    CapsuleVideoIpc.METHOD_RESOLVE,
                    null,
                    extras,
                )
            } catch (dead: DeadObjectException) {
                throw VideoBackendException(
                    CapsuleVideoFailure.REMOTE_PROCESS_DIED,
                    "VIDEO extractor process stopped unexpectedly",
                    dead,
                )
            } catch (remote: RemoteException) {
                throw VideoBackendException(
                    CapsuleVideoFailure.REMOTE_PROCESS_DIED,
                    "VIDEO extractor IPC failed",
                    remote,
                )
            } catch (throwable: Throwable) {
                throw VideoBackendException(
                    CapsuleVideoFailure.REMOTE_PROCESS_DIED,
                    throwable.message ?: "VIDEO extractor unavailable",
                    throwable,
                )
            } ?: throw VideoBackendException(
                CapsuleVideoFailure.REMOTE_PROCESS_DIED,
                "VIDEO extractor returned no result",
            )

        if (!bundle.getBoolean(CapsuleVideoIpc.EXTRA_SUCCESS, false)) {
            val failure =
                runCatching {
                    CapsuleVideoFailure.valueOf(
                        bundle.getString(CapsuleVideoIpc.EXTRA_FAILURE).orEmpty(),
                    )
                }.getOrDefault(CapsuleVideoFailure.UNKNOWN)
            val message =
                bundle.getString(CapsuleVideoIpc.EXTRA_MESSAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: "VIDEO extraction failed"

            when (failure) {
                CapsuleVideoFailure.RATE_LIMITED,
                CapsuleVideoFailure.BOT_BLOCKED,
                -> CapsuleVideoRequestGuard.noteBlockedAfterAllAttempts(failure.name.lowercase())

                else -> Unit
            }

            throw VideoBackendException(failure, message)
        }

        val url =
            bundle.getString(CapsuleVideoIpc.EXTRA_VIDEO_URL)
                ?.takeIf { it.startsWith("http", ignoreCase = true) }
                ?: throw VideoBackendException(
                    CapsuleVideoFailure.EXTRACTOR,
                    "VIDEO extractor returned an invalid stream URL",
                )

        CapsuleResolvedVideo(
            videoId = bundle.getString(CapsuleVideoIpc.EXTRA_VIDEO_ID).orEmpty().ifBlank { videoId },
            videoUrl = url,
            audioUrl = bundle.getString(CapsuleVideoIpc.EXTRA_AUDIO_URL)?.takeIf { it.isNotBlank() },
            qualityLabel = bundle.getString(CapsuleVideoIpc.EXTRA_QUALITY_LABEL).orEmpty().ifBlank { "VIDEO" },
            width = bundle.getInt(CapsuleVideoIpc.EXTRA_WIDTH, 0),
            height = bundle.getInt(CapsuleVideoIpc.EXTRA_HEIGHT, 0),
            videoItag = bundle.getInt(CapsuleVideoIpc.EXTRA_VIDEO_ITAG, -1),
            audioItag = bundle.getInt(CapsuleVideoIpc.EXTRA_AUDIO_ITAG, -1),
            expiresAtMs = bundle.getLong(CapsuleVideoIpc.EXTRA_EXPIRES_AT, System.currentTimeMillis() + 3_600_000L),
        )
    }

    private fun CapsuleResolvedVideo.toResolved(sourceMediaId: String): ResolvedVideo {
        val videoFormat =
            StreamFormat(
                itag = videoItag,
                width = width.takeIf { it > 0 },
                height = height.takeIf { it > 0 },
                qualityLabel = qualityLabel,
            )
        val audioFormat =
            if (adaptive) {
                StreamFormat(
                    itag = audioItag,
                    width = null,
                    height = null,
                    qualityLabel = null,
                )
            } else {
                null
            }

        return ResolvedVideo(
            sourceMediaId = sourceMediaId,
            videoId = videoId,
            videoStreamUrl = videoUrl,
            videoFormat = videoFormat,
            audioStreamUrl = audioUrl,
            audioFormat = audioFormat,
            expiresAtMs = expiresAtMs,
        )
    }

    private fun cacheKey(
        videoId: String,
        quality: CapsuleVideoQuality,
        muxedOnly: Boolean,
    ): String = "$videoId:${quality.name}:${if (muxedOnly) "muxed" else "preferred"}"

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            runCatching {
                CapsuleVideoGuardStore.initialize(App.instance.applicationContext)
            }.onFailure {
                Timber.tag(TAG).w(it, "Could not restore VIDEO request guard")
            }
            initialized = true
        }
    }
}
