from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match in {path}, got {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Field evidence from step45 shows the remaining CDN 403s split into two classes:
# 1) old prefetched signed URLs (71s/80s old in the capture), and
# 2) a rare fresh generation while the next-track prefetch was allowed to begin
#    before the current track had even received its first byte.
#
# Final policy: prefetch just-in-time, only after current playback is genuinely
# running, and never consume a prefetched generation older than 60s. This keeps
# the warm-next-track benefit without holding signed URL/PoToken generations for
# most of a song or overlapping /player work with the current first CDN open.

replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    '''internal const val SIGNED_URL_SESSION_REFRESH_THRESHOLD_MS = 3_000L
internal const val SIGNED_URL_MAX_FRESH_RESOLVE_DELAY_MS = 3_000L
''',
    '''internal const val SIGNED_URL_SESSION_REFRESH_THRESHOLD_MS = 3_000L
internal const val SIGNED_URL_MAX_FRESH_RESOLVE_DELAY_MS = 3_000L
internal const val AUDIO_PREFETCH_LEAD_TIME_MS = 45_000L
internal const val AUDIO_PREFETCH_MIN_CURRENT_PROGRESS_MS = 3_000L
internal const val AUDIO_PREFETCH_RECHECK_MS = 2_000L
internal const val AUDIO_PREFETCHED_URL_MAX_AGE_MS = 60_000L

internal fun audioPrefetchWaitMs(
    durationMs: Long,
    positionMs: Long,
    isPlaying: Boolean,
    leadTimeMs: Long = AUDIO_PREFETCH_LEAD_TIME_MS,
    minimumCurrentProgressMs: Long = AUDIO_PREFETCH_MIN_CURRENT_PROGRESS_MS,
): Long {
    if (!isPlaying) return AUDIO_PREFETCH_RECHECK_MS

    val safePositionMs = positionMs.coerceAtLeast(0L)
    val untilPlaybackWarmMs =
        (minimumCurrentProgressMs - safePositionMs).coerceAtLeast(0L)
    val untilLeadWindowMs =
        if (durationMs > 0L && durationMs != C.TIME_UNSET) {
            (durationMs - safePositionMs - leadTimeMs).coerceAtLeast(0L)
        } else {
            0L
        }

    return maxOf(untilPlaybackWarmMs, untilLeadWindowMs)
}
''',
)

replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    '''    private var streamRetryJob: Job? = null
''',
    '''    private var streamRetryJob: Job? = null
    private var prefetchScheduleJob: Job? = null
''',
)

