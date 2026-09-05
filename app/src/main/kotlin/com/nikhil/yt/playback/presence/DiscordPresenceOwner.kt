package com.nikhil.yt.playback.presence

import android.content.Context
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.ui.screens.settings.DiscordPresenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class DiscordPresenceReconcileAction {
    KEEP,
    STOP,
    RESTART,
}

internal fun decideDiscordPresenceReconcileAction(
    enabled: Boolean,
    configuredToken: String,
    managerRunning: Boolean,
    activeToken: String?,
): DiscordPresenceReconcileAction {
    val token = configuredToken.trim()
    if (!enabled || token.isBlank()) return DiscordPresenceReconcileAction.STOP
    if (managerRunning && activeToken == token) return DiscordPresenceReconcileAction.KEEP
    return DiscordPresenceReconcileAction.RESTART
}

/**
 * Owns the lifecycle of the single Discord presence connection used by playback.
 *
 * MusicService should request reconciliation; it must not create a second DiscordRPC
 * instance. Keeping the active token here also makes token replacement explicit:
 * a running manager with a different configured token is restarted instead of being
 * accepted merely because "some" presence manager is already running.
 */
internal class DiscordPresenceOwner(
    context: Context,
    private val scopeProvider: () -> CoroutineScope,
    private val enabledProvider: suspend () -> Boolean,
    private val tokenProvider: suspend () -> String,
    private val songProvider: () -> Song?,
    private val positionProvider: () -> Long,
    private val isPausedProvider: () -> Boolean,
    private val intervalProvider: () -> Long,
    private val onFailure: (operation: String, error: Throwable) -> Unit,
) {
    private val appContext = context.applicationContext
    private val reconcileMutex = Mutex()

    @Volatile
    private var activeToken: String? = null

    fun ensure() {
        scopeProvider().launch {
            try {
                reconcile()
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                onFailure("reconcile Discord presence", error)
            }
        }
    }

    fun restart() {
        scopeProvider().launch {
            try {
                reconcileMutex.withLock {
                    val enabled = enabledProvider()
                    val token = tokenProvider().trim()
                    if (!enabled || token.isBlank()) {
                        stopLocked()
                        return@withLock
                    }
                    stopLocked()
                    startLocked(token)
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                onFailure("restart Discord presence", error)
            }
        }
    }

    suspend fun reconcile() {
        reconcile(
            enabled = enabledProvider(),
            configuredToken = tokenProvider(),
        )
    }

    suspend fun reconcile(
        enabled: Boolean,
        configuredToken: String,
    ) {
        reconcileMutex.withLock {
            val normalizedToken = configuredToken.trim()
            when (
                decideDiscordPresenceReconcileAction(
                    enabled = enabled,
                    configuredToken = normalizedToken,
                    managerRunning = DiscordPresenceManager.isRunning(),
                    activeToken = activeToken,
                )
            ) {
                DiscordPresenceReconcileAction.KEEP -> Unit
                DiscordPresenceReconcileAction.STOP -> stopLocked()
                DiscordPresenceReconcileAction.RESTART -> {
                    stopLocked()
                    startLocked(normalizedToken)
                }
            }
        }
    }

    fun stop() {
        activeToken = null
        try {
            DiscordPresenceManager.stop()
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            onFailure("stop Discord presence", error)
        }
    }

    private fun startLocked(token: String) {
        try {
            DiscordPresenceManager.start(
                context = appContext,
                token = token,
                songProvider = songProvider,
                positionProvider = positionProvider,
                isPausedProvider = isPausedProvider,
                intervalProvider = intervalProvider,
            )
            activeToken = token
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            activeToken = null
            onFailure("start Discord presence", error)
        }
    }

    private fun stopLocked() {
        activeToken = null
        try {
            DiscordPresenceManager.stop()
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            onFailure("stop Discord presence", error)
        }
    }
}
