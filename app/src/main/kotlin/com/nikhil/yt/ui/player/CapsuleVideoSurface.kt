/* Capsule MUSIC — retained paused frame over Media3 TextureView output. GPL-3.0.
 * TextureView attachment follows AndroidX Media3 PlayerSurface (Apache-2.0,
 * Copyright 2025 The Android Open Source Project). */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.ui.player

import android.content.Context
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
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
    val state by service.videoPlaybackState.collectAsState()
    val presentation = rememberPresentationState(player, keepContentOnReset = state.presentingVideo)
    val frame = still?.takeIf { it.exiting ||
        (!player.playWhenReady && it.mediaId == player.currentMediaItem?.mediaId) }
    // The captured bitmap has exactly the TextureView's aspect. Use its bounds
    // through pause/exit even if Media3 resets videoSize while stopping.
    val sourceSize = frame?.let { Size(it.bitmap.width.toFloat(), it.bitmap.height.toFloat()) }
        ?: presentation.videoSizeDp
    Box(modifier.clipToBounds().background(Color.Black)) {
      CapsuleVideoViewport(sourceSize) {
        if (active) CapsuleVideoTextureOutput(service, player, retention, presentation.coverSurface, state.firstFrameReady)
        if (frame != null) {
            Image(
                bitmap = remember(frame.bitmap) { frame.bitmap.asImageBitmap() },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
      }
    }
}

@Composable
internal fun CapsuleVideoViewport(sourceSize: Size?, content: @Composable () -> Unit) {
    // Live output and still share one fitted, centred viewport. Neither crops
    // encoded black borders nor changes scale independently on pause.
    Box(Modifier.fillMaxSize().resizeWithContentScale(ContentScale.Fit, sourceSize)) { content() }
}

@Composable
private fun CapsuleVideoTextureOutput(service: MusicService, player: Player, retention: CapsuleVideoFrameRetention,
    coverSurface: Boolean, firstFrameReady: Boolean) {
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
        update = {},
        onRelease = { retention.retainPausedFrame(); player.clearVideoTextureView(it) },
    )
    val view = texture
    if (view != null) {
        DisposableEffect(view, player, retention) {
            retention.registerCapture(view) {
                if (view.hasVideoFrame && view.isAvailable && view.width > 0 && view.height > 0) {
                    // Read once on pause/exit, never on a playback timer. Bound the one retained bitmap.
                    val scale = minOf(1f, 960f / view.width, 540f / view.height)
                    try { view.getBitmap((view.width * scale).toInt().coerceAtLeast(1),
                        (view.height * scale).toInt().coerceAtLeast(1)) } catch (_: RuntimeException) { null }
                } else null
            }
            val listener = object : Player.Listener {
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
    if (coverSurface && !firstFrameReady) {
        Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

private class CapsuleFrameTextureView(context: Context, private val retention: CapsuleVideoFrameRetention) : TextureView(context) {
    var hasVideoFrame = false
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
