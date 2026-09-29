/*
 * Capsule MUSIC
 *
 * Capsule is the only lyrics screen. Provider, database and manual-edit
 * behaviour still use the existing Velune lyrics pipeline.
 *
 * Licensed under GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.db.entities.LyricsEntity
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.ui.component.LocalMenuState
import com.nikhil.yt.ui.menu.LyricsMenu
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun LyricsScreen(
    mediaMetadata: MediaMetadata,
    onBackClick: () -> Unit,
    playerArtworkColors: List<Color> = emptyList(),
    backdropAnimationTime: State<Long>? = null,
    isVisible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val player = playerConnection.player
    val context = LocalContext.current
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val onScreen = appIsOnScreen()

    val currentLyrics by
        playerConnection.currentLyrics.collectAsState(initial = null)
    val isPlaying by
        playerConnection.isPlaying.collectAsState()

    // One owner for "fetch these lyrics if we do not have them", shared with the sounding line
    // under Capsule Light's artwork.
    RequestLyricsIfMissing(mediaMetadata, currentLyrics)

    var position by remember(mediaMetadata.id) {
        mutableLongStateOf(player.currentPosition.coerceAtLeast(0L))
    }
    var duration by remember(mediaMetadata.id) {
        mutableLongStateOf(player.duration)
    }
    var sliderPosition by remember(mediaMetadata.id) {
        mutableStateOf<Long?>(null)
    }

    LaunchedEffect(mediaMetadata.id, player, isVisible, onScreen, isPlaying) {
        // This is a display clock, not playback state. Hidden/backgrounded/paused surfaces should
        // do zero periodic work. A pause reads the final position once and sleeps until playback
        // resumes or the user seeks.
        if (!isVisible || !onScreen) return@LaunchedEffect

        position = player.currentPosition.coerceAtLeast(0L)
        duration = player.duration.takeIf { it > 0L } ?: C.TIME_UNSET
        if (!isPlaying) return@LaunchedEffect

        while (isActive) {
            delay(250)
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0L } ?: C.TIME_UNSET
        }
    }

    BackHandler(onBack = onBackClick)

    CapsuleLyricsContent(
        mediaMetadata = mediaMetadata,
        sliderPosition = sliderPosition,
        positionMs = position,
        durationMs = duration,
        onClose = onBackClick,
        onMenuClick = {
            menuState.show {
                LyricsMenu(
                    lyricsProvider = { currentLyrics },
                    mediaMetadataProvider = { mediaMetadata },
                    onDismiss = menuState::dismiss,
                )
            }
        },
        onSeekPreview = {
            sliderPosition = it
        },
        playerArtworkColors = playerArtworkColors,
        backdropAnimationTime = backdropAnimationTime,
        onSeekFinished = {
            sliderPosition?.let {
                player.seekTo(it)
                position = it
            }
            sliderPosition = null
        },
        isVisible = isVisible,
        modifier = modifier,
    )
}
