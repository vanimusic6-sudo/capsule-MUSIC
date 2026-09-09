#!/usr/bin/env python3
from pathlib import Path
import sys

CHECK = "--check" in sys.argv


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"anchor missing in {path}: {old[:120]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"anchor not unique in {path}: count={text.count(old)}")
    if not CHECK:
        p.write_text(text.replace(old, new, 1))
        print(f"patched {path}")


def write_new(path: str, content: str) -> None:
    p = Path(path)
    if p.exists():
        raise SystemExit(f"new file already exists: {path}")
    if not CHECK:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)
        print(f"created {path}")


# 1) A connected transport retry can otherwise silently remain BUFFERING without
# another PlayerError callback. Retry the *same resolved URL* again only when
# playback made no progress, and stay inside the existing 3-attempt budget.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/PlaybackRecoveryCoordinator.kt",
    """    private val playbackBlockedProvider: () -> Boolean,\n    private val healthyPlaybackProvider: (String) -> Boolean,\n    private val pausePlayback: () -> Unit,\n    private val preparePlayback: () -> Unit,\n    private val healthyPlaybackDelayMs: Long = 5_000L,\n) {\n    private val retryBudget = PlaybackRetryBudget()\n""",
    """    private val playbackBlockedProvider: () -> Boolean,\n    private val healthyPlaybackProvider: (String) -> Boolean,\n    private val recoveryProgressProvider: (String) -> Boolean,\n    private val pausePlayback: () -> Unit,\n    private val preparePlayback: () -> Unit,\n    private val healthyPlaybackDelayMs: Long = 5_000L,\n    private val networkRetryProgressGraceMs: Long = 5_000L,\n) {\n    private val retryBudget = PlaybackRetryBudget()\n    private val noPlayableFreshResolveUsed = LinkedHashSet<String>()\n""",
)
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/PlaybackRecoveryCoordinator.kt",
    """    fun resetRetry(mediaId: String) {\n        retryBudget.reset(mediaId)\n    }\n\n    fun clearRetryBudget() {\n        retryBudget.clear()\n    }\n""",
    """    fun resetRetry(mediaId: String) {\n        retryBudget.reset(mediaId)\n        noPlayableFreshResolveUsed.remove(mediaId)\n    }\n\n    fun clearRetryBudget() {\n        retryBudget.clear()\n        noPlayableFreshResolveUsed.clear()\n    }\n\n    /** Exactly one clean playback resolve may follow a deterministic no-stream result. */\n    fun claimNoPlayableFreshResolve(mediaId: String): Boolean {\n        if (!noPlayableFreshResolveUsed.add(mediaId)) return false\n        if (noPlayableFreshResolveUsed.size > 128) {\n            noPlayableFreshResolveUsed.remove(noPlayableFreshResolveUsed.first())\n        }\n        return true\n    }\n""",
)
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/PlaybackRecoveryCoordinator.kt",
    """        waitingForNetworkConnection.value = true\n        if (!connectedProvider()) return\n\n        val retryDelay = retryBudget.nextDelayMs(mediaId)\n""",
    """        waitingForNetworkConnection.value = true\n        if (!connectedProvider()) {\n            Timber.tag(\"PlaybackRecovery\").i(\n                \"Transport recovery waiting for connectivity id=%s; resolved stream preserved\",\n                mediaId,\n            )\n            return\n        }\n\n        val retryDelay = retryBudget.nextDelayMs(mediaId)\n""",
)
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/PlaybackRecoveryCoordinator.kt",
    """                if (waitingForNetworkConnection.value && connectedProvider()) {\n                    waitingForNetworkConnection.value = false\n                    preparePlayback()\n                }\n                networkRecoveryJob = null\n""",
    """                if (waitingForNetworkConnection.value && connectedProvider()) {\n                    waitingForNetworkConnection.value = false\n                    Timber.tag(\"PlaybackRecovery\").i(\n                        \"Retrying same resolved stream id=%s delayMs=%d\",\n                        mediaId,\n                        retryDelay,\n                    )\n                    preparePlayback()\n\n                    // Media3 can occasionally remain BUFFERING/isLoading=false after a\n                    // transport reset without emitting a second PlayerError. Give the\n                    // same URL a bounded grace period, then spend the next existing\n                    // retry-budget slot. This never starts a new YouTube resolve.\n                    delay(networkRetryProgressGraceMs)\n                    if (\n                        playWhenReadyProvider() &&\n                        currentMediaIdProvider() == mediaId &&\n                        currentIndexProvider() == index &&\n                        positionGenerationProvider() == positionGeneration &&\n                        connectedProvider() &&\n                        !playbackBlockedProvider() &&\n                        !recoveryProgressProvider(mediaId)\n                    ) {\n                        networkRecoveryJob = null\n                        Timber.tag(\"PlaybackRecovery\").w(\n                            \"Same-stream transport retry made no progress id=%s; using next bounded retry\",\n                            mediaId,\n                        )\n                        recoverFromNetworkError()\n                        return@launch\n                    }\n                }\n                networkRecoveryJob = null\n""",
)

