/* Capsule MUSIC — retained paused frame over Media3 TextureView output. GPL-3.0.
 * TextureView attachment follows AndroidX Media3 PlayerSurface (Apache-2.0,
 * Copyright 2025 The Android Open Source Project). */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.ui.player

import android.content.Context
import android.view.TextureView
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import com.nikhil.yt.playback.MusicService
import com.nikhil.yt.playback.video.CapsuleVideoFrameRetention
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val targetZoom by retention.borderZoom.collectAsState()
    val zoom by animateFloatAsState(targetZoom, tween(220), label = "capsule-video-framing")
    Box(modifier.clipToBounds().background(Color.Black)) {
      Box(Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom }) {
        if (active) CapsuleVideoTextureOutput(service, player, retention)
        val frame = still
        if (frame != null && (frame.exiting ||
                (!player.playWhenReady && frame.mediaId == player.currentMediaItem?.mediaId))) {
            Image(
                bitmap = remember(frame.bitmap) { frame.bitmap.asImageBitmap() },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
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
    val scope = rememberCoroutineScope()
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
        modifier = Modifier.fillMaxSize().resizeWithContentScale(ContentScale.Crop, presentation.videoSizeDp),
        factory = { CapsuleFrameTextureView(it, retention).also { view -> texture = view } },
        onReset = {},
        update = {},
        onRelease = { retention.retainPausedFrame(); player.clearVideoTextureView(it) },
    )
    val view = texture
    if (view != null) {
        DisposableEffect(view, player, retention) {
            var sampling: Job? = null
            var sampledMediaId: String? = null
            fun sampleBorders() {
                val mediaId = player.currentMediaItem?.mediaId ?: return
                if (sampledMediaId == mediaId || sampling?.isActive == true) return
                if (retention.borderZoom.value > 1f) return
                sampling?.cancel()
                sampling = scope.launch {
                    val consensus = CapsuleVideoBorderConsensus()
                    repeat(5) { attempt ->
                        delay(if (attempt == 0) 48L else 400L)
                        if (!view.isAvailable || !view.hasVideoFrame ||
                            player.currentMediaItem?.mediaId != mediaId) return@launch
                        val snapshot = try { view.getBitmap(160, 90) } catch (_: RuntimeException) { null }
                        if (snapshot != null) {
                            val insets = try { detectCapsuleVideoInsets(160, 90, snapshot::getPixel) }
                                finally { snapshot.recycle() }
                            val agreed = consensus.offer(insets)
                            if (agreed != null) {
                                sampledMediaId = mediaId
                                val size = player.videoSize
                                val aspect = if (size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 16f / 9f
                                retention.setBorderZoom(mediaId, agreed.zoom(aspect, 16f / 9f))
                                return@launch
                            }
                        }
                    }
                    sampledMediaId = mediaId
                }
            }
            retention.registerCapture(view) {
                if (view.hasVideoFrame && view.isAvailable && view.width > 0 && view.height > 0) {
                    // Read once on pause/exit, never on a playback timer. Bound the one retained bitmap.
                    val scale = minOf(1f, 1280f / view.width, 720f / view.height)
                    try { view.getBitmap((view.width * scale).toInt().coerceAtLeast(1),
                        (view.height * scale).toInt().coerceAtLeast(1)) } catch (_: RuntimeException) { null }
                } else null
            }
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    view.hasVideoFrame = true
                    view.postOnAnimation { retention.retainPausedFrame() }
                    sampleBorders()
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    view.hasVideoFrame = false
                    sampling?.cancel()
                    sampling = null
                }
            }
            player.addListener(listener)
            onDispose {
                retention.retainPausedFrame()
                sampling?.cancel()
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
