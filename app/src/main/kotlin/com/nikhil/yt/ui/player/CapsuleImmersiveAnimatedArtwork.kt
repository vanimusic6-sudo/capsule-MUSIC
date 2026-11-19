/*
 * Capsule MUSIC — a silent Immersion-only animated-artwork layer.
 * Independent from MusicService video playback; the static cover remains
 * underneath until Media3 has actually rendered its first frame.
 * GPL-3.0
 */
@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.ui.player

import android.view.LayoutInflater
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nikhil.yt.R
import com.nikhil.yt.canvas.CapsuleMotionArtwork
import com.nikhil.yt.canvas.CapsuleMotionClip
import com.nikhil.yt.canvas.CapsuleMotionSource
import com.nikhil.yt.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun capsuleShouldPlayMotionArtwork(
    enabled: Boolean,
    visible: Boolean,
    controlsActive: Boolean,
    isPlaying: Boolean,
    presentingVideo: Boolean,
    videoNeedsSurface: Boolean,
): Boolean = enabled && visible && controlsActive && isPlaying &&
    !presentingVideo && !videoNeedsSurface

/**
 * Only composed inside ImmersiveAudioScene's EXISTING feathered artwork crop.
 * The same photo and blurred page remain mounted as the fallback. The video is
 * decoded once by the Android hardware decoder; no WebView, frame polling,
 * synthetic per-frame bitmaps or extra AudioTrack.
 */
@Composable
internal fun CapsuleImmersiveAnimatedArtwork(
    metadata: MediaMetadata,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    var canvasOnly by remember(metadata.id) { mutableStateOf(false) }
    var unusable by remember(metadata.id) { mutableStateOf(false) }
    var clip by remember(metadata.id, canvasOnly) { mutableStateOf<CapsuleMotionClip?>(null) }

    if (!active || unusable) return

    val artist = metadata.artists.firstOrNull()?.name.orEmpty()
    LaunchedEffect(metadata.id, metadata.title, artist, metadata.album?.title,
        metadata.duration, canvasOnly) {
        clip = null
        if (metadata.title.isBlank() || artist.isBlank()) return@LaunchedEffect
        clip = withContext(Dispatchers.IO) {
            CapsuleMotionArtwork.resolve(
                song = metadata.title,
                artist = artist,
                album = metadata.album?.title,
                durationSeconds = metadata.duration,
                canvasOnly = canvasOnly,
            )
        }
    }

    val current = clip ?: return
    key(metadata.id, current.uri) {
        val context = LocalContext.current
        var firstFrame by remember { mutableStateOf(false) }
        val opacity by animateFloatAsState(
            targetValue = if (firstFrame) 1f else 0f,
            animationSpec = tween(durationMillis = 650),
            label = "capsuleArtworkFirstFrame",
        )
        // This is a *different* player from MusicService. Its audio renderer is
        // disabled as well as muted, so it cannot steal music focus or output.
        val videoPlayer = remember(current.uri) {
            ExoPlayer.Builder(context.applicationContext).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setMaxVideoSize(720, 1280)
                    .setMaxVideoBitrate(2_500_000)
                    .build()
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(current.uri)
                        .setMimeType(
                            if (current.isHls) MimeTypes.APPLICATION_M3U8
                            else MimeTypes.VIDEO_MP4,
                        ).build()
                )
            }
        }
        DisposableEffect(videoPlayer) {
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    firstFrame = true
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Apple's media stream may expire or use a codec the device
                    // doesn't support. Try the track-specific Spotify Canvas
                    // ONCE, then leave the static photo untouched.
                    if (current.source == CapsuleMotionSource.APPLE_ARTWORK && !canvasOnly) {
                        canvasOnly = true
                    } else {
                        unusable = true
                    }
                }
            }
            videoPlayer.addListener(listener)
            videoPlayer.prepare()
            videoPlayer.playWhenReady = true
            onDispose {
                videoPlayer.playWhenReady = false
                videoPlayer.removeListener(listener)
                videoPlayer.stop()
                videoPlayer.release()
            }
        }
        // PlayerView is XML-configured for TextureView (not SurfaceView), so
        // Android's native surface participates in Capsule's fade / clip mask.
        Box(modifier.fillMaxSize().graphicsLayer { alpha = opacity }) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(
                        R.layout.capsule_motion_artwork_view, null, false,
                    ) as PlayerView).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        keepScreenOn = false
                        player = videoPlayer
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
