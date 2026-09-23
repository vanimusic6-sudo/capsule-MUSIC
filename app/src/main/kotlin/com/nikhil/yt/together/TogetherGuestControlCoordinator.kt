package com.nikhil.yt.together

internal data class TogetherGuestReconcileDecision(
    val applyRemoteState: Boolean,
    val notifySongChangeFailure: Boolean = false,
)

internal class TogetherGuestControlCoordinator(
    private val dedupeWindowMs: Long = 350L,
    private val localTimeoutMs: Long = 2_000L,
    private val onlineTimeoutMs: Long = 5_000L,
    private val songChangeFailureThresholdMs: Long = 1_200L,
) {
    private data class PendingControl(
        val desiredIsPlaying: Boolean? = null,
        val desiredIndex: Int? = null,
        val desiredTrackId: String? = null,
        val requestedAtElapsedMs: Long,
        val expiresAtElapsedMs: Long,
    )

    private var lastSentControlAtElapsedMs: Long = 0L
    private var lastSentControlAction: ControlAction? = null
    private var pendingControl: PendingControl? = null

    fun registerOutgoing(
        action: ControlAction,
        nowElapsedMs: Long,
        isOnlineSession: Boolean,
    ): Boolean {
        val lastAction = lastSentControlAction
        val lastAt = lastSentControlAtElapsedMs
        if (lastAction == action && nowElapsedMs - lastAt < dedupeWindowMs) return false

        lastSentControlAction = action
        lastSentControlAtElapsedMs = nowElapsedMs

        val timeoutMs = if (isOnlineSession) onlineTimeoutMs else localTimeoutMs
        pendingControl =
            when (action) {
                ControlAction.Play ->
                    PendingControl(
                        desiredIsPlaying = true,
                        requestedAtElapsedMs = nowElapsedMs,
                        expiresAtElapsedMs = nowElapsedMs + timeoutMs,
                    )

                ControlAction.Pause ->
                    PendingControl(
                        desiredIsPlaying = false,
                        requestedAtElapsedMs = nowElapsedMs,
                        expiresAtElapsedMs = nowElapsedMs + timeoutMs,
                    )

                is ControlAction.SeekToIndex ->
                    PendingControl(
                        desiredIndex = action.index.coerceAtLeast(0),
                        requestedAtElapsedMs = nowElapsedMs,
                        expiresAtElapsedMs = nowElapsedMs + timeoutMs,
                    )

                is ControlAction.SeekToTrack ->
                    PendingControl(
                        desiredTrackId = action.trackId.trim().ifBlank { null },
                        requestedAtElapsedMs = nowElapsedMs,
                        expiresAtElapsedMs = nowElapsedMs + timeoutMs,
                    )

                else -> pendingControl
            }

        return true
    }

    fun reconcile(
        state: TogetherRoomState,
        nowElapsedMs: Long,
    ): TogetherGuestReconcileDecision {
        val pending = pendingControl ?: return TogetherGuestReconcileDecision(applyRemoteState = true)
        val currentTrackId = state.queue.getOrNull(state.currentIndex.coerceAtLeast(0))?.id
        val mismatch =
            (pending.desiredIsPlaying != null && state.isPlaying != pending.desiredIsPlaying) ||
                (pending.desiredIndex != null && state.currentIndex != pending.desiredIndex) ||
                (pending.desiredTrackId != null && currentTrackId != pending.desiredTrackId)

        if (nowElapsedMs >= pending.expiresAtElapsedMs) {
            val shouldNotifySongChangeFailure =
                mismatch &&
                    (pending.desiredIndex != null || pending.desiredTrackId != null) &&
                    nowElapsedMs - pending.requestedAtElapsedMs >= songChangeFailureThresholdMs
            pendingControl = null
            return TogetherGuestReconcileDecision(
                applyRemoteState = true,
                notifySongChangeFailure = shouldNotifySongChangeFailure,
            )
        }

        if (mismatch) {
            return TogetherGuestReconcileDecision(applyRemoteState = false)
        }

        pendingControl = null
        return TogetherGuestReconcileDecision(applyRemoteState = true)
    }

    fun reset() {
        lastSentControlAtElapsedMs = 0L
        lastSentControlAction = null
        pendingControl = null
    }
}
