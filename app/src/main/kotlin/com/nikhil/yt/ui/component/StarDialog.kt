/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Compatibility shim for legacy call sites.
 *
 * The old Velune "star the author" prompt has been removed from Capsule.
 * No dialog, text, external profile link, or GitHub-star button is rendered.
 * The first legacy invocation marks the prompt as completed through [onStar]
 * so the old launch-counter path permanently stops requesting it.
 */
@Composable
fun StarDialog(
    onDismissRequest: () -> Unit,
    onStar: () -> Unit,
    onLater: () -> Unit,
) {
    LaunchedEffect(Unit) {
        onStar()
    }
}
