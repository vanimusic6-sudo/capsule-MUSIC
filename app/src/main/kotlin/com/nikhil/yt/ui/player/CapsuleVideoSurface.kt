/* Capsule MUSIC — retained paused frame over Media3 TextureView output. GPL-3.0.
 * TextureView attachment follows AndroidX Media3 PlayerSurface (Apache-2.0,
 * Copyright 2025 The Android Open Source Project). */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.ui.player

import android.content.Context
import android.graphics.Matrix
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.compose.state.rememberPresentationState
import com.nikhil.yt.playback.MusicService
import com.nikhil.yt.playback.video.CapsuleVideoFrameRetention

/** The decoder may redraw a frame while paused after a new surface. Keep the user's still above it. */
@Composable
internal fun CapsuleVideoSurface(service: MusicService, modifier: Modifier = Modifier, active: Boolean = true) {
    val player = service.videoRenderPlayer
    val retention = service.videoFrameRetention
    if (player == null || retention == null) {
        Box(modifier.background(Color.Black))
        return
    }
    val still by retention.frame.collectAsState()
    Box(modifier.clipToBounds().background(Color.Black)) {
        if (active) CapsuleVideoTextureOutput(service, player, retention)
        val frame = still
        if (frame != null && (frame.exiting ||
                (!player.playWhenReady && frame.mediaId == player.currentMediaItem?.mediaId))) {
            Image(
                bitmap = remember(frame.bitmap) { frame.bitmap.asImageBitmap() },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun CapsuleVideoTextureOutput(service: MusicService, player: Player, retention: CapsuleVideoFrameRetention) {
    val state by service.videoPlaybackState.collectAsState()
    val presentation = rememberPresentationState(player, keepContentOnReset = state.presentingVideo)
    val owner = remember(service, player) { Any() }
    var texture by remember { mutableStateOf<CapsuleFrameTextureView?>(null) }
    val host = LocalView.current
    DisposableEffect(service, player, owner) {
        service.attachVideoSurface(owner)
        onDispose { service.detachVideoSurface(owner) }
    }
    DisposableEffect(host, player) {
        val wasKeepingScreenOn = host.keepScreenOn
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                host.keepScreenOn = player.playWhenReady && player.currentMediaItem != null
            }
        }
        player.addListener(listener)
        host.keepScreenOn = player.playWhenReady && player.currentMediaItem != null
        onDispose { player.removeListener(listener); host.keepScreenOn = wasKeepingScreenOn }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { CapsuleFrameTextureView(it, retention).also { view -> texture = view } },
        onReset = {},
        update = { view ->
            val size = presentation.videoSizeDp
            view.setSourceAspect(if (size != null && size.height > 0f) size.width / size.height else 16f / 9f)
        },
        onRelease = { retention.retainPausedFrame(); player.clearVideoTextureView(it) },
    )
    val view = texture
    if (view != null) {
        DisposableEffect(view, player, retention) {
            retention.registerCapture(view) {
                if (view.hasVideoFrame && view.isAvailable && view.width > 0 && view.height > 0) {
                    // Read once on pause/exit, never on a playback timer. Bound the one retained bitmap.
                    val scale = minOf(1f, 1280f / view.width, 720f / view.height)
                    try { view.getBitmap((view.width * scale).toInt().coerceAtLeast(1),
                        (view.height * scale).toInt().coerceAtLeast(1)) } catch (_: RuntimeException) { null }
                } else null
            }
            val listener = object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.height > 0) view.setSourceAspect(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                }
                override fun onRenderedFirstFrame() {
                    view.hasVideoFrame = true
                    view.postOnAnimation { retention.retainPausedFrame() }
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    view.hasVideoFrame = false
                }
            }
            player.addListener(listener)
            onDispose {
                retention.retainPausedFrame()
                retention.unregisterCapture(view)
                player.removeListener(listener)
                // ExoPlayer clears only this view, so an old design cannot clear its replacement.
                player.clearVideoTextureView(view)
            }
        }
        // Start after Media3's presentation observer, matching PlayerSurface's attachment order.
        LaunchedEffect(view, player) { player.setVideoTextureView(view) }
    }
    if (presentation.coverSurface) Box(Modifier.fillMaxSize().background(Color.Black))
}

private class CapsuleFrameTextureView(context: Context, private val retention: CapsuleVideoFrameRetention) : TextureView(context) {
    init { isOpaque = false }
    var hasVideoFrame = false
    private var sourceAspect = 16f / 9f
    private val fitTransform = Matrix()
    fun setSourceAspect(value: Float) {
        if (sourceAspect == value) return
        sourceAspect = value
        applyFitTransform()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyFitTransform()
    }
    private fun applyFitTransform() {
        if (width <= 0 || height <= 0) return
        val fit = capsuleVideoFitScale(sourceAspect, width.toFloat() / height)
        fitTransform.setScale(fit.first, fit.second, width / 2f, height / 2f)
        setTransform(fitTransform)
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.VISIBLE) {
            retention.retainPausedFrame()
            hasVideoFrame = false
        }
        super.onWindowVisibilityChanged(visibility)
    }
    override fun onDetachedFromWindow() {
        retention.retainPausedFrame()
        hasVideoFrame = false
        super.onDetachedFromWindow()
    }
}

/** Live TextureView and retained bitmap use the same fixed viewport. */
internal fun capsuleVideoFitScale(sourceAspect: Float, viewportAspect: Float): Pair<Float, Float> =
    if (sourceAspect <= 0f || viewportAspect <= 0f) 1f to 1f
    else if (sourceAspect >= viewportAspect) 1f to (viewportAspect / sourceAspect)
    else (sourceAspect / viewportAspect) to 1f
