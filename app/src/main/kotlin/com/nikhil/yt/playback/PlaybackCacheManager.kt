package com.nikhil.yt.playback

import androidx.media3.datasource.cache.Cache
import com.nikhil.yt.extensions.directorySizeBytes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun configuredPlayerCacheLimitBytes(
    enabled: Boolean,
    maxSongCacheSizeMb: Int,
): Long? {
    if (!enabled) return null
    if (maxSongCacheSizeMb <= 0 || maxSongCacheSizeMb == -1) return null

    val bytesPerMb = 1024L * 1024L
    val safeSizeMb =
        maxSongCacheSizeMb
            .toLong()
            .coerceAtMost(Long.MAX_VALUE / bytesPerMb)
    return safeSizeMb * bytesPerMb
}

/** Owns Smart Trimmer policy and player-cache eviction, never stream routing. */
internal class PlaybackCacheManager(
    private val cache: Cache,
    private val cacheDirectory: File,
) {
    suspend fun trimToConfiguredLimit(
        enabled: Boolean,
        maxSongCacheSizeMb: Int,
    ) {
        val limitBytes =
            configuredPlayerCacheLimitBytes(
                enabled = enabled,
                maxSongCacheSizeMb = maxSongCacheSizeMb,
            ) ?: return
        trimToBytes(limitBytes)
    }

    private suspend fun trimToBytes(limitBytes: Long) {
        if (limitBytes <= 0L) return

        withContext(Dispatchers.IO) {
            val currentSpace = runCatching { cache.cacheSpace }.getOrNull() ?: 0L
            var totalBytes =
                if (currentSpace > 0L) {
                    currentSpace
                } else {
                    cacheDirectory.directorySizeBytes()
                }
            if (totalBytes <= limitBytes) return@withContext

            data class Candidate(
                val key: String,
                val lastTouchTimestamp: Long,
                val sizeBytes: Long,
            )

            val candidates =
                runCatching {
                    cache.keys
                        .mapNotNull { key ->
                            runCatching candidate@{
                                val spans = cache.getCachedSpans(key)
                                if (spans.isEmpty()) return@candidate null
                                Candidate(
                                    key = key,
                                    lastTouchTimestamp = spans.minOf { it.lastTouchTimestamp },
                                    sizeBytes = spans.sumOf { it.length },
                                )
                            }.getOrNull()
                        }.sortedBy { it.lastTouchTimestamp }
                }.getOrNull().orEmpty()

            for (candidate in candidates) {
                if (totalBytes <= limitBytes) break
                val removedSize = candidate.sizeBytes.coerceAtLeast(0L)
                runCatching { cache.removeResource(candidate.key) }
                totalBytes -= removedSize
            }
        }
    }
}
