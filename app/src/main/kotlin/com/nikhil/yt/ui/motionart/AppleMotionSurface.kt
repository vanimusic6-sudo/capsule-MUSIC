/*
 * Capsule MUSIC — silent, lifecycle-bound Motion Artwork surface.
 * Dedicated decorator only: never shares or controls the audio/video players.
 * GPL-3.0.
 */
package com.nikhil.yt.ui.motionart

import android.content.Context
import android.os.PowerManager
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import com.nikhil.yt.ui.component.LocalCapsuleBackgroundMotionEnabled

@Composable
internal fun AppleMotionSurface(
    artist: String,
    album: String? = null,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    dimBackground: Boolean = false,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) foreground = true
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) foreground = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // No decoder, network probe or frame timer during battery saver or while
    // the sheet is collapsed / screen is not in the foreground.
    val powerSave = remember(context) {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    }
    val reduceMotion = !LocalCapsuleBackgroundMotionEnabled.current
    val allowed = enabled && foreground && !powerSave && !reduceMotion && artist.isNotBlank()
    var url by remember(album, artist) { mutableStateOf<String?>(null) }
    LaunchedEffect(album, artist, allowed) {
        if (!allowed) return@LaunchedEffect
        try {
            url = if (album.isNullOrBlank()) AppleMotionArtworkProvider.artist(artist)
            else AppleMotionArtworkProvider.album(album, artist)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { url = null }
    }
    val activeUrl = url?.takeIf { allowed && AppleMotionArtworkProvider.validVideoUrl(it) }
    if (activeUrl != null) {
        val exo = remember(activeUrl) {
            ExoPlayer.Builder(context).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(MediaItem.fromUri(activeUrl))
                prepare()
                playWhenReady = true
            }
        }
        var rendered by remember(activeUrl) { mutableStateOf(false) }
        var failed by remember(activeUrl) { mutableStateOf(false) }
        DisposableEffect(exo) {
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() { rendered = true }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    failed = true
                }
            }
            exo.addListener(listener)
            onDispose {
                exo.removeListener(listener)
                exo.release()
            }
        }
        val motionAlpha by animateFloatAsState(
            targetValue = if (rendered && !failed) 1f else 0f,
            animationSpec = tween(durationMillis = 580),
            label = "capsuleMotionArtworkFirstFrame",
        )
        if (!failed) {
            Box(modifier.graphicsLayer { alpha = motionAlpha }) {
                AndroidView(
                    factory = {
                        android.view.LayoutInflater.from(it)
                            .inflate(com.nikhil.yt.R.layout.capsule_motion_video, null) as PlayerView
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { it.player = exo },
                )
                if (dimBackground) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.2f),
                            Color.Black.copy(alpha = 0.36f),
                            Color.Black.copy(alpha = 0.68f),
                        ),
                    )))
                }
            }
        }
    }
}
