package com.nikhil.yt.playback.presence

import android.content.Context
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.ui.screens.settings.ListenBrainzManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ImmediatePresenceUpdateGate(
    private val minIntervalMs: Long = 20_000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var lastAcceptedAtMs: Long? = null

    fun tryAcquire(): Boolean = synchronized(lock) {
        val now = nowMillis()
        val previous = lastAcceptedAtMs
        if (previous != null && now - previous <= minIntervalMs) {
            return@synchronized false
        }
        lastAcceptedAtMs = now
        true
    }
}

internal fun shouldSubmitListenBrainzPlayingNow(
    enabled: Boolean,
    token: String,
    songAvailable: Boolean,
): Boolean = enabled && token.isNotBlank() && songAvailable

/**
 * Owns immediate external playback-presence updates triggered by Media3 events.
 *
 * Discord and ListenBrainz are intentionally independent destinations. The old
 * MusicService callbacks accidentally nested ListenBrainz under the Discord-token
 * check, so disabling Discord could suppress an otherwise enabled ListenBrainz
 * playing_now submission.
 */
internal class PlaybackPresenceCoordinator(
    context: Context,
    private val scopeProvider: () -> CoroutineScope,
    private val discordOwner: DiscordPresenceOwner,
    private val currentMediaIdProvider: () -> String?,
    private val songProvider: suspend (mediaId: String?) -> Song?,
    private val positionProvider: () -> Long,
    private val isPausedProvider: () -> Boolean,
    private val listenBrainzEnabledProvider: suspend () -> Boolean,
    private val listenBrainzTokenProvider: suspend () -> String,
    private val onFailure: (operation: String, error: Throwable) -> Unit,
    private val gate: ImmediatePresenceUpdateGate = ImmediatePresenceUpdateGate(),
) {
    private val appContext = context.applicationContext

    fun requestImmediateUpdate() {
        scopeProvider().launch {
            val mediaId = currentMediaIdProvider()?.trim()?.takeIf(String::isNotBlank)
            val positionMs = positionProvider().coerceAtLeast(0L)
            val isPaused = isPausedProvider()

            try {
                val song = songProvider(mediaId)

                // Never publish a DB result for a track that stopped being current
                // while the lookup was suspended.
                val stillCurrent = currentMediaIdProvider()?.trim()?.takeIf(String::isNotBlank)
                if (stillCurrent != mediaId) return@launch

                if (!gate.tryAcquire()) return@launch

                val discordResult =
                    withContext(Dispatchers.IO) {
                        discordOwner.updateNow(
                            song = song,
                            positionMs = positionMs,
                            isPaused = isPaused,
                        )
                    }
                if (discordResult == DiscordPresenceUpdateResult.FAILED) {
                    discordOwner.restart()
                }

                val lbEnabled = listenBrainzEnabledProvider()
                val lbToken = listenBrainzTokenProvider().trim()
                if (
                    shouldSubmitListenBrainzPlayingNow(
                        enabled = lbEnabled,
                        token = lbToken,
                        songAvailable = song != null,
                    )
                ) {
                    withContext(Dispatchers.IO) {
                        ListenBrainzManager.submitPlayingNow(
                            context = appContext,
                            token = lbToken,
                            song = song,
                            positionMs = positionMs,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onFailure("update playback presence", error)
            }
        }
    }
}
