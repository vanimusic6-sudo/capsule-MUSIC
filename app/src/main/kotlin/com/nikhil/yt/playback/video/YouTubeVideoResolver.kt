/* Capsule MUSIC - VIDEO resolver v2. GPL-3.0 */
package com.nikhil.yt.playback.video

import android.net.Uri
import android.os.Bundle
import android.os.DeadObjectException
import android.os.RemoteException
import com.nikhil.yt.App
import com.nikhil.yt.constants.CapsuleVideoQuality
import com.nikhil.yt.innertube.CapsuleVideoRequestGuard
import com.nikhil.yt.innertube.YouTubeMusicVideoLinkResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

object YouTubeVideoResolver {
    private const val TAG = "CapsuleVideo"
    private const val CACHE_SAFETY_MS = 45_000L

    data class StreamFormat(val itag: Int, val width: Int?, val height: Int?, val qualityLabel: String?, val quality: String = qualityLabel.orEmpty())

    data class ResolvedVideo(
        val sourceMediaId: String,
        val videoId: String,
        val videoStreamUrl: String,
        val videoFormat: StreamFormat,
        val audioStreamUrl: String? = null,
        val audioFormat: StreamFormat? = null,
        val expiresAtMs: Long,
    ) {
        val streamUrl get() = videoStreamUrl
        val format get() = videoFormat
        val isAdaptive get() = !audioStreamUrl.isNullOrBlank() && audioFormat != null
        val qualityLabel get() = videoFormat.qualityLabel ?: videoFormat.height?.let { "${it}p" } ?: "VIDEO"
    }

