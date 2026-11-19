/*
 * Capsule MUSIC — persistent, silent Canvas surface for Immersion.
 * Native renderer is mounted only while the player is visible; its ExoPlayer
 * belongs to CapsuleMotionPlaybackSession and survives sheet closure.
 * GPL-3.0.
 */
@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.withFrameNanos
import androidx.media3.common.Player
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView
import com.nikhil.yt.R
import com.nikhil.yt.canvas.CapsuleMotionArtwork
import com.nikhil.yt.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The old guard still defines discovery, but NOT surface lifetime:
 * suppress new requests while paused/hidden/video, without dropping the
 * retained Canvas timeline or replacing the video with the album image.
 */
internal fun capsuleShouldPlayMotionArtwork(
    enabled: Boolean,
    visible: Boolean,
    controlsActive: Boolean,
    isPlaying: Boolean,
    presentingVideo: Boolean,
    videoNeedsSurface: Boolean,
): Boolean = enabled && visible && controlsActive && isPlaying &&
    !presentingVideo && !videoNeedsSurface

@Composable
internal fun CapsuleImmersiveAnimatedArtwork(
    metadata: MediaMetadata,
    discover: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val session = CapsuleMotionPlaybackSession
    val context = LocalContext.current

    LaunchedEffect(enabled, discover, metadata.id, metadata.title,
        metadata.artists.firstOrNull()?.name, session.appleFailed) {
        if (!enabled) {
            session.release()
            return@LaunchedEffect
        }
        // No lookup unless the user has ACTUALLY opened Immersion.
        if (!discover) return@LaunchedEffect
        session.begin(metadata.id)
        if (session.hasPrepared(metadata.id) || session.permanentFailure) return@LaunchedEffect
        val artist = metadata.artists.firstOrNull()?.name.orEmpty()
        if (metadata.title.isBlank() || artist.isBlank()) return@LaunchedEffect
        val clip = withContext(Dispatchers.IO) {
            CapsuleMotionArtwork.resolve(
                song = metadata.title,
                artist = artist,
                album = metadata.album?.title,
                durationSeconds = metadata.duration,
                canvasOnly = session.appleFailed,
            )
        }
        if (clip != null && session.isCurrent(metadata.id)) {
            session.play(context.applicationContext, metadata.id, clip)
        }
    }

    val current = session.clip?.takeIf { session.isCurrent(metadata.id) } ?: return
    val videoPlayer = session.player ?: return

    key(metadata.id, current.uri, videoPlayer) {
        var attachedView by remember { mutableStateOf<PlayerView?>(null) }
        var surfaceReady by remember { mutableStateOf(false) }
        val frameOpacity by animateFloatAsState(
            targetValue = if (surfaceReady) 1f else 0f,
            animationSpec = tween(durationMillis = 400),
            label = "capsuleCanvasFirstFrame",
        )

        // On re-entry the last CANVAS frame (not the album cover) remains in
        // front while TextureView waits for its first newly rendered frame.
        // The native clip keeps advancing in the retained Media3 player.
        Box(modifier.fillMaxSize()) {
            if (!surfaceReady) {
                session.retainedFrame?.let { picture ->
                    Image(
                        bitmap = picture, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = frameOpacity }) {
                AndroidView(
                    factory = { ctx ->
                        (LayoutInflater.from(ctx).inflate(
                            R.layout.capsule_motion_artwork_view, null, false,
                        ) as PlayerView).apply {
                            useController = false
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            keepScreenOn = false
                            player = videoPlayer
                            attachedView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        val texture = view.videoSurfaceView as? TextureView
                        if (texture != null && texture.isAvailable && surfaceReady) {
                            // One bounded snapshot per sheet unmount. Never
                            // capture every full video frame for the transition.
                            runCatching { texture.getBitmap(256, 256) }.getOrNull()
                                ?.let { session.retain(it) }
                        }
                        view.player = null // detach surface, NOT ExoPlayer timeline
                        if (attachedView === view) attachedView = null
                    },
                )
            }
        }

        DisposableEffect(videoPlayer, attachedView) {
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    surfaceReady = true
                }
            }
            videoPlayer.addListener(listener)
            onDispose { videoPlayer.removeListener(listener) }
        }

        // Reuse Capsule's existing 48x28 video-spatial sampler and smooth
        // backdrop shader. Only capture while a canvas TextureView is shown.
        // Decoder-frame metadata drives the loop; NO polling interval or
        // second decoder. At most ~6.7 small GPU readbacks per second.
        LaunchedEffect(videoPlayer, attachedView, surfaceReady, metadata.id) {
            val view = attachedView?.videoSurfaceView as? TextureView
                ?: return@LaunchedEffect
            if (!surfaceReady) return@LaunchedEffect
            val buffer = Bitmap.createBitmap(
                VIDEO_AMBIENT_SAMPLE_WIDTH, VIDEO_AMBIENT_SAMPLE_HEIGHT,
                Bitmap.Config.ARGB_8888,
            )
            val lightFilter = CapsuleVideoLightFilter()
            val frames = Channel<Long>(Channel.CONFLATED)
            val listener = VideoFrameMetadataListener { _, releaseNs, _, _ ->
                frames.trySend(releaseNs)
            }
            videoPlayer.setVideoFrameMetadataListener(listener)
            try {
                frames.trySend(System.nanoTime())
                var lastSampleNs = 0L
                var errors = 0
                for (signal in frames) {
                    if (!isActive || !view.isAvailable || !view.isShown) break
                    val now = System.nanoTime()
                    if (now - lastSampleNs < VIDEO_AMBIENT_CAPTURE_INTERVAL_MS * 1_000_000L) continue
                    withFrameNanos { }
                    try {
                        view.getBitmap(buffer)
                        val frame = withContext(Dispatchers.Default) {
                            capsuleVideoAmbientFrameFromBitmap(
                                buffer, metadata.id, lightFilter,
                            )
                        } ?: continue
                        session.onFrame(frame)
                        lastSampleNs = now
                        errors = 0
                    } catch (_: RuntimeException) {
                        if (++errors >= 3) break
                    }
                }
            } finally {
                videoPlayer.clearVideoFrameMetadataListener(listener)
                frames.close()
                buffer.recycle()
            }
        }
    }
}
