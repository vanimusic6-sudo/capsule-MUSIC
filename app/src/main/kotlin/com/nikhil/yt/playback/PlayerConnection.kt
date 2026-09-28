/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Timeline
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.extensions.currentMetadata
import com.nikhil.yt.extensions.getCurrentQueueIndex
import com.nikhil.yt.extensions.getQueueWindows
import com.nikhil.yt.extensions.metadata
import com.nikhil.yt.playback.MusicService.MusicBinder
import com.nikhil.yt.playback.queues.Queue
import com.nikhil.yt.utils.reportException
import com.nikhil.yt.utils.reportRecoverableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerConnection(
    context: Context,
    binder: MusicBinder,
    val database: MusicDatabase,
    private val scope: CoroutineScope,
) : Player.Listener {
    val service = binder.service
    val player = service.player

    val playbackState = MutableStateFlow(player.playbackState)
    private val playWhenReady = MutableStateFlow(player.playWhenReady)
    val playbackParameters = MutableStateFlow(player.playbackParameters)
    val isPlaying =
        combine(playbackState, playWhenReady) { playbackState, playWhenReady ->
            playWhenReady && playbackState != STATE_ENDED
        }.stateIn(
            scope,
            SharingStarted.Lazily,
            player.playWhenReady && player.playbackState != STATE_ENDED
        )
    val mediaMetadata = MutableStateFlow(player.currentMetadata)
    val currentSong =
        mediaMetadata.flatMapLatest {
            database.song(it?.id)
        }
    val currentLyrics = mediaMetadata.flatMapLatest { mediaMetadata ->
        database.lyrics(mediaMetadata?.id)
    }
    val currentFormat =
        mediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }

    val queueTitle = MutableStateFlow<String?>(null)
    val queueWindows = MutableStateFlow<List<Timeline.Window>>(emptyList())
    val currentMediaItemIndex = MutableStateFlow(-1)
    val currentWindowIndex = MutableStateFlow(-1)

    val shuffleModeEnabled = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(REPEAT_MODE_OFF)

    val canSkipPrevious = MutableStateFlow(true)
    val canSkipNext = MutableStateFlow(true)

    val error = MutableStateFlow<PlaybackException?>(null)
    private var pendingUiErrorJob: Job? = null
    val waitingForNetworkConnection = service.waitingForNetworkConnection
    val queueRestoreCompleted = service.queueRestoreCompleted

    init {
        player.addListener(this)

        playbackState.value = player.playbackState
        playWhenReady.value = player.playWhenReady
        playbackParameters.value = player.playbackParameters
        val currentMeta = player.currentMetadata ?: service.currentMediaMetadata.value
        mediaMetadata.value = currentMeta
        queueTitle.value = service.queueTitle
        queueWindows.value = player.getQueueWindows()
        currentWindowIndex.value = player.getCurrentQueueIndex()
        currentMediaItemIndex.value = player.currentMediaItemIndex
        shuffleModeEnabled.value = player.shuffleModeEnabled
        repeatMode.value = player.repeatMode
        
        if (currentMeta == null && player.mediaItemCount > 0) {
            val mediaItem = player.currentMediaItem
            if (mediaItem != null) {
                mediaMetadata.value = mediaItem.metadata
            }
        }
    }

    fun playQueue(queue: Queue) {
        service.playQueue(queue)
    }

    fun startRadioSeamlessly() {
        service.startRadioSeamlessly()
    }

    fun playNext(item: MediaItem) = playNext(listOf(item))

    fun playNext(items: List<MediaItem>) {
        service.playNext(items)
    }

    fun addToQueue(item: MediaItem) = addToQueue(listOf(item))

    fun addToQueue(items: List<MediaItem>) {
        service.addToQueue(items)
    }

    fun toggleLike() {
        service.toggleLike("player_ui")
    }

    fun seekToNext() {
        val state = service.togetherSessionState.value as? com.nikhil.yt.together.TogetherSessionState.Joined
        if (state?.role is com.nikhil.yt.together.TogetherRole.Guest) {
            service.requestTogetherControl(com.nikhil.yt.together.ControlAction.SkipNext)
            return
        }
        player.seekToNext()
        player.prepare()
        player.playWhenReady = true
        // Immediately restart the Discord presence updater so it picks up the new track without waiting
        if (com.nikhil.yt.ui.screens.settings.DiscordPresenceManager.isRunning()) {
            try {
                com.nikhil.yt.ui.screens.settings.DiscordPresenceManager.restart()
            } catch (error: Exception) {
                reportRecoverableException("PlayerConnection", "restart Discord presence after seek", error)
            }
        }
    }

    fun seekToPrevious() {
        val state = service.togetherSessionState.value as? com.nikhil.yt.together.TogetherSessionState.Joined
        if (state?.role is com.nikhil.yt.together.TogetherRole.Guest) {
            service.requestTogetherControl(com.nikhil.yt.together.ControlAction.SkipPrevious)
            return
        }
        player.seekToPrevious()
        player.prepare()
        player.playWhenReady = true
        // Immediately restart the Discord presence updater so it picks up the new track without waiting
        if (com.nikhil.yt.ui.screens.settings.DiscordPresenceManager.isRunning()) {
            try {
                com.nikhil.yt.ui.screens.settings.DiscordPresenceManager.restart()
            } catch (error: Exception) {
                reportRecoverableException("PlayerConnection", "restart Discord presence after next", error)
            }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        playbackState.value = state
        // Media3 can briefly surface an I/O error while the service is already replacing an
        // obsolete source after a skip. Recovery still happens immediately in MusicService; the
        // UI only clears here. A persistent error is published by onPlayerErrorChanged after the
        // grace period below.
        if (state == Player.STATE_READY || player.playerError == null) {
            clearPendingUiError()
        }
    }

    override fun onPlayWhenReadyChanged(
        newPlayWhenReady: Boolean,
        reason: Int,
    ) {
        playWhenReady.value = newPlayWhenReady
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        this.playbackParameters.value = playbackParameters
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        // Never carry an obsolete source failure onto the artwork of the next track.
        clearPendingUiError()
        val meta = mediaItem?.metadata ?: service.currentMediaMetadata.value
        mediaMetadata.value = meta
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onTimelineChanged(
        timeline: Timeline,
        reason: Int,
    ) {
        queueWindows.value = player.getQueueWindows()
        queueTitle.value = service.queueTitle
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
        shuffleModeEnabled.value = enabled
        queueWindows.value = player.getQueueWindows()
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onRepeatModeChanged(mode: Int) {
        repeatMode.value = mode
        updateCanSkipPreviousAndNext()
    }

    override fun onPlayerErrorChanged(playbackError: PlaybackException?) {
        pendingUiErrorJob?.cancel()

        if (playbackError == null) {
            error.value = null
            return
        }

        // Logging and MusicService recovery remain immediate. Only the visual error card waits:
        // transient code-2000/source errors during a skip normally disappear as soon as the fresh
        // source becomes READY, so flashing a full error card for them is actively misleading.
        reportException(playbackError)
        val mediaIdAtFailure = player.currentMediaItem?.mediaId
        pendingUiErrorJob =
            scope.launch {
                delay(UI_ERROR_GRACE_MS)
                val sameItem = player.currentMediaItem?.mediaId == mediaIdAtFailure
                val sameError = player.playerError === playbackError
                val recovered = player.playbackState == Player.STATE_READY
                if (sameItem && sameError && !recovered) {
                    error.value = playbackError
                }
            }
    }

    private fun clearPendingUiError() {
        pendingUiErrorJob?.cancel()
        pendingUiErrorJob = null
        error.value = null
    }

    private fun updateCanSkipPreviousAndNext() {
        if (!player.currentTimeline.isEmpty) {
            val window =
                player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
            canSkipPrevious.value = player.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) ||
                    !window.isLive ||
                    player.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            canSkipNext.value = window.isLive &&
                    window.isDynamic ||
                    player.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            canSkipPrevious.value = false
            canSkipNext.value = false
        }
    }

    fun dispose() {
        clearPendingUiError()
        player.removeListener(this)
    }

    private companion object {
        const val UI_ERROR_GRACE_MS = 1_200L
    }
}
