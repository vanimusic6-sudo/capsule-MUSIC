@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback

import androidx.media3.common.Player

/** An errored IDLE source is discarded by prepare(); rebuilding its queue is unnecessary.
 * Only a stuck active source needs full recreation. Preserve the captured play intent. */
internal fun prepareAudioSourceRetry(
    player: Player,
    index: Int,
    positionMs: Long,
    playWhenReady: Boolean,
    forceRecreateSources: Boolean,
    recreate: () -> Unit,
) {
    if (forceRecreateSources && player.playbackState != Player.STATE_IDLE) {
        recreate()
    } else {
        if (player.currentMediaItemIndex != index || player.currentPosition != positionMs) {
            player.seekTo(index, positionMs)
        }
        player.playWhenReady = playWhenReady
        player.prepare()
    }
}

/** Unknown stream/device volume is not evidence of a muted local output. */
internal fun isSystemMediaMuted(volume: Int?, explicitlyMuted: Boolean?, volumeFixed: Boolean): Boolean? =
    if (volumeFixed) false else when {
        explicitlyMuted == true -> true
        volume != null && volume >= 0 -> volume == 0
        else -> null
    }