old_prefetch = '''    private fun prefetchUpcomingAudio() {
        val prefetchGeneration = audioResolveCoordinator.nextPrefetchGeneration()
        val upcoming = upcomingAudioIds()

        val relevantIds =
            buildSet {
                player.currentMediaItem
                    ?.mediaId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
                addAll(upcoming)
            }

        /*
         * Rapid skipping used to leave every abandoned prefetch alive. Each
         * one owns its own client fallback budget, so a short swipe burst
         * could keep contacting YouTube for tracks no longer near playback.
         * Keep only the current item and the one useful look-ahead item.
         */
        audioResolveCoordinator.cancelStaleExcept(relevantIds).forEach { mediaId ->
            Timber.tag(CAPSULE_RESOLVE_TAG).i(
                "prefetch cancel stale id=%s",
                mediaId,
            )
        }

        Timber.tag(CAPSULE_RESOLVE_TAG).i(
            "prefetch queue ahead=%d ids=%s",
            upcoming.size,
            upcoming.joinToString(","),
        )

        upcoming.forEach { mediaId ->
            ioScope.launch {
                if (!audioResolveCoordinator.isPrefetchGenerationCurrent(prefetchGeneration)) {
                    Timber.tag(CAPSULE_RESOLVE_TAG).i(
                        "prefetch skip transient id=%s",
                        mediaId,
                    )
                    return@launch
                }

                if (!isNetworkConnected.value ||
                    AudioCacheIdentity.completeKey(downloadCache, mediaId) != null ||
                    AudioCacheIdentity.completeKey(playerCache, mediaId) != null
                ) return@launch
                if (playbackUrlCache.get(mediaId, PREFETCH_FRESHNESS_MS) != null) {
                    Timber.tag(CAPSULE_RESOLVE_TAG).i("prefetch skip cached id=%s", mediaId)
                    return@launch
                }
                if (audioResolveCoordinator.hasInFlight(mediaId)) {
                    Timber.tag(CAPSULE_RESOLVE_TAG).i("prefetch skip inflight id=%s", mediaId)
                    return@launch
                }

                // The shared job publishes the entire result before completing.
                audioResolveJob(mediaId).await()
            }
        }
    }
'''
new_prefetch = '''    private fun prefetchUpcomingAudio() {
        val prefetchGeneration = audioResolveCoordinator.nextPrefetchGeneration()
        val upcoming = upcomingAudioIds()

        prefetchScheduleJob?.cancel()
        prefetchScheduleJob = null

        val relevantIds =
            buildSet {
                player.currentMediaItem
                    ?.mediaId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
                addAll(upcoming)
            }

        /*
         * Rapid skipping used to leave every abandoned prefetch alive. Each
         * one owns its own client fallback budget, so a short swipe burst
         * could keep contacting YouTube for tracks no longer near playback.
         * Keep only the current item and the one useful look-ahead item.
         */
        audioResolveCoordinator.cancelStaleExcept(relevantIds).forEach { mediaId ->
            Timber.tag(CAPSULE_RESOLVE_TAG).i(
                "prefetch cancel stale id=%s",
                mediaId,
            )
        }

        Timber.tag(CAPSULE_RESOLVE_TAG).i(
            "prefetch queue ahead=%d ids=%s",
            upcoming.size,
            upcoming.joinToString(","),
        )

        val mediaId = upcoming.singleOrNull() ?: return
        prefetchScheduleJob =
            ioScope.launch {
                /*
                 * Resolve the next track just-in-time instead of immediately at
                 * the start of the current song. Besides keeping the signed URL
                 * young, requiring real current playback means background /player
                 * work never races the current track's first CDN open/first byte.
                 */
                while (isActive && audioResolveCoordinator.isPrefetchGenerationCurrent(prefetchGeneration)) {
                    val waitMs =
                        withContext(Dispatchers.Main.immediate) {
                            val metadataDurationMs =
                                player.currentMetadata
                                    ?.duration
                                    ?.takeIf { it > 0 }
                                    ?.toLong()
                                    ?.times(1_000L)
                            val durationMs =
                                player.duration
                                    .takeIf { it > 0L && it != C.TIME_UNSET }
                                    ?: metadataDurationMs
                                    ?: C.TIME_UNSET
                            audioPrefetchWaitMs(
                                durationMs = durationMs,
                                positionMs = player.currentPosition,
                                isPlaying = player.isPlaying,
                            )
                        }

                    if (waitMs <= 0L) break
                    delay(waitMs.coerceAtMost(AUDIO_PREFETCH_RECHECK_MS))
                }

                if (!isActive || !audioResolveCoordinator.isPrefetchGenerationCurrent(prefetchGeneration)) {
                    Timber.tag(CAPSULE_RESOLVE_TAG).i(
                        "prefetch skip transient id=%s",
                        mediaId,
                    )
                    return@launch
                }

                val stillUpcoming =
                    withContext(Dispatchers.Main.immediate) {
                        mediaId in upcomingAudioIds()
                    }
                if (!stillUpcoming) {
                    Timber.tag(CAPSULE_RESOLVE_TAG).i("prefetch skip stale-next id=%s", mediaId)
                    return@launch
                }

                if (!isNetworkConnected.value ||
                    AudioCacheIdentity.completeKey(downloadCache, mediaId) != null ||
                    AudioCacheIdentity.completeKey(playerCache, mediaId) != null
                ) return@launch
                if (
                    playbackUrlCache.getForPlayback(
                        mediaId = mediaId,
                        maxPrefetchedAgeMs = AUDIO_PREFETCHED_URL_MAX_AGE_MS,
                        minimumRemainingMs = PREFETCH_FRESHNESS_MS,
                    ) != null
                ) {
                    Timber.tag(CAPSULE_RESOLVE_TAG).i("prefetch skip cached id=%s", mediaId)
                    return@launch
                }
                if (audioResolveCoordinator.hasInFlight(mediaId)) {
                    Timber.tag(CAPSULE_RESOLVE_TAG).i("prefetch skip inflight id=%s", mediaId)
                    return@launch
                }

                // The shared job publishes the entire result before completing.
                audioResolveJob(mediaId).await()
            }
    }
'''
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    old_prefetch,
    new_prefetch,
)

replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    '''                    result.getOrNull()?.let {
                        cacheResolvedPlayback(mediaId, it, policyGeneration, selection)
                    }
''',
    '''                    result.getOrNull()?.let {
                        cacheResolvedPlayback(
                            mediaId = mediaId,
                            playback = it,
                            generation = policyGeneration,
                            selection = selection,
                            priority = priority,
                        )
                    }
''',
)

replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    '''                playbackUrlCache.get(mediaId)?.let { cached ->
                    songMetadataRecoveryCoordinator.schedule(mediaId, cached)
                    return@ResolvingDataSource resolvedAudioDataSpec(
                        dataSpec = dataSpec,
                        playback = cached,
                        contract = contract,
                        source = AudioCdnOpenSource.CACHED,
                    )
                }
''',
    '''                playbackUrlCache
                    .getForPlayback(
                        mediaId = mediaId,
                        maxPrefetchedAgeMs = AUDIO_PREFETCHED_URL_MAX_AGE_MS,
                    )
                    ?.let { cached ->
                        songMetadataRecoveryCoordinator.schedule(mediaId, cached)
                        return@ResolvingDataSource resolvedAudioDataSpec(
                            dataSpec = dataSpec,
                            playback = cached,
                            contract = contract,
                            source = AudioCdnOpenSource.CACHED,
                        )
                    }
''',
)

replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    '''    private suspend fun cacheResolvedPlayback(
        mediaId: String,
        playback: CapsuleAudioEngine.PlaybackData,
        generation: Long,
        selection: AudioPlaybackContext,
    ) {
''',
    '''    private suspend fun cacheResolvedPlayback(
        mediaId: String,
        playback: CapsuleAudioEngine.PlaybackData,
        generation: Long,
        selection: AudioPlaybackContext,
        priority: AudioResolvePriority,
    ) {
''',
)

replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    '''            publishResolvedLoudness(mediaId, loudness)
            playbackUrlCache.put(mediaId, playback, selection)
''',
    '''            publishResolvedLoudness(mediaId, loudness)
            playbackUrlCache.put(
                mediaId = mediaId,
                data = playback,
                context = selection,
                prefetched = priority == AudioResolvePriority.PREFETCH,
            )
''',
)

replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    '''        audioResolveCoordinator.cancelAll()
        playbackRecoveryCoordinator.cancelNetworkRecovery()
''',
    '''        audioResolveCoordinator.cancelAll()
        prefetchScheduleJob?.cancel()
        prefetchScheduleJob = null
        playbackRecoveryCoordinator.cancelNetworkRecovery()
''',
)

# Cache knows whether a generation came from prefetch. Only prefetched generations
# get the short age cap; current/on-demand generations can still be reused for seek
# and re-prepare as long as the server expiry/context remain valid.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/audio/PlaybackDataCache.kt",
    '''    private data class Entry(val data: CapsuleAudioEngine.PlaybackData, val expiresAtMs: Long, val context: Any?)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun get(mediaId: String, minimumRemainingMs: Long = 5_000L): CapsuleAudioEngine.PlaybackData? {
        val entry = entries[mediaId] ?: return null
        if (entry.context != currentContext() || entry.expiresAtMs <= nowMs() + minimumRemainingMs) {
            entries.remove(mediaId)
            return null
        }
        return entry.data
    }

    @Synchronized
    fun put(mediaId: String, data: CapsuleAudioEngine.PlaybackData, context: Any? = currentContext()) {
        entries[mediaId] = Entry(data, nowMs() + data.streamExpiresInSeconds.coerceAtLeast(1) * 1_000L, context)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
''',
    '''    private data class Entry(
        val data: CapsuleAudioEngine.PlaybackData,
        val expiresAtMs: Long,
        val context: Any?,
        val storedAtMs: Long,
        val prefetched: Boolean,
    )
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun get(mediaId: String, minimumRemainingMs: Long = 5_000L): CapsuleAudioEngine.PlaybackData? =
        getValidEntry(mediaId, minimumRemainingMs, maxPrefetchedAgeMs = null)

    @Synchronized
    fun getForPlayback(
        mediaId: String,
        maxPrefetchedAgeMs: Long,
        minimumRemainingMs: Long = 5_000L,
    ): CapsuleAudioEngine.PlaybackData? =
        getValidEntry(
            mediaId = mediaId,
            minimumRemainingMs = minimumRemainingMs,
            maxPrefetchedAgeMs = maxPrefetchedAgeMs,
        )

    private fun getValidEntry(
        mediaId: String,
        minimumRemainingMs: Long,
        maxPrefetchedAgeMs: Long?,
    ): CapsuleAudioEngine.PlaybackData? {
        val entry = entries[mediaId] ?: return null
        val now = nowMs()
        val stalePrefetch =
            entry.prefetched &&
                maxPrefetchedAgeMs != null &&
                now - entry.storedAtMs > maxPrefetchedAgeMs
        if (
            entry.context != currentContext() ||
            entry.expiresAtMs <= now + minimumRemainingMs ||
            stalePrefetch
        ) {
            entries.remove(mediaId)
            return null
        }
        return entry.data
    }

    @Synchronized
    fun put(
        mediaId: String,
        data: CapsuleAudioEngine.PlaybackData,
        context: Any? = currentContext(),
        prefetched: Boolean = false,
    ) {
        val now = nowMs()
        entries[mediaId] =
            Entry(
                data = data,
                expiresAtMs = now + data.streamExpiresInSeconds.coerceAtLeast(1) * 1_000L,
                context = context,
                storedAtMs = now,
                prefetched = prefetched,
            )
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
''',
)

