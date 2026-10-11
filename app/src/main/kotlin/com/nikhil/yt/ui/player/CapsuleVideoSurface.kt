/* Capsule MUSIC — one lifecycle-aware native video surface. GPL-3.0. */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.ui.player

import android.view.LayoutInflater
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.nikhil.yt.R
import com.nikhil.yt.playback.MusicService

/** TextureView follows Compose clipping/alpha; release cannot retain a ghost surface. */
@Composable
internal fun CapsuleVideoSurface(service: MusicService, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            (LayoutInflater.from(context).inflate(R.layout.capsule_video_surface, null) as PlayerView).apply {
                useController = false
                hideController()
                setKeepContentOnPlayerReset(false)
                player = service.videoRenderPlayer
                service.attachVideoSurface(this)
            }
        },
        onRelease = { view ->
            service.detachVideoSurface(view)
            view.player = null
            view.keepScreenOn = false
        },
        update = { view ->
            if (view.player !== service.videoRenderPlayer) view.player = service.videoRenderPlayer
            // User pause stops keeping the display awake.
            view.keepScreenOn = service.videoRenderPlayer?.playWhenReady == true
        },
        modifier = modifier,
    )
}
