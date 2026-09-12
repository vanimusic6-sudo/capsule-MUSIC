package com.nikhil.yt.playback

import android.media.AudioFocusRequest
import android.media.AudioManager

internal enum class PlaybackFocusPlaybackAction {
    NONE,
    PAUSE,
    RESUME,
}

internal data class PlaybackFocusState(
    val hasFocus: Boolean = false,
    val wasPlayingBeforeLoss: Boolean = false,
    val lastFocusState: Int = AudioManager.AUDIOFOCUS_NONE,
)

internal data class PlaybackFocusDecision(
    val state: PlaybackFocusState,
    val volumeFactor: Float,
    val playbackAction: PlaybackFocusPlaybackAction,
)

internal fun reducePlaybackFocusChange(
    state: PlaybackFocusState,
    focusChange: Int,
    isPlaying: Boolean,
): PlaybackFocusDecision =
    when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        ->
            PlaybackFocusDecision(
                state =
                    state.copy(
                        hasFocus = true,
                        wasPlayingBeforeLoss = false,
                        lastFocusState = focusChange,
                    ),
                volumeFactor = 1f,
                playbackAction =
                    if (state.wasPlayingBeforeLoss) {
                        PlaybackFocusPlaybackAction.RESUME
                    } else {
                        PlaybackFocusPlaybackAction.NONE
                    },
            )

        AudioManager.AUDIOFOCUS_LOSS ->
            PlaybackFocusDecision(
                state =
                    state.copy(
                        hasFocus = false,
                        wasPlayingBeforeLoss = false,
                        lastFocusState = focusChange,
                    ),
                volumeFactor = 1f,
                playbackAction =
                    if (isPlaying) PlaybackFocusPlaybackAction.PAUSE else PlaybackFocusPlaybackAction.NONE,
            )

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
            PlaybackFocusDecision(
                state =
                    state.copy(
                        hasFocus = false,
                        wasPlayingBeforeLoss = isPlaying,
                        lastFocusState = focusChange,
                    ),
                volumeFactor = 1f,
                playbackAction =
                    if (isPlaying) PlaybackFocusPlaybackAction.PAUSE else PlaybackFocusPlaybackAction.NONE,
            )

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
            PlaybackFocusDecision(
                state =
                    state.copy(
                        hasFocus = false,
                        wasPlayingBeforeLoss = isPlaying,
                        lastFocusState = focusChange,
                    ),
                volumeFactor = 0.2f,
                playbackAction = PlaybackFocusPlaybackAction.NONE,
            )

        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK ->
            PlaybackFocusDecision(
                state =
                    state.copy(
                        hasFocus = true,
                        lastFocusState = focusChange,
                    ),
                volumeFactor = 1f,
                playbackAction = PlaybackFocusPlaybackAction.NONE,
            )

        else ->
            PlaybackFocusDecision(
                state = state,
                volumeFactor = 1f,
                playbackAction = PlaybackFocusPlaybackAction.NONE,
            )
    }

/**
 * Owns Android audio-focus request state and policy.
 *
 * It never mutates ExoPlayer directly. Focus decisions are returned through a
 * callback so MusicService remains the owner of play/pause operations.
 */
internal class PlaybackFocusController(
    private val audioManager: AudioManager,
    private val isPlayingProvider: () -> Boolean,
    private val onDecision: (PlaybackFocusDecision) -> Unit,
) {
    private var focusRequest: AudioFocusRequest? = null
    private var state = PlaybackFocusState()

    val hasFocus: Boolean
        get() = state.hasFocus

    fun initialize() {
        if (focusRequest != null) return
        focusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener(::handleFocusChange)
                .setAcceptsDelayedFocusGain(true)
                .build()
    }

    private fun handleFocusChange(focusChange: Int) {
        val decision =
            reducePlaybackFocusChange(
                state = state,
                focusChange = focusChange,
                isPlaying = isPlayingProvider(),
            )
        state = decision.state
        onDecision(decision)
    }

    fun requestFocus(): Boolean {
        if (state.hasFocus) return true
        val request = focusRequest ?: return false
        val result = audioManager.requestAudioFocus(request)
        state = state.copy(hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return state.hasFocus
    }

    fun abandonFocus() {
        if (!state.hasFocus) return
        val request = focusRequest ?: return
        audioManager.abandonAudioFocusRequest(request)
        state = state.copy(hasFocus = false)
    }
}
