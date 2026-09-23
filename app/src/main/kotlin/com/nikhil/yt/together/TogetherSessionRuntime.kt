package com.nikhil.yt.together

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Owns mutable runtime resources and echo-suppression bookkeeping for Together.
 *
 * Keeping these resources outside MusicService gives the service a single
 * session-runtime boundary and makes shutdown/reset behavior consistent for
 * LAN, online-host and guest sessions.
 */
internal class TogetherSessionRuntime(
    private val onRecoverableError: (operation: String, error: Throwable) -> Unit,
) {
    val sessionState = MutableStateFlow<TogetherSessionState>(TogetherSessionState.Idle)

    var server: TogetherServer? = null
    var onlineHost: TogetherOnlineHost? = null
    var client: TogetherClient? = null

    var broadcastJob: Job? = null
    var onlineConnectJob: Job? = null
    var clientEventsJob: Job? = null
    var heartbeatJob: Job? = null

    var clock: TogetherClock? = null
    var selfParticipantId: String? = null
    var lastAppliedQueueHash: String? = null
    var isOnlineSession: Boolean = false

    @Volatile
    var applyingRemote: Boolean = false
        private set

    @Volatile
    var suppressEchoUntilElapsedMs: Long = 0L
        private set

    @Volatile
    var lastAppliedRoomStateSentAtElapsedMs: Long = 0L

    @Volatile
    var lastRemoteAppliedPlayWhenReady: Boolean? = null

    @Volatile
    var lastRemoteAppliedIndex: Int = -1

    fun beginRemoteApply(nowElapsedMs: Long) {
        applyingRemote = true
        suppressEchoUntilElapsedMs = nowElapsedMs + REMOTE_ECHO_SUPPRESSION_MS
    }

    fun finishRemoteApply() {
        applyingRemote = false
    }

    fun shouldSuppressSeekEcho(nowElapsedMs: Long, index: Int): Boolean =
        applyingRemote ||
            (nowElapsedMs < suppressEchoUntilElapsedMs && lastRemoteAppliedIndex == index)

    fun shouldSuppressPlayWhenReadyEcho(
        nowElapsedMs: Long,
        playWhenReady: Boolean,
    ): Boolean =
        applyingRemote ||
            (
                nowElapsedMs < suppressEchoUntilElapsedMs &&
                    lastRemoteAppliedPlayWhenReady != null &&
                    lastRemoteAppliedPlayWhenReady == playWhenReady
            )

    fun resetBookkeeping() {
        clock = null
        selfParticipantId = null
        lastAppliedQueueHash = null
        isOnlineSession = false
        applyingRemote = false
        suppressEchoUntilElapsedMs = 0L
        lastAppliedRoomStateSentAtElapsedMs = 0L
        lastRemoteAppliedPlayWhenReady = null
        lastRemoteAppliedIndex = -1
    }

    suspend fun stopConnections() {
        broadcastJob?.cancel()
        broadcastJob = null

        onlineConnectJob?.cancel()
        onlineConnectJob = null

        clientEventsJob?.cancel()
        clientEventsJob = null

        heartbeatJob?.cancel()
        heartbeatJob = null

        resetBookkeeping()

        try {
            client?.disconnect()
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            onRecoverableError("disconnect Together client", error)
        }
        client = null

        try {
            onlineHost?.disconnect()
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            onRecoverableError("disconnect Together host", error)
        }
        onlineHost = null

        try {
            server?.stop()
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            onRecoverableError("stop Together server", error)
        }
        server = null
    }

    companion object {
        private const val REMOTE_ECHO_SUPPRESSION_MS = 450L
    }
}
