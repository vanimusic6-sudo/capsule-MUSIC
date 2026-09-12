/**
 * Capsule MUSIC
 *
 * Shared mini-player entry point for the standard layout and Capsule Dock.
 *
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.nikhil.yt.ui.theme.CapsuleBottomBarEnabledKey
import com.nikhil.yt.utils.rememberPreference
import androidx.compose.ui.Modifier

@Composable
fun MiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    val capsuleDock by rememberPreference(CapsuleBottomBarEnabledKey, false)
    CapsuleMiniPlayer(
        position = position,
        duration = duration,
        modifier = modifier,
        pureBlack = pureBlack,
        standardStyle = !capsuleDock,
    )
}
