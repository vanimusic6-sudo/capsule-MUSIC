/* Capsule MUSIC — paused/exit frame retention. GPL-3.0. */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import android.graphics.Bitmap
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CapsuleRetainedVideoFrame(val mediaId: String, val bitmap: Bitmap, val exiting: Boolean)

/** One still per clip. Surface reconstruction never replaces a user's paused frame. */
internal class CapsuleVideoFrameRetention(private val player: Player, private val scope: CoroutineScope) {
    private val mutableFrame = MutableStateFlow<CapsuleRetainedVideoFrame?>(null)
    val frame = mutableFrame.asStateFlow()
    private var owner: Any? = null
    private var capture: (() -> Bitmap?)? = null
    private var expiry: Job? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (mutableFrame.value?.exiting == true) return // Player.stop() must not clear the reverse-motion still.
            if (player.playWhenReady) {
                clear()
            } else {
                if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    mutableFrame.value?.mediaId?.let { it != player.currentMediaItem?.mediaId } == true) {
                    clear()
                    return
                }
                // A seek needs its newly decoded frame, not the old surface bitmap.
                if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) retainPausedFrame()
            }
        }
    }
    init { player.addListener(listener) }

    fun registerCapture(owner: Any, capture: () -> Bitmap?) {
        this.owner = owner
        this.capture = capture
    }
    fun unregisterCapture(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        capture = null
    }
    fun retainPausedFrame() {
        if (player.playWhenReady || mutableFrame.value != null) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val bitmap = capture?.invoke() ?: return
        mutableFrame.value = CapsuleRetainedVideoFrame(mediaId, bitmap, exiting = false)
    }
    fun retainExitFrame() {
        if (mutableFrame.value?.exiting == true) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val bitmap = mutableFrame.value?.takeIf { it.mediaId == mediaId }?.bitmap ?: capture?.invoke() ?: return
        expiry?.cancel()
        val still = CapsuleRetainedVideoFrame(mediaId, bitmap, exiting = true)
        mutableFrame.value = still
        expiry = scope.launch {
            delay(1_600L) // Keep the exit frame through the softer reverse motion.
            if (mutableFrame.value === still) mutableFrame.value = null
            expiry = null
        }
    }
    fun clear() {
        expiry?.cancel(); expiry = null
        // Let Bitmap/ImageBitmap references retire normally; never recycle a frame being drawn.
        mutableFrame.value = null
    }
    fun release() {
        player.removeListener(listener)
        clear()
        owner = null; capture = null
    }
}
