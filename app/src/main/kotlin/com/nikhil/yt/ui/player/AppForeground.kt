/**
 * Capsule MUSIC
 * Whether the app is on screen, for the things that must stop when it is not.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * True while the app is at least STARTED, i.e. actually on screen.
 *
 * Composition does not stop when the app goes to the background — only drawing does. Every timer,
 * every animation clock and every effect keeps running behind a screen nobody is looking at, and
 * the work they cause is invisible in every sense: it cannot be seen, and it does not show up as
 * anything but battery. Anything that exists to be looked at is gated on this.
 */
@Composable
internal fun appIsOnScreen(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var onScreen by
        remember(lifecycleOwner) {
            mutableStateOf(
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            )
        }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, _ ->
                onScreen =
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return onScreen
}
