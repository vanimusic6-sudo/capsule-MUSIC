package com.nikhil.yt.ui.component

import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Swallows the auto-repeat a held Back button produces.
 *
 * Holding the navigation bar's Back button makes the system deliver a stream of back events, and
 * each one pops another destination — so a long press walks several screens down the settings tree
 * instead of stepping back once. A person pressing Back twice on purpose is far slower than that
 * stream, so the two can be told apart by timing alone.
 *
 * Three details matter as much as the timing:
 *
 * - It is a real dispatcher callback, not a `BackHandler`. It has to step aside and re-dispatch so
 *   the genuine handler still runs, and that means flipping `isEnabled` synchronously; a
 *   `BackHandler` only applies its enabled state on the next recomposition, so re-dispatching from
 *   one would re-enter this callback instead of reaching the handler behind it.
 * - It must be composed after the NavHost and inside the same subcomposition. Back callbacks are
 *   consulted newest-first, and Material's `Scaffold` subcomposes its content during the measure
 *   pass, so a guard registered from the outer composition is registered *before* navigation's own
 *   callback and never sees an event.
 * - It is enabled only when a back press would actually pop something. An always-enabled callback
 *   tells the system the app handles every back press, which suppresses the predictive-back
 *   animation the platform shows when Back is about to leave the app. There is nothing to guard at
 *   the root anyway: with an empty back stack there is no second screen to fall through to.
 *
 * Re-dispatching preserves behaviour exactly. This callback decides nothing itself — it hands the
 * event to whichever handler would have received it, and only drops the repeats.
 */
@Composable
fun BackRepeatGuard(
    enabled: Boolean,
    minIntervalMillis: Long = BackRepeatMinIntervalMillis,
    // The clock is a parameter because the behaviour is entirely about timing, and a test has no
    // way to move SystemClock in step with Compose's own test clock.
    now: () -> Long = { SystemClock.uptimeMillis() },
) {
    val dispatcher =
        LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher ?: return
    val currentNow = rememberUpdatedState(now)

    val callback =
        remember(dispatcher, minIntervalMillis) {
            object : OnBackPressedCallback(false) {
                // Nullable rather than a sentinel: `now - Long.MIN_VALUE` overflows to a negative
                // number, which reads as "too soon" forever and swallows every back press.
                private var lastHandledAt: Long? = null

                override fun handleOnBackPressed() {
                    val at = currentNow.value()
                    val previous = lastHandledAt
                    if (previous != null && at - previous < minIntervalMillis) return

                    lastHandledAt = at
                    isEnabled = false
                    try {
                        dispatcher.onBackPressed()
                    } finally {
                        isEnabled = true
                    }
                }
            }
        }

    // Updated in place rather than by re-registering: recreating the callback would move it to the
    // newest position again and reset the repeat window on every navigation.
    SideEffect { callback.isEnabled = enabled }

    DisposableEffect(dispatcher, callback) {
        dispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
}

/**
 * Comfortably longer than key auto-repeat, comfortably shorter than two deliberate presses. Key
 * repeat runs at roughly one event every 50ms; a person stepping back twice on purpose takes far
 * more than this.
 */
const val BackRepeatMinIntervalMillis = 150L