# 2) Wire READY-as-progress separately from the stricter sustained-playback reset.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    """            healthyPlaybackProvider = { mediaId ->\n                player.currentMediaItem?.mediaId == mediaId &&\n                    player.playbackState == Player.STATE_READY &&\n                    player.isPlaying\n            },\n            pausePlayback = { player.pause() },\n""",
    """            healthyPlaybackProvider = { mediaId ->\n                player.currentMediaItem?.mediaId == mediaId &&\n                    player.playbackState == Player.STATE_READY &&\n                    player.isPlaying\n            },\n            recoveryProgressProvider = { mediaId ->\n                player.currentMediaItem?.mediaId == mediaId &&\n                    player.playbackState == Player.STATE_READY\n            },\n            pausePlayback = { player.pause() },\n""",
)

# 3) A confirmed no-playable-stream result is not a generic terminal error. Allow
# one fresh resolve using the SAME selected policy, then stop. No client carousel.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    """        if (!isNetworkConnected.value || error.isTransientNetworkFailure()) {\n            playbackRecoveryCoordinator.recoverFromNetworkError()\n            return\n        }\n\n        val shouldAttemptStreamRefresh =\n""",
    """        if (currentMediaId != null && error.isNoPlayableStreamFailure()) {\n            val claimed = playbackRecoveryCoordinator.claimNoPlayableFreshResolve(currentMediaId)\n            val retryDelay = if (claimed) playbackRecoveryCoordinator.nextRetryDelayMs(currentMediaId) else null\n            if (retryDelay != null && CapsuleAudioEngine.playbackBlockedExceptionOrNull() == null) {\n                // Clear only song-local extraction state. Keep the user's selected\n                // client/profile and every global anti-bot/rate-limit guard intact.\n                CapsuleAudioEngine.clearTrackClientFailures(currentMediaId)\n                CapsuleAudioEngine.invalidateCachedStreamUrls(currentMediaId)\n                audioResolveCoordinator.cancelMedia(currentMediaId) {\n                    playbackUrlCache.remove(currentMediaId)\n                }\n                Timber.tag(CAPSULE_RESOLVE_TAG).w(\n                    \"No playable stream id=%s; scheduling one clean same-policy resolve\",\n                    currentMediaId,\n                )\n                scheduleStreamRefreshRetry(\n                    mediaId = currentMediaId,\n                    refreshCipherConfig = false,\n                    retryReason = \"no playable stream\",\n                    retryDelayMs = retryDelay,\n                )\n                return\n            }\n\n            Timber.tag(CAPSULE_RESOLVE_TAG).w(\n                \"No playable stream id=%s; bounded fresh-resolve retry unavailable\",\n                currentMediaId,\n            )\n            handleTerminalPlaybackError()\n            return\n        }\n\n        if (!isNetworkConnected.value || error.isTransientNetworkFailure()) {\n            playbackRecoveryCoordinator.recoverFromNetworkError()\n            return\n        }\n\n        val shouldAttemptStreamRefresh =\n""",
)
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt",
    """    private fun PlaybackException.isTransientNetworkFailure(): Boolean {\n""",
    """    internal fun PlaybackException.isNoPlayableStreamFailure(): Boolean =\n        generateSequence(this as Throwable?) { it?.cause }\n            .take(8)\n            .any { throwable ->\n                val message = throwable?.message.orEmpty()\n                message.contains(\"No playable stream found for this track\", ignoreCase = true) ||\n                    message.contains(\"InnerTubeX returned no playable AUDIO stream\", ignoreCase = true)\n            }\n\n    private fun PlaybackException.isTransientNetworkFailure(): Boolean {\n""",
)

