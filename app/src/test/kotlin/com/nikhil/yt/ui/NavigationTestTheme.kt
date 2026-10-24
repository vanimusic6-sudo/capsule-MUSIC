package com.nikhil.yt.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.nikhil.yt.LocalPlayerAwareWindowInsets

/** Match the required inset provider installed by MainActivity around the real NavHost. */
@Composable
internal fun NavigationTestTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0)) {
        MaterialTheme(content = content)
    }
}