    class VideoBackendException(val failure: CapsuleVideoFailure, message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    private val cache = ConcurrentHashMap<String, ResolvedVideo>()
    private val latestCacheKeyByVideoId = ConcurrentHashMap<String, String>()
    private val resolveMutex = Mutex()
    @Volatile private var initialized = false

    fun invalidate(videoId: String) {
        val id = videoId.trim(); if (id.isBlank()) return
        cache.keys.removeIf { it.startsWith("$id:") }
        latestCacheKeyByVideoId.remove(id)
    }

    fun peekResolved(videoId: String): ResolvedVideo? {
        ensureInitialized()
        val id = videoId.trim()
        val key = latestCacheKeyByVideoId[id] ?: return null
        val item = cache[key]
        if (item == null || item.expiresAtMs <= System.currentTimeMillis() + CACHE_SAFETY_MS) {
            cache.remove(key); latestCacheKeyByVideoId.remove(id, key); return null
        }
        return item
    }

    suspend fun resolveForSong(sourceMediaId: String, title: String, artists: List<String>, durationSeconds: Int?, quality: CapsuleVideoQuality): Result<ResolvedVideo> = runCatching {
        ensureInitialized()
        val sourceId = sourceMediaId.trim(); require(sourceId.isNotBlank()) { "Missing YouTube Music track id" }
        val link = YouTubeMusicVideoLinkResolver.resolve(sourceId, title, artists, durationSeconds).getOrThrow()
        resolveExact(sourceId, link.videoId, quality, false).also {
            latestCacheKeyByVideoId[link.videoId] = cacheKey(link.videoId, quality, false)
            CapsuleVideoRequestGuard.noteSuccess()
        }
    }

    suspend fun resolveMuxed(videoId: String, quality: CapsuleVideoQuality): Result<ResolvedVideo> = runCatching {
        ensureInitialized()
        val id = videoId.trim(); require(id.isNotBlank()) { "Missing linked YouTube video id" }
        resolveExact(id, id, quality, true)
    }

    private suspend fun resolveExact(sourceMediaId: String, videoId: String, quality: CapsuleVideoQuality, muxedOnly: Boolean): ResolvedVideo {
        val key = cacheKey(videoId, quality, muxedOnly)
        cache[key]?.takeIf { it.expiresAtMs > System.currentTimeMillis() + CACHE_SAFETY_MS }?.let { return it }
        if (CapsuleVideoRequestGuard.isBlocked()) throw CapsuleVideoRequestGuard.RequestBlockedException("YouTube VIDEO paused for ${CapsuleVideoRequestGuard.remainingBackoffMs() / 1000L}s")
        CapsuleVideoRequestGuard.beforeMetadataRequest()

        return resolveMutex.withLock {
            cache[key]?.takeIf { it.expiresAtMs > System.currentTimeMillis() + CACHE_SAFETY_MS }?.let { return@withLock it }
            val resolved = callRemoteExtractor(videoId, quality, muxedOnly).toResolved(sourceMediaId)
            cache[key] = resolved
            latestCacheKeyByVideoId[videoId] = key
            resolved
        }
    }

    private suspend fun callRemoteExtractor(videoId: String, quality: CapsuleVideoQuality, muxedOnly: Boolean): CapsuleResolvedVideo = withContext(Dispatchers.IO) {
        val context = App.instance.applicationContext
        val uri = Uri.parse("content://${context.packageName}.capsule.video.extractor")
        val extras = Bundle().apply {
            putString(CapsuleVideoIpc.EXTRA_VIDEO_ID, videoId)
            putString(CapsuleVideoIpc.EXTRA_QUALITY, CapsuleVideoIpc.qualityToWire(quality))
            putBoolean(CapsuleVideoIpc.EXTRA_MUXED_ONLY, muxedOnly)
        }
        val bundle = try {
            context.contentResolver.call(uri, CapsuleVideoIpc.METHOD_RESOLVE, null, extras)
        } catch (dead: DeadObjectException) {
            throw VideoBackendException(CapsuleVideoFailure.REMOTE_PROCESS_DIED, "VIDEO extractor process stopped unexpectedly", dead)
        } catch (remote: RemoteException) {
            throw VideoBackendException(CapsuleVideoFailure.REMOTE_PROCESS_DIED, "VIDEO extractor IPC failed", remote)
        } catch (t: Throwable) {
            throw VideoBackendException(CapsuleVideoFailure.REMOTE_PROCESS_DIED, t.message ?: "VIDEO extractor unavailable", t)
        } ?: throw VideoBackendException(CapsuleVideoFailure.REMOTE_PROCESS_DIED, "VIDEO extractor returned no result")

        if (!bundle.getBoolean(CapsuleVideoIpc.EXTRA_SUCCESS, false)) {
            val failure = runCatching { CapsuleVideoFailure.valueOf(bundle.getString(CapsuleVideoIpc.EXTRA_FAILURE).orEmpty()) }.getOrDefault(CapsuleVideoFailure.UNKNOWN)
            val message = bundle.getString(CapsuleVideoIpc.EXTRA_MESSAGE)?.takeIf { it.isNotBlank() } ?: "VIDEO extraction failed"
            if (failure == CapsuleVideoFailure.RATE_LIMITED || failure == CapsuleVideoFailure.BOT_BLOCKED) CapsuleVideoRequestGuard.noteBlockedAfterAllAttempts(failure.name.lowercase())
            throw VideoBackendException(failure, message)
        }

        val url = bundle.getString(CapsuleVideoIpc.EXTRA_VIDEO_URL)?.takeIf { it.startsWith("http", true) }
            ?: throw VideoBackendException(CapsuleVideoFailure.EXTRACTOR, "VIDEO extractor returned an invalid stream URL")
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
        val vf = StreamFormat(videoItag, width.takeIf { it > 0 }, height.takeIf { it > 0 }, qualityLabel)
        val af = if (adaptive) StreamFormat(audioItag, null, null, null) else null
        return ResolvedVideo(sourceMediaId, videoId, videoUrl, vf, audioUrl, af, expiresAtMs)
    }

    private fun cacheKey(videoId: String, quality: CapsuleVideoQuality, muxedOnly: Boolean) = "$videoId:${quality.name}:${if (muxedOnly) "muxed" else "preferred"}"

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            runCatching { CapsuleVideoGuardStore.initialize(App.instance.applicationContext) }
                .onFailure { Timber.tag(TAG).w(it, "Could not restore VIDEO request guard") }
            initialized = true
        }
    }
}
