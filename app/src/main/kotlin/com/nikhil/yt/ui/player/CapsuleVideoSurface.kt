/* Capsule MUSIC — Media3 Compose video output with an opaque black shutter. GPL-3.0. */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.Player
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.nikhil.yt.playback.MusicService

/** No PlayerView artwork/shutter layer or page-colored overlay around the video. */
@Composable
internal fun CapsuleVideoSurface(service: MusicService, modifier: Modifier = Modifier) {
    val player = service.videoRenderPlayer
    val owner = remember(service, player) { Any() }
    val host = LocalView.current
    DisposableEffect(service, player, owner) {
        service.attachVideoSurface(owner)
        onDispose { service.detachVideoSurface(owner) }
    }
    DisposableEffect(host, player) {
        val wasKeepingScreenOn = host.keepScreenOn
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { host.keepScreenOn = isPlaying }
        }
        player?.addListener(listener)
        host.keepScreenOn = player?.isPlaying == true
        onDispose {
            player?.removeListener(listener)
            host.keepScreenOn = wasKeepingScreenOn
        }
    }
    Box(modifier.background(Color.Black)) {
        ContentFrame(
            player = player, modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW, contentScale = ContentScale.Fit,
            keepContentOnReset = false,
        )
    }
}