replace_once(
    "app/src/test/kotlin/com/nikhil/yt/playback/audio/PlaybackDataCacheTest.kt",
    '''    @Test fun changedSessionQualityOrRouteCannotReuseAnOldUrl() {
        var context = "account-a/high/wifi"
        val cache = PlaybackDataCache(nowMs = { 0L }, currentContext = { context })
        val startedIn = context
        cache.put("track", playback(), startedIn)
        context = "account-b/low/mobile"
        assertNull(cache.get("track"))
        // An obsolete request completing after the change must not relabel its URL as new.
        cache.put("track", playback(), startedIn)
        assertNull(cache.get("track"))
        cache.put("track", playback(), context)
        assertNotNull(cache.get("track"))
    }

}
''',
    '''    @Test fun changedSessionQualityOrRouteCannotReuseAnOldUrl() {
        var context = "account-a/high/wifi"
        val cache = PlaybackDataCache(nowMs = { 0L }, currentContext = { context })
        val startedIn = context
        cache.put("track", playback(), startedIn)
        context = "account-b/low/mobile"
        assertNull(cache.get("track"))
        // An obsolete request completing after the change must not relabel its URL as new.
        cache.put("track", playback(), startedIn)
        assertNull(cache.get("track"))
        cache.put("track", playback(), context)
        assertNotNull(cache.get("track"))
    }

    @Test fun stalePrefetchedGenerationIsDroppedBeforePlayback() {
        var now = 0L
        val cache = PlaybackDataCache(nowMs = { now })
        val data = playback()

        cache.put("prefetched", data, prefetched = true)
        now = 1_001L
        assertNull(
            cache.getForPlayback(
                mediaId = "prefetched",
                maxPrefetchedAgeMs = 1_000L,
            ),
        )

        cache.put("foreground", data, prefetched = false)
        now = 2_002L
        assertSame(
            data,
            cache.getForPlayback(
                mediaId = "foreground",
                maxPrefetchedAgeMs = 1_000L,
            ),
        )
    }

}
''',
)

# Pure scheduling tests keep the final prefetch policy from drifting back toward
# start-of-track background requests.
(ROOT / "app/src/test/kotlin/com/nikhil/yt/playback/AudioPrefetchSchedulingTest.kt").write_text(
    '''package com.nikhil.yt.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPrefetchSchedulingTest {
    @Test
    fun longTrackWaitsUntilJustInTimeLeadWindow() {
        assertEquals(
            135_000L,
            audioPrefetchWaitMs(
                durationMs = 180_000L,
                positionMs = 0L,
                isPlaying = true,
            ),
        )
    }

    @Test
    fun shortTrackStillWaitsForCurrentPlaybackToWarm() {
        assertEquals(
            3_000L,
            audioPrefetchWaitMs(
                durationMs = 30_000L,
                positionMs = 0L,
                isPlaying = true,
            ),
        )
    }

    @Test
    fun pausedOrBufferingTrackOnlySchedulesARecheck() {
        assertEquals(
            AUDIO_PREFETCH_RECHECK_MS,
            audioPrefetchWaitMs(
                durationMs = 180_000L,
                positionMs = 120_000L,
                isPlaying = false,
            ),
        )
    }

    @Test
    fun readyTrackInsideLeadWindowCanPrefetchImmediately() {
        assertEquals(
            0L,
            audioPrefetchWaitMs(
                durationMs = 180_000L,
                positionMs = 150_000L,
                isPlaying = true,
            ),
        )
    }

    @Test
    fun unknownDurationStillWaitsForCurrentPlaybackWarmup() {
        assertEquals(
            2_000L,
            audioPrefetchWaitMs(
                durationMs = C.TIME_UNSET,
                positionMs = 1_000L,
                isPlaying = true,
            ),
        )
    }
}
''',
    encoding="utf-8",
)

print("step46 final CDN/prefetch polish applied")
