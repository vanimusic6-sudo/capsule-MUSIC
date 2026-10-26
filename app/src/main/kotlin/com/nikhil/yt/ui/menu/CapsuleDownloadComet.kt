package com.nikhil.yt.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.component.VeluneLoader

/**
 * Compatibility entry point for older More menus. All download progress
 * visuals now delegate to the one canonical loader used by local playlists;
 * there is no second independently timed orbit.
 */
@Composable
internal fun CapsuleDownloadComet() {
    VeluneLoader(size = 24.dp)
}
