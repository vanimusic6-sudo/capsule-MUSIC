package com.nikhil.yt.ui.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavBackStackEntry

/**
 * The entry's [SavedStateHandle], or null once the entry is gone.
 *
 * `NavBackStackEntry.savedStateHandle` is backed by a ViewModel, so reading it after the entry has
 * been destroyed throws `IllegalStateException: You cannot access the NavBackStackEntry's ViewModels
 * after the NavBackStackEntry is destroyed`.
 *
 * That is easy to hit by accident. Screens observe `currentBackStackEntryAsState()`, which reports
 * whichever entry is globally current rather than the screen's own, and during rapid navigation that
 * value briefly refers to an entry that has just been popped and destroyed. A recomposition then
 * reads the handle and the app dies — which is exactly the crash reported from a heavy tab-switching
 * session on device.
 *
 * Every access to a `NavBackStackEntry`'s saved state goes through here. A `StateFlow` already
 * obtained from a live handle stays safe to collect afterwards; it is reaching for the handle itself
 * that must be guarded.
 */
fun NavBackStackEntry.liveSavedStateHandle(): SavedStateHandle? {
    if (lifecycle.currentState == Lifecycle.State.DESTROYED) return null
    // The lifecycle check and the access are both on the main thread, so nothing can destroy the
    // entry between them; runCatching covers the remaining case of an entry whose saved-state
    // registry was never attached, which throws from the same accessor.
    return runCatching { savedStateHandle }.getOrNull()
}
