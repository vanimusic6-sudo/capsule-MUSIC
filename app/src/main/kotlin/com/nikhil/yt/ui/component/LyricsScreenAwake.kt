package com.nikhil.yt.ui.component

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.LocalActivity

/** Screen-on is owned by visible, playing lyrics, never by a retained hidden list. */
@Composable
internal fun LyricsScreenAwake(isPlaying: Boolean, visible: Boolean, onScreen: Boolean) {
    val window = LocalActivity.current?.window
    val keepAwake = isPlaying && visible && onScreen
    DisposableEffect(window, keepAwake) {
        if (keepAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
