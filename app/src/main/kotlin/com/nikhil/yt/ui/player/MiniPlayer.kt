/**
 * Capsule MUSIC
 *
 * Single mini-player entry point. Alternative Velune mini-player skins were
 * intentionally removed; new Capsule-native designs can be added here later.
 *
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun MiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    CapsuleMiniPlayer(
        position = position,
        duration = duration,
        modifier = modifier,
        pureBlack = pureBlack,
    )
}
