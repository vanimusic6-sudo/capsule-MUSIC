package com.nikhil.yt.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.nikhil.yt.constants.EnableLastFMScrobblingKey
import com.nikhil.yt.constants.LastFMUseNowPlaying
import com.nikhil.yt.constants.ListenBrainzEnabledKey
import com.nikhil.yt.constants.ListenBrainzTokenKey
import com.nikhil.yt.constants.ScrobbleDelayPercentKey
import com.nikhil.yt.constants.ScrobbleDelaySecondsKey
import com.nikhil.yt.constants.ScrobbleMinSongDurationKey
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.lastfm.LastFM
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.ui.screens.settings.ListenBrainzManager
import com.nikhil.yt.utils.ScrobbleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal data class LastFmScrobbleSettings(
    val enabled: Boolean,
    val useNowPlaying: Boolean,
    val delayPercent: Float,
    val minSongDuration: Int,
    val delaySeconds: Int,
)

internal fun finishedListenWindow(
    endMs: Long,
    totalPlayTimeMs: Long,
): Pair<Long, Long> = (endMs - totalPlayTimeMs) to endMs

/**
 * Owns external scrobbling lifecycle and configuration for MusicService.
 *
 * The existing [ScrobbleManager] remains the single owner of Last.fm timer
 * semantics. This class only reconciles preferences, forwards playback events,
 * and submits completed listens to ListenBrainz.
 */
@OptIn(FlowPreview::class)
internal class ScrobbleCoordinator(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val scopeProvider: () -> CoroutineScope,
    private val ioScopeProvider: () -> CoroutineScope,
    private val songProvider: suspend (String) -> Song?,
    private val onFailure: (String, Throwable) -> Unit,
) {
    private var settingsJob: Job? = null
    private var lastFmManager: ScrobbleManager? = null

    fun start() {
        if (settingsJob?.isActive == true) return

        settingsJob =
            scopeProvider().launch {
                dataStore.data
                    .map { preferences ->
                        LastFmScrobbleSettings(
                            enabled = preferences[EnableLastFMScrobblingKey] ?: false,
                            useNowPlaying = preferences[LastFMUseNowPlaying] ?: false,
                            delayPercent =
                                preferences[ScrobbleDelayPercentKey]
                                    ?: LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT,
                            minSongDuration =
                                preferences[ScrobbleMinSongDurationKey]
                                    ?: LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
                            delaySeconds =
                                preferences[ScrobbleDelaySecondsKey]
                                    ?: LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS,
                        )
                    }
                    .debounce(300)
                    .distinctUntilChanged()
                    .collect(::reconcileLastFm)
            }
    }

    private fun reconcileLastFm(settings: LastFmScrobbleSettings) {
        if (!settings.enabled) {
            lastFmManager?.destroy()
            lastFmManager = null
            return
        }

        val manager =
            lastFmManager
                ?: ScrobbleManager(
                    scope = ioScopeProvider(),
                    minSongDuration = settings.minSongDuration,
                    scrobbleDelayPercent = settings.delayPercent,
                    scrobbleDelaySeconds = settings.delaySeconds,
                ).also { lastFmManager = it }

        manager.useNowPlaying = settings.useNowPlaying
        manager.scrobbleDelayPercent = settings.delayPercent
        manager.minSongDuration = settings.minSongDuration
        manager.scrobbleDelaySeconds = settings.delaySeconds
    }

    fun onSongStart(
        metadata: MediaMetadata?,
        duration: Long? = null,
    ) {
        lastFmManager?.onSongStart(metadata, duration)
    }

    fun onSongStop() {
        lastFmManager?.onSongStop()
    }

    fun onPlayerStateChanged(
        isPlaying: Boolean,
        metadata: MediaMetadata?,
        duration: Long? = null,
    ) {
        lastFmManager?.onPlayerStateChanged(isPlaying, metadata, duration)
    }

    fun onPlaybackFinished(
        mediaId: String,
        totalPlayTimeMs: Long,
    ) {
        ioScopeProvider().launch {
            try {
                val preferences = dataStore.data.first()
                val enabled = preferences[ListenBrainzEnabledKey] ?: false
                val token = preferences[ListenBrainzTokenKey].orEmpty()
                if (!enabled || token.isBlank()) return@launch

                val song = songProvider(mediaId) ?: return@launch
                val endMs = System.currentTimeMillis()
                val (startMs, finishedAtMs) = finishedListenWindow(endMs, totalPlayTimeMs)
                ListenBrainzManager.submitFinished(
                    context = context,
                    token = token,
                    song = song,
                    startMs = startMs,
                    endMs = finishedAtMs,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure("submit finished ListenBrainz event", error)
            }
        }
    }

    fun destroy() {
        settingsJob?.cancel()
        settingsJob = null
        lastFmManager?.destroy()
        lastFmManager = null
    }
}