# 4) LRCLIB really returns duration:null. Treat unknown duration as unknown rather
# than rejecting the whole JSON response or accidentally matching it as 0 seconds.
replace_once(
    "lrclib/src/main/kotlin/com/nikhil/yt/lrclib/models/Track.kt",
    """    val artistName: String,\n    val duration: Double,\n    val plainLyrics: String?,\n""",
    """    val artistName: String,\n    val duration: Double? = null,\n    val plainLyrics: String?,\n""",
)
replace_once(
    "lrclib/src/main/kotlin/com/nikhil/yt/lrclib/models/Track.kt",
    """    return minByOrNull { abs(it.duration.toInt() - duration) }\n        ?.takeIf { abs(it.duration.toInt() - duration) <= 2 }\n""",
    """    return mapNotNull { track ->\n        track.duration\n            ?.takeIf { it.isFinite() }\n            ?.let { value -> track to abs(value.toInt() - duration) }\n    }.minByOrNull { (_, delta) -> delta }\n        ?.takeIf { (_, delta) -> delta <= 2 }\n        ?.first\n""",
)
replace_once(
    "lrclib/src/main/kotlin/com/nikhil/yt/lrclib/LrcLib.kt",
    """            else -> {\n                tracks.sortedBy { abs(it.duration.toInt() - duration) }\n            }\n""",
    """            else -> {\n                tracks.sortedBy { track ->\n                    track.duration\n                        ?.takeIf { it.isFinite() }\n                        ?.let { value -> abs(value.toInt() - duration) }\n                        ?: Int.MAX_VALUE\n                }\n            }\n""",
)
replace_once(
    "lrclib/src/main/kotlin/com/nikhil/yt/lrclib/LrcLib.kt",
    """                } else {\n                    if (track.syncedLyrics != null && abs(track.duration.toInt() - duration) <= 2) {\n                        count++\n                        track.syncedLyrics.let(callback)\n                    }\n                    if (track.plainLyrics != null && abs(track.duration.toInt() - duration) <= 2 && plain == 0) {\n                        count++\n                        plain++\n                        track.plainLyrics.let(callback)\n                    }\n                }\n""",
    """                } else {\n                    val durationDelta =\n                        track.duration\n                            ?.takeIf { it.isFinite() }\n                            ?.let { value -> abs(value.toInt() - duration) }\n                    if (track.syncedLyrics != null && durationDelta != null && durationDelta <= 2) {\n                        count++\n                        track.syncedLyrics.let(callback)\n                    }\n                    if (track.plainLyrics != null && durationDelta != null && durationDelta <= 2 && plain == 0) {\n                        count++\n                        plain++\n                        track.plainLyrics.let(callback)\n                    }\n                }\n""",
)

# Expected LRCLIB misses should not be emitted as error stack traces by LyricsHelper.
replace_once(
    "app/src/main/kotlin/com/nikhil/yt/lyrics/LrcLibLyricsProvider.kt",
    """    ): Result<String> = LrcLib.getLyrics(title, artist, duration)\n""",
    """    ): Result<String> =\n        LrcLib.getLyrics(title, artist, duration).fold(\n            onSuccess = { Result.success(it) },\n            onFailure = { failure ->\n                if (failure is IllegalStateException && failure.message == \"Lyrics unavailable\") {\n                    Result.failure(com.nikhil.yt.betterlyrics.LyricsUnavailableException())\n                } else {\n                    Result.failure(failure)\n                }\n            },\n        )\n""",
)

