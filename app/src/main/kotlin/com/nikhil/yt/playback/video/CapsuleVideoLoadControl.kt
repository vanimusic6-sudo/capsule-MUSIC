/* Capsule MUSIC — no background loading for a parked VIDEO decoder. GPL-3.0. */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

internal class CapsuleVideoLoadControl(
    private val delegate: LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(2_500, 12_000, 350, 750)
        .setPrioritizeTimeOverSizeThresholds(true).build(),
) : LoadControl {
    @Volatile var parked = false

    // Kotlin interface delegation can inherit Java default methods instead of
    // forwarding them. Media3's compatibility defaults throw unless a concrete
    // LoadControl implements the current player-aware API, so forward explicitly.
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        !parked && delegate.shouldContinueLoading(parameters)
    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = !parked && delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)
}
