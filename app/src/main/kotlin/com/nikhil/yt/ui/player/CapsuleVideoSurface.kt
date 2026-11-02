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
import androidx.compose.runtime.SideEffect
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
import timber.log.Timber
import androidx.media3.common.Player
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import com.nikhil.yt.playback.MusicService
import com.nikhil.yt.playback.video.CapsuleVideoFrameRetention

/** The decoder may redraw a frame while paused after a new surface. Keep the user's still above it. */
@Composable
internal fun CapsuleVideoSurface(service: MusicService, modifier: Modifier = Modifier, active: Boolean = true) {
    // Cold first VIDEO request: the companion does not exist when the surface
    // composable first enters composition. Subscribe BEFORE the early return,
    // otherwise creation of the companion does not cause this view to recompose.
    // Read State.value now, not a lazy delegated value after the null guard.
    // Otherwise Compose does not observe it during the first cold composition.
    val state = service.videoPlaybackState.collectAsState().value
    val player = service.videoRenderPlayer
    val retention = service.videoFrameRetention
    if (player == null || retention == null) {
        Box(modifier.background(Color.Black))
        return
    }
    val still by retention.frame.collectAsState()
    val presentation = rememberPresentationState(player, keepContentOnReset = state.presentingVideo)
    val frame = still?.takeIf { it.exiting ||
        (!player.playWhenReady && it.mediaId == player.currentMediaItem?.mediaId) }
    // Decoder dimensions can arrive after the first frame. A provisional 16:9
    // slot must never become a permanent lock for square/portrait clips.
    val coded = player.videoSize
    val decoderSize = presentation.videoSizeDp ?: coded.takeIf { it.width > 0 && it.height > 0 }
        ?.let { Size(it.width * it.pixelWidthHeightRatio, it.height.toFloat()) }
    val streamSize = state.width?.takeIf { it > 0 }?.let { width ->
        state.height?.takeIf { it > 0 }?.let { height -> Size(width.toFloat(), height.toFloat()) }
    }
    val viewportSize = rememberCapsuleVideoViewportSize(
        player.currentMediaItem?.mediaId, decoderSize, streamSize,
        frame?.let { Size(it.bitmap.width.toFloat(), it.bitmap.height.toFloat()) },
    )
    Box(modifier.clipToBounds().background(Color.Black)) {
      CapsuleVideoViewport(viewportSize) {
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

/** Real dimensions replace provisional metadata immediately; resets retain only a known size. */
@Composable
internal fun rememberCapsuleVideoViewportSize(
    videoKey: String?, decoderSize: Size?, streamSize: Size?, retainedSize: Size?,
): Size {
    fun Size?.valid() = this?.takeIf { it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f }
    var lastKnown by remember(videoKey) { mutableStateOf<Size?>(null) }
    val measured = decoderSize.valid() ?: streamSize.valid()
    SideEffect { if (measured != null && measured != lastKnown) lastKnown = measured }
    return retainedSize.valid() ?: measured ?: lastKnown ?: Size(16f, 9f)
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
    // Surface ownership is registered only after the real TextureView has a
    // SurfaceTexture. Previously this was called at composition time, before
    // AndroidView existed, so a cold decoder could be marked rendering-ready
    // without any actual output target.
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
            }
        }
        // Observe native attachment/pre-draw once, then sleep. Never poll the
        // surface every 32 ms, or replace Media3's SurfaceTextureListener.
        DisposableEffect(view, player, service, owner) {
            player.setVideoTextureView(view)
            val binding = CapsuleVideoSurfaceBinding(
                view = view,
                isReady = { view.isAvailable && view.width > 0 && view.height > 0 },
                onAttached = {
                    service.attachVideoSurface(owner)
                    Timber.tag("CapsuleVideo").i("VIDEO surface-bound available=true width=%d height=%d", view.width, view.height)
                },
                onDetached = { service.detachVideoSurface(owner) },
            )
            onDispose {
                binding.dispose()
                retention.retainPausedFrame()
                player.clearVideoTextureView(view)
            }
        }
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