write_new(
    "app/src/test/kotlin/com/nikhil/yt/playback/PlaybackRecoveryTransportTest.kt",
    r'''package com.nikhil.yt.playback

import androidx.media3.common.PlaybackException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRecoveryTransportTest {
    @Test
    fun silentBufferingAfterTransportRetryUsesNextBoundedSameStreamAttempt() = runTest {
        var ready = false
        var prepares = 0
        val coordinator = PlaybackRecoveryCoordinator(
            scopeProvider = { this },
            maxConsecutiveTrackFailures = 3,
            currentMediaIdProvider = { "song" },
            playWhenReadyProvider = { true },
            currentIndexProvider = { 0 },
            positionGenerationProvider = { 0L },
            connectedProvider = { true },
            playbackBlockedProvider = { false },
            healthyPlaybackProvider = { ready },
            recoveryProgressProvider = { ready },
            pausePlayback = {},
            preparePlayback = { prepares += 1 },
            healthyPlaybackDelayMs = 5_000L,
            networkRetryProgressGraceMs = 100L,
        )

        coordinator.recoverFromNetworkError()
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, prepares)

        advanceTimeBy(100L)
        runCurrent()
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(2, prepares)

        ready = true
        advanceTimeBy(100L)
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(2, prepares)
    }

    @Test
    fun noPlayableFreshResolveClaimIsOneShotUntilHealthyReset() = runTest {
        val coordinator = PlaybackRecoveryCoordinator(
            scopeProvider = { this },
            maxConsecutiveTrackFailures = 3,
            currentMediaIdProvider = { "song" },
            playWhenReadyProvider = { true },
            currentIndexProvider = { 0 },
            positionGenerationProvider = { 0L },
            connectedProvider = { true },
            playbackBlockedProvider = { false },
            healthyPlaybackProvider = { true },
            recoveryProgressProvider = { true },
            pausePlayback = {},
            preparePlayback = {},
        )

        assertTrue(coordinator.claimNoPlayableFreshResolve("song"))
        assertFalse(coordinator.claimNoPlayableFreshResolve("song"))
        coordinator.resetRetry("song")
        assertTrue(coordinator.claimNoPlayableFreshResolve("song"))
    }

    @Test
    fun noPlayableStreamCauseIsClassifiedWithoutTreatingGenericRemoteErrorsAsRetryable() {
        val noStream = PlaybackException(
            "unknown",
            IllegalStateException("No playable stream found for this track."),
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )
        val generic = PlaybackException(
            "unknown",
            IllegalStateException("some other remote failure"),
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )

        assertTrue(noStream.isNoPlayableStreamFailure())
        assertFalse(generic.isNoPlayableStreamFailure())
    }
}
''',
)

write_new(
    "lrclib/src/test/kotlin/com/nikhil/yt/lrclib/models/TrackNullableDurationTest.kt",
    r'''package com.nikhil.yt.lrclib.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackNullableDurationTest {
    @Test
    fun searchResponseAcceptsNullDurationAndDoesNotTreatItAsZero() {
        val tracks = Json { ignoreUnknownKeys = true }.decodeFromString<List<Track>>(
            """[
              {"id":1,"trackName":"MIXTAPE","artistName":"Artist","duration":null,"plainLyrics":"plain","syncedLyrics":"[00:01.00]one"},
              {"id":2,"trackName":"MIXTAPE","artistName":"Artist","duration":201.2,"plainLyrics":null,"syncedLyrics":"[00:01.00]two"}
            ]""",
        )

        assertNull(tracks[0].duration)
        assertEquals(2, tracks.bestMatchingFor(201)?.id)
        assertNull(listOf(tracks[0]).bestMatchingFor(201))
    }
}
''',
)

if CHECK:
    print("step36 patch is applicable")
