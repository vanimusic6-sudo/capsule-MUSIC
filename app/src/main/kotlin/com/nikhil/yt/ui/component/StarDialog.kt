/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Suppress("UNUSED_PARAMETER")
@Composable
fun StarDialog(
    onDismissRequest: () -> Unit,
    onStar: () -> Unit,
    onLater: () -> Unit,
) {
    // Compatibility shim only: renders absolutely nothing.
    // Mark the old prompt flow as completed so it cannot appear again.
    LaunchedEffect(Unit) {
        onStar()
    }
}
