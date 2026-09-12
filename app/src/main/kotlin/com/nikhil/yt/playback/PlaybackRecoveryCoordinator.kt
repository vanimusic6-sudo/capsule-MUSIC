package com.nikhil.yt.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

internal enum class TerminalPlaybackAction {
    SKIP,
    STOP,
}

internal data class TerminalPlaybackDecision(
    val action: TerminalPlaybackAction,
    val circuitOpenedNow: Boolean,
    val mayAutoSkip: Boolean,
    val failureCount: Int,
    val circuitOpen: Boolean,
)

/**
 * Owns automatic audio-playback recovery bookkeeping and delayed recovery work.
 *
 * The Media3 error callback and all ExoPlayer mutations stay in MusicService.
 * This class owns only retry/failure state and delayed recovery scheduling.
 */
internal class PlaybackRecoveryCoordinator(
    private val scopeProvider: () -> CoroutineScope,
    maxConsecutiveTrackFailures: Int,
    private val currentMediaIdProvider: () -> String?,
    private val playWhenReadyProvider: () -> Boolean,
    private val currentIndexProvider: () -> Int,
    private val positionGenerationProvider: () -> Long,
    private val connectedProvider: () -> Boolean,
    private val playbackBlockedProvider: () -> Boolean,
    private val healthyPlaybackProvider: (String) -> Boolean,
    private val recoveryProgressProvider: (String) -> Boolean = healthyPlaybackProvider,
    private val pausePlayback: () -> Unit,
    private val preparePlayback: () -> Unit,
    private val healthyPlaybackDelayMs: Long = 5_000L,
    private val networkRetryProgressGraceMs: Long = 5_000L,
) {
    private val retryBudget = PlaybackRetryBudget()
    private val noPlayableFreshResolveUsed = LinkedHashSet<String>()
    private val failureGuard = ConsecutiveTrackFailureGuard(maxConsecutiveTrackFailures)

    private var networkRecoveryJob: Job? = null
    private var healthyResetJob: Job? = null

    val waitingForNetworkConnection = MutableStateFlow(false)

    fun nextRetryDelayMs(mediaId: String): Long? = retryBudget.nextDelayMs(mediaId)

    fun resetRetry(mediaId: String) {
        retryBudget.reset(mediaId)
        noPlayableFreshResolveUsed.remove(mediaId)
    }

    fun clearRetryBudget() {
        retryBudget.clear()
        noPlayableFreshResolveUsed.clear()
    }

    /** Exactly one clean playback resolve may follow a deterministic no-stream result. */
    fun claimNoPlayableFreshResolve(mediaId: String): Boolean {
        if (!noPlayableFreshResolveUsed.add(mediaId)) return false
        if (noPlayableFreshResolveUsed.size > 128) {
            noPlayableFreshResolveUsed.remove(noPlayableFreshResolveUsed.first())
        }
        return true
    }

    fun cancelNetworkRecovery(clearWaiting: Boolean = true) {
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        if (clearWaiting) waitingForNetworkConnection.value = false
    }

    fun onConnectivityChanged(isConnected: Boolean) {
        if (
            isConnected &&
            waitingForNetworkConnection.value &&
            networkRecoveryJob?.isActive != true
        ) {
            recoverFromNetworkError()
        }
    }

    fun recoverFromNetworkError() {
        cancelNetworkRecovery(clearWaiting = true)

        val mediaId = currentMediaIdProvider() ?: return
        if (!playWhenReadyProvider()) return

        waitingForNetworkConnection.value = true
        if (!connectedProvider()) {
            Timber.tag("PlaybackRecovery").i(
                "Transport recovery waiting for connectivity id=%s; resolved stream preserved",
                mediaId,
            )
            return
        }

        val retryDelay = retryBudget.nextDelayMs(mediaId)
        if (retryDelay == null || playbackBlockedProvider()) {
            waitingForNetworkConnection.value = false
            pausePlayback()
            Timber.tag("PlaybackRecovery").w(
                "Network recovery stopped for %s; automatic retry budget exhausted or cooldown active",
                mediaId,
            )
            return
        }

        val index = currentIndexProvider()
        val positionGeneration = positionGenerationProvider()
        networkRecoveryJob =
            scopeProvider().launch {
                delay(retryDelay)
                if (
                    !playWhenReadyProvider() ||
                    currentMediaIdProvider() != mediaId ||
                    currentIndexProvider() != index ||
                    positionGenerationProvider() != positionGeneration
                ) {
                    waitingForNetworkConnection.value = false
                    networkRecoveryJob = null
                    return@launch
                }
                if (playbackBlockedProvider()) {
                    waitingForNetworkConnection.value = false
                    pausePlayback()
                    networkRecoveryJob = null
                    return@launch
                }
                if (waitingForNetworkConnection.value && connectedProvider()) {
                    waitingForNetworkConnection.value = false
                    Timber.tag("PlaybackRecovery").i(
                        "Retrying same resolved stream id=%s delayMs=%d",
                        mediaId,
                        retryDelay,
                    )
                    preparePlayback()

                    // Media3 can occasionally remain BUFFERING/isLoading=false after a
                    // transport reset without emitting a second PlayerError. Give the
                    // same URL a bounded grace period, then spend the next existing
                    // retry-budget slot. This never starts a new YouTube resolve.
                    delay(networkRetryProgressGraceMs)
                    if (
                        playWhenReadyProvider() &&
                        currentMediaIdProvider() == mediaId &&
                        currentIndexProvider() == index &&
                        positionGenerationProvider() == positionGeneration &&
                        connectedProvider() &&
                        !playbackBlockedProvider() &&
                        !recoveryProgressProvider(mediaId)
                    ) {
                        networkRecoveryJob = null
                        Timber.tag("PlaybackRecovery").w(
                            "Same-stream transport retry made no progress id=%s; using next bounded retry",
                            mediaId,
                        )
                        recoverFromNetworkError()
                        return@launch
                    }
                }
                networkRecoveryJob = null
            }
    }

    fun onPlaybackActivity(
        mediaId: String?,
        ready: Boolean,
        playing: Boolean,
    ) {
        if (!ready || !playing || mediaId.isNullOrBlank()) {
            cancelHealthyReset()
            return
        }
        scheduleHealthyReset(mediaId)
    }

    private fun scheduleHealthyReset(mediaId: String) {
        healthyResetJob?.cancel()
        healthyResetJob =
            scopeProvider().launch {
                delay(healthyPlaybackDelayMs)
                if (healthyPlaybackProvider(mediaId)) {
                    failureGuard.onHealthyPlayback()
                    resetRetry(mediaId)
                    Timber.tag("PlaybackRecovery").i(
                        "Playback failure circuit reset after healthy playback id=%s",
                        mediaId,
                    )
                }
                healthyResetJob = null
            }
    }

    fun cancelHealthyReset() {
        healthyResetJob?.cancel()
        healthyResetJob = null
    }

    fun resetFailureGuard() {
        cancelHealthyReset()
        failureGuard.reset()
    }

    fun recordTerminalFailure(
        mediaId: String?,
        autoSkipEnabled: Boolean,
    ): TerminalPlaybackDecision {
        cancelHealthyReset()
        val wasOpen = failureGuard.isOpen
        val mayAutoSkip = failureGuard.recordFailure(mediaId)
        return TerminalPlaybackDecision(
            action =
                if (mayAutoSkip && autoSkipEnabled) {
                    TerminalPlaybackAction.SKIP
                } else {
                    TerminalPlaybackAction.STOP
                },
            circuitOpenedNow = !wasOpen && failureGuard.isOpen,
            mayAutoSkip = mayAutoSkip,
            failureCount = failureGuard.failureCount,
            circuitOpen = failureGuard.isOpen,
        )
    }

    fun cancelTransientWork() {
        cancelNetworkRecovery()
        cancelHealthyReset()
    }
}
